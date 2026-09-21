package com.diversive.agent.spi;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Turns a name the user typed into the records it could mean, for one entity type. One bean per
 * type that an {@code @AgentParam(resolver)} names; the application refuses to start without it.
 *
 * <p>Scope belongs inside the query, never in a filter afterwards (invariant 7): a record the user
 * may not see must not be returned at all, so "no such record" and "not yours" look the same.
 */
public interface EntityResolver {

    /** The entity type this bean resolves, as {@code @AgentParam(resolver)} names it, e.g. {@code section}. */
    String type();

    /**
     * Every record the user may see that the name could mean. Preflight treats no match as
     * {@code NOT_FOUND}, one as resolved, and several as {@code AMBIGUOUS_ENTITY}.
     *
     * @param raw the user's own words for the record, e.g. "class 5 blue"
     */
    List<EntityMatch> resolve(String raw, UserContext user);

    /**
     * What this resolver searches by, in plain words, e.g. "the student's name, optionally with the class and
     * section, or the admission number". Published with every parameter that names this type, so the planner
     * knows which of the user's words to pass, and the chat can say what to type when nothing matches. Null
     * when there is nothing useful to say.
     */
    default String lookup() {
        return null;
    }

    static EntityResolver of(String type, BiFunction<String, UserContext, List<EntityMatch>> search) {
        return of(type, null, search);
    }

    static EntityResolver of(String type, String lookup, BiFunction<String, UserContext, List<EntityMatch>> search) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(search, "search");
        return new EntityResolver() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public String lookup() {
                return lookup;
            }

            @Override
            public List<EntityMatch> resolve(String raw, UserContext user) {
                return search.apply(raw, user);
            }
        };
    }
}
