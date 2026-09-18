package com.diversive.agent.registry;

import com.diversive.agent.metadata.CapabilityMetadata;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A capability as the backend knows it: the public metadata, plus the handler that runs it.
 * The handler part never leaves the backend.
 *
 * @param requestType         the handler's request record, or null when it takes no input
 * @param requestFields       the request record's fields by JSON name, in declaration order; empty when
 *                            it takes no input
 * @param implemented         false when the method carries {@code @AgentNotImplemented}: declared for
 *                            retrieval and planning, refused by preflight and execute
 * @param requestParameter    where the request record goes in the handler's arguments; -1 when it takes none
 * @param userParameter       where the signed-in user goes in the handler's arguments; -1 when it takes none
 * @param responseProperties  the JSON property names of what the handler returns; empty for {@code void}
 */
public record RegisteredCapability(
        CapabilityMetadata metadata,
        Class<?> handlerType,
        Method handlerMethod,
        Class<?> requestType,
        Map<String, RecordComponent> requestFields,
        boolean implemented,
        int requestParameter,
        int userParameter,
        Set<String> responseProperties) {

    public RegisteredCapability {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(handlerType, "handlerType");
        Objects.requireNonNull(handlerMethod, "handlerMethod");
        requestFields = Collections.unmodifiableMap(new LinkedHashMap<>(requestFields));
        responseProperties = Collections.unmodifiableSet(new TreeSet<>(responseProperties));
    }

    public String id() {
        return metadata.id();
    }

    /** The record field behind a parameter, by the parameter's JSON name. */
    public Optional<RecordComponent> requestField(String paramName) {
        return Optional.ofNullable(requestFields.get(paramName));
    }

    RegisteredCapability withMetadata(CapabilityMetadata newMetadata) {
        return new RegisteredCapability(newMetadata, handlerType, handlerMethod, requestType, requestFields, implemented,
                requestParameter, userParameter, responseProperties);
    }
}
