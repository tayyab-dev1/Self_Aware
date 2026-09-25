package com.diversive.agent.registry;

import com.diversive.agent.annotation.AgentCapability;
import com.diversive.agent.annotation.AgentEffect;
import com.diversive.agent.annotation.AgentNotImplemented;
import com.diversive.agent.annotation.AgentParam;
import com.diversive.agent.annotation.AgentPrecondition;
import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.metadata.CapabilityMetadata;
import com.diversive.agent.metadata.EffectMetadata;
import com.diversive.agent.metadata.ParamMetadata;
import com.diversive.agent.metadata.ParamType;
import com.diversive.agent.metadata.PreconditionMetadata;
import com.diversive.agent.spi.UserContext;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Reads the {@code @Agent*} annotations on a handler type into unversioned registry entries, and
 * reports every rule a single entry breaks on its own. Rules that need the whole registry or the
 * application's beans live in {@link RegistryRules}.
 */
final class CapabilityScanner {

    static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$");
    static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)*$");

    private final ObjectMapper objectMapper;

    CapabilityScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void scan(Class<?> handlerType, List<RegisteredCapability> into, List<String> problems) {
        Class<?> type = ClassUtils.getUserClass(handlerType);
        Map<Method, AgentCapability> methods = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<AgentCapability>) method ->
                        method.getAnnotation(AgentCapability.class));
        methods.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Method, AgentCapability> entry) -> entry.getValue().id())
                        .thenComparing(entry -> entry.getKey().getName()))
                .forEach(entry -> scanMethod(type, entry.getKey(), entry.getValue(), into, problems));
    }

    private void scanMethod(Class<?> type, Method method, AgentCapability capability,
                            List<RegisteredCapability> into, List<String> problems) {
        String where = capability.id() + " (" + type.getSimpleName() + "." + method.getName() + ")";
        Consumer<String> problem = message -> problems.add(where + ": " + message);

        if (!CAPABILITY_ID.matcher(capability.id()).matches()) {
            problem.accept("id must be dotted lower-case, like fee.reminder.send");
        }
        if (!SNAKE_CASE.matcher(capability.module()).matches()) {
            problem.accept("module must be lower_snake_case, like fee");
        }
        if (capability.description().isBlank()) {
            problem.accept("description is empty");
        }
        if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
            problem.accept("is not a request-mapped controller method");
        }
        boolean readOnly = capability.readOnly();
        if (readOnly != (capability.blastRadius() == BlastRadius.NONE)) {
            problem.accept("readOnly must be true exactly when blastRadius is NONE");
        }
        String reverses = blankToNull(capability.reverses());
        if (reverses != null && readOnly) {
            problem.accept("a read-only capability reverses nothing, so reverses must be empty");
        }
        if (reverses != null && !CAPABILITY_ID.matcher(reverses).matches()) {
            problem.accept("reverses must be a capability id, got '" + reverses + "'");
        }
        List<String> siblings = sortedDistinct(capability.disambiguateFrom(), "disambiguateFrom", problem);
        if (siblings.contains(capability.id())) {
            problem.accept("disambiguateFrom names the capability itself");
        }

        HandlerParameters parameters = handlerParameters(method, problem);
        Class<?> requestType = parameters.requestIndex() < 0 ? null : method.getParameterTypes()[parameters.requestIndex()];
        Map<String, RequestField> fields = requestType == null
                ? Map.of()
                : requestFields(requestType, problem);
        List<ParamMetadata> params = params(method, requestType, fields, problem);
        List<PreconditionMetadata> preconditions = preconditions(method, problem);
        EffectMetadata effect = effect(method, readOnly, problem);
        if (effect == null) {
            return;
        }

        CapabilityMetadata metadata = new CapabilityMetadata(
                capability.id(),
                null,
                capability.module(),
                readOnly,
                capability.blastRadius(),
                reverses,
                normalizeSpace(capability.description()),
                siblings,
                params,
                preconditions,
                effect);
        Map<String, RecordComponent> requestFields = new LinkedHashMap<>();
        fields.values().forEach(field -> requestFields.put(field.name(), field.component()));
        into.add(new RegisteredCapability(metadata, type, method, requestType, requestFields,
                !method.isAnnotationPresent(AgentNotImplemented.class), parameters.requestIndex(), parameters.userIndex(),
                responseProperties(method)));
    }

    /**
     * A handler takes at most one request record, and may also take the signed-in {@link UserContext}, which
     * execute passes in and the gateway resolves for plain HTTP calls.
     */
    private static HandlerParameters handlerParameters(Method method, Consumer<String> problem) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        int userIndex = -1;
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == UserContext.class && userIndex < 0) {
                userIndex = i;
            } else {
                others.add(i);
            }
        }
        if (others.size() > 1) {
            problem.accept("takes " + others.size() + " parameters; a capability handler takes at most one request record");
            return new HandlerParameters(-1, userIndex);
        }
        if (others.size() == 1 && !parameterTypes[others.getFirst()].isRecord()) {
            problem.accept("its parameter " + parameterTypes[others.getFirst()].getSimpleName() + " must be a record");
            return new HandlerParameters(-1, userIndex);
        }
        return new HandlerParameters(others.isEmpty() ? -1 : others.getFirst(), userIndex);
    }

    /** The JSON property names of what the handler returns; none for {@code void}. */
    private Set<String> responseProperties(Method method) {
        Class<?> returnType = method.getReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return Set.of();
        }
        BeanDescription description = objectMapper.getSerializationConfig()
                .introspect(objectMapper.constructType(method.getGenericReturnType()));
        Set<String> names = new TreeSet<>();
        description.findProperties().forEach(property -> names.add(property.getName()));
        return names;
    }

    /** Where the request record and the signed-in user go in the handler's arguments; -1 when absent. */
    private record HandlerParameters(int requestIndex, int userIndex) {
    }

    // --- Parameters ----------------------------------------------------------------------------

    private List<ParamMetadata> params(Method method, Class<?> requestType, Map<String, RequestField> fields,
                                       Consumer<String> problem) {
        Map<String, AgentParam> declared = new LinkedHashMap<>();
        for (AgentParam param : method.getAnnotationsByType(AgentParam.class)) {
            if (declared.putIfAbsent(param.name(), param) != null) {
                problem.accept("param '" + param.name() + "' is declared twice");
            }
        }

        for (String name : declared.keySet()) {
            if (!fields.containsKey(name)) {
                problem.accept("param '" + name + "' is not a field of "
                        + (requestType == null ? "the request, because the handler takes none"
                        : requestType.getSimpleName()));
            }
        }

        List<ParamMetadata> params = new ArrayList<>();
        for (RequestField field : fields.values()) {
            AgentParam param = declared.get(field.name());
            if (param == null) {
                problem.accept("request field '" + field.name() + "' has no @AgentParam");
                continue;
            }
            params.add(param(field, param, problem));
        }
        return params;
    }

    private ParamMetadata param(RequestField field, AgentParam param, Consumer<String> problem) {
        String prefix = "param '" + field.name() + "' ";
        if (param.meaning().isBlank()) {
            problem.accept(prefix + "has no meaning");
        }
        String resolver = blankToNull(param.resolver());
        String label = blankToNull(param.label());
        if ((resolver == null) != (label == null)) {
            problem.accept(prefix + "must set resolver and label together, or neither");
        }
        if (resolver != null && !SNAKE_CASE.matcher(resolver).matches()) {
            problem.accept(prefix + "resolver must be lower_snake_case");
        }
        if (label != null && !SNAKE_CASE.matcher(label).matches()) {
            problem.accept(prefix + "label must be lower_snake_case");
        }
        if (resolver != null && field.multiple()) {
            problem.accept(prefix + "is a list, and preflight cannot look up a list of names yet; "
                    + "take one name, or no resolver");
        }

        List<String> allowed;
        if (!field.enumValues().isEmpty()) {
            if (param.allowed().length > 0) {
                problem.accept(prefix + "is an enum, which lists its own values; remove allowed");
            }
            allowed = field.enumValues();
        } else {
            allowed = List.of(param.allowed());
            if (!allowed.isEmpty() && field.type() != ParamType.STRING) {
                problem.accept(prefix + "may only list allowed values when it is text");
            }
        }
        String defaultValue = blankToNull(param.defaultValue());
        if (defaultValue != null && !allowed.isEmpty() && !allowed.contains(defaultValue)) {
            problem.accept(prefix + "default '" + defaultValue + "' is not one of " + allowed);
        }

        return new ParamMetadata(field.name(), field.type(), field.multiple(), field.required(),
                normalizeSpace(param.meaning()), resolver, label, null, List.of(), allowed, defaultValue);
    }

    private Map<String, RequestField> requestFields(Class<?> requestType, Consumer<String> problem) {
        BeanDescription description = objectMapper.getDeserializationConfig()
                .introspect(objectMapper.constructType(requestType));
        Map<String, String> jsonNames = new HashMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            jsonNames.put(property.getInternalName(), property.getName());
        }

        Map<String, RequestField> fields = new LinkedHashMap<>();
        for (RecordComponent component : requestType.getRecordComponents()) {
            String name = jsonNames.getOrDefault(component.getName(), component.getName());
            boolean multiple = Collection.class.isAssignableFrom(component.getType());
            Class<?> valueType = multiple ? elementType(component.getGenericType()) : component.getType();

            ParamType type = valueType == null ? null : paramType(valueType);
            if (type == null) {
                problem.accept("request field '" + name + "' has a type the planner cannot fill: "
                        + component.getGenericType().getTypeName());
                type = ParamType.STRING;
            }
            List<String> enumValues = valueType != null && valueType.isEnum()
                    ? Arrays.stream(valueType.getEnumConstants())
                            .map(constant -> objectMapper.convertValue(constant, String.class))
                            .toList()
                    : List.of();
            boolean required = component.getType().isPrimitive() || isRequired(requestType, component);
            fields.put(name, new RequestField(name, type, multiple, required, enumValues, component));
        }
        return fields;
    }

    private static Class<?> elementType(Type genericType) {
        if (genericType instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return null;
    }

    private static ParamType paramType(Class<?> type) {
        if (type == String.class || type.isEnum()) {
            return ParamType.STRING;
        }
        if (type == Long.class || type == long.class || type == Integer.class || type == int.class
                || type == Short.class || type == short.class) {
            return ParamType.INTEGER;
        }
        if (type == BigDecimal.class) {
            return ParamType.DECIMAL;
        }
        if (type == Boolean.class || type == boolean.class) {
            return ParamType.BOOLEAN;
        }
        if (type == LocalDate.class) {
            return ParamType.DATE;
        }
        return null;
    }

    private static final List<Class<? extends Annotation>> REQUIRED_MARKERS =
            List.of(NotNull.class, NotBlank.class, NotEmpty.class);

    private static boolean isRequired(Class<?> recordType, RecordComponent component) {
        List<java.lang.reflect.AnnotatedElement> places = new ArrayList<>();
        places.add(component);
        places.add(component.getAccessor());
        try {
            Field field = recordType.getDeclaredField(component.getName());
            places.add(field);
        } catch (NoSuchFieldException ignored) {
            // A record always has a field per component; nothing else to inspect.
        }
        return places.stream().anyMatch(place -> REQUIRED_MARKERS.stream().anyMatch(place::isAnnotationPresent));
    }

    // --- Preconditions and effect --------------------------------------------------------------

    private static List<PreconditionMetadata> preconditions(Method method, Consumer<String> problem) {
        List<PreconditionMetadata> preconditions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentPrecondition precondition : method.getAnnotationsByType(AgentPrecondition.class)) {
            String prefix = "precondition '" + precondition.id() + "' ";
            if (!SNAKE_CASE.matcher(precondition.id()).matches()) {
                problem.accept(prefix + "id must be lower_snake_case");
            }
            if (!seen.add(precondition.id())) {
                problem.accept(prefix + "is declared twice");
            }
            if (precondition.text().isBlank() || precondition.hint().isBlank()) {
                problem.accept(prefix + "needs both text and hint");
            }
            preconditions.add(new PreconditionMetadata(precondition.id(),
                    normalizeSpace(precondition.text()), normalizeSpace(precondition.hint())));
        }
        return preconditions;
    }

    private static EffectMetadata effect(Method method, boolean readOnly, Consumer<String> problem) {
        AgentEffect effect = method.getAnnotation(AgentEffect.class);
        if (effect == null) {
            problem.accept("has no @AgentEffect");
            return null;
        }
        String creates = blankToNull(effect.creates());
        String notifies = blankToNull(effect.notifies());
        String confirmation = blankToNull(effect.confirmationTemplate());
        String pending = blankToNull(effect.pendingTemplate());
        String reply = blankToNull(effect.replyTemplate());

        if (reply == null) {
            problem.accept("reply template is empty");
        }
        if (readOnly && (creates != null || notifies != null)) {
            problem.accept("a read creates and notifies nothing, so creates and notifies must be empty");
        }
        if (readOnly && (confirmation != null || pending != null)) {
            problem.accept("a read needs no confirmation, so its confirmation and pending templates must be empty");
        }
        if (!readOnly && confirmation == null) {
            problem.accept("a write needs a confirmation template");
        }
        Map<String, String> templates = new LinkedHashMap<>();
        templates.put("confirmation template", confirmation);
        templates.put("pending template", pending);
        templates.put("reply template", reply);
        templates.forEach((name, template) -> {
            if (template != null) {
                TemplatePlaceholders.malformation(template).ifPresent(issue -> problem.accept(name + " " + issue));
            }
        });
        if (pending != null && !TemplatePlaceholders.names(pending).isEmpty()) {
            problem.accept("pending template may not contain placeholders, because its values are not known yet");
        }

        List<String> facts = sortedDistinct(effect.facts(), "facts", problem);
        facts.stream()
                .filter(fact -> !SNAKE_CASE.matcher(fact).matches())
                .forEach(fact -> problem.accept("fact '" + fact + "' must be lower_snake_case"));

        return new EffectMetadata(creates, notifies, confirmation, pending, reply == null ? "" : reply, facts);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private static List<String> sortedDistinct(String[] values, String what, Consumer<String> problem) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            if (!distinct.add(value)) {
                problem.accept(what + " lists '" + value + "' twice");
            }
        }
        return distinct.stream().sorted().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : normalizeSpace(value);
    }

    /** Text blocks bring line breaks and indentation; the metadata carries one clean line. */
    private static String normalizeSpace(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private record RequestField(String name, ParamType type, boolean multiple, boolean required,
                                List<String> enumValues, RecordComponent component) {
    }
}
