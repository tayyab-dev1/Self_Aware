package com.diversive.school.platform.agent;

import com.diversive.agent.spi.EntityMatch;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How the school's resolvers match what the user typed: every word typed is a whole word of the record's
 * name, in any order and any case. "class 5 blue" finds "Class 5 Blue"; "blue" finds every Blue section;
 * "5" does not find "Class 15".
 *
 * <p>In SQL, compare {@link #wordsOf(String)} of the name column with the typed words joined by spaces:
 * {@code WORDS_OF(label) @> string_to_array(:words, ' ')}.
 */
public final class NameSearch {

    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private NameSearch() {
    }

    /** Splits a SQL text expression into lower-case words the same way {@link #words(String)} does. */
    public static String wordsOf(String sqlExpression) {
        return "regexp_split_to_array(lower(" + sqlExpression + "), '[^[:alnum:]]+')";
    }

    /** "Ahmed's  Class-5!" becomes [ahmed, s, class, 5]. */
    public static List<String> words(String text) {
        return Arrays.stream(NOT_A_WORD.split(text.toLowerCase(Locale.ROOT)))
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** The words as the single SQL parameter {@code :words}. */
    public static String sqlWords(List<String> words) {
        return String.join(" ", words);
    }

    /**
     * When some matches are named exactly what was typed, only those: "class 5" is Class 5, not Class 5A too.
     * Otherwise every match stays, and several mean the user must choose.
     */
    public static List<EntityMatch> preferExact(String raw, List<EntityMatch> matches) {
        List<String> typed = words(raw);
        List<EntityMatch> exact = matches.stream().filter(match -> words(match.label()).equals(typed)).toList();
        return exact.isEmpty() ? matches : exact;
    }
}
