package com.diversive.agent.preflight;

import com.diversive.agent.annotation.BlastRadius;
import com.diversive.agent.step.PreparedStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Joins the steps' lines into the one message the user approves, and adds the warnings the metadata
 * implies. One dialog, one approval, whatever the step count.
 *
 * <pre>
 * one write:     Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp. This cannot be undone.
 *
 * several:       1. Record PKR 5,000 received in cash against Ahmed Raza's September 2026 invoice.
 *                2. Send a fee reminder to 5 guardians in Class 5 Blue by WhatsApp.
 *
 *                Step 2 cannot be undone.
 * </pre>
 *
 * Only writes have lines; reads need no approval. Step numbers in the message count the lines the user sees.
 */
final class ConfirmationComposer {

    private ConfirmationComposer() {
    }

    static Confirmation compose(List<PreparedStep> steps) {
        List<PreparedStep> writes = steps.stream().filter(PreparedStep::writes).toList();
        if (writes.isEmpty()) {
            return new Confirmation(null, List.of());
        }
        boolean single = writes.size() == 1;

        List<String> warnings = new ArrayList<>();
        warning(writes, step -> step.metadata().reverses() == null, single, "cannot be undone.", "cannot be undone.")
                .ifPresent(warnings::add);
        warning(writes, step -> step.metadata().blastRadius() == BlastRadius.BRANCH, single,
                "affects everything of its kind in the branch.", "affect everything of their kind in the branch.")
                .ifPresent(warnings::add);
        warning(writes, step -> step.metadata().blastRadius() == BlastRadius.ORGANISATION, single,
                "affects everything of its kind in every branch.", "affect everything of their kind in every branch.")
                .ifPresent(warnings::add);

        String lines = single
                ? writes.getFirst().line()
                : IntStream.range(0, writes.size())
                        .mapToObj(i -> (i + 1) + ". " + writes.get(i).line())
                        .collect(Collectors.joining("\n"));
        String text = warnings.isEmpty()
                ? lines
                : lines + (single ? " " : "\n\n") + String.join(" ", warnings);
        return new Confirmation(text, warnings);
    }

    private static Optional<String> warning(List<PreparedStep> writes, Predicate<PreparedStep> applies,
                                            boolean single, String oneVerb, String severalVerb) {
        List<Integer> positions = IntStream.range(0, writes.size())
                .filter(i -> applies.test(writes.get(i)))
                .mapToObj(i -> i + 1)
                .toList();
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        if (single) {
            return Optional.of("This " + oneVerb);
        }
        if (positions.size() == 1) {
            return Optional.of("Step " + positions.getFirst() + " " + oneVerb);
        }
        String numbers = positions.subList(0, positions.size() - 1).stream().map(String::valueOf)
                .collect(Collectors.joining(", ")) + " and " + positions.getLast();
        return Optional.of("Steps " + numbers + " " + severalVerb);
    }

    /** @param text absent when nothing needs approving */
    record Confirmation(String text, List<String> warnings) {
    }
}
