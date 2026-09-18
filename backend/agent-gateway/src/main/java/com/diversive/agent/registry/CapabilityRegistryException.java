package com.diversive.agent.registry;

import java.util.List;

/**
 * The annotated capabilities break a registry rule, so the application must not start. Lists every
 * problem at once rather than the first, so one run shows everything to fix.
 */
public class CapabilityRegistryException extends RuntimeException {

    private final List<String> problems;

    public CapabilityRegistryException(List<String> problems) {
        super(format(problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    private static String format(List<String> problems) {
        StringBuilder message = new StringBuilder("Agent capability registry is invalid (")
                .append(problems.size())
                .append(problems.size() == 1 ? " problem):" : " problems):");
        problems.forEach(problem -> message.append(System.lineSeparator()).append("  - ").append(problem));
        return message.toString();
    }
}
