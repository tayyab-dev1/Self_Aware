package com.diversive.agent.registry;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and fills the {@code {placeholder}} names in a confirmation, pending or reply template. */
public final class TemplatePlaceholders {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_]*)}");

    private TemplatePlaceholders() {
    }

    public static Set<String> names(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** A description of what is malformed, e.g. a stray brace or {@code {Bad Name}}. */
    public static Optional<String> malformation(String template) {
        String withoutValidPlaceholders = PLACEHOLDER.matcher(template).replaceAll("");
        if (withoutValidPlaceholders.indexOf('{') >= 0 || withoutValidPlaceholders.indexOf('}') >= 0) {
            return Optional.of("has a brace that is not a {lower_snake_case} placeholder");
        }
        return Optional.empty();
    }

    /** Replaces every placeholder with what {@code valueOf} gives for its name. Plain substitution, nothing else. */
    public static String fill(String template, Function<String, String> valueOf) {
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(valueOf.apply(match.group(1))));
    }
}
