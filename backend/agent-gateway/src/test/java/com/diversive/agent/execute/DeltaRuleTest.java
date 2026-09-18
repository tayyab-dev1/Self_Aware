package com.diversive.agent.execute;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeltaRuleTest {

    private final DeltaRule rule = new DeltaRule(20, 5);

    @Test
    void anUnchangedCountNeverMoved() {
        assertThat(rule.movedMaterially(5, 5)).isFalse();
        assertThat(rule.movedMaterially(400, 400)).isFalse();
    }

    @Test
    void anyChangeToASmallCountIsMaterial() {
        assertThat(rule.movedMaterially(17, 16)).isTrue();
        assertThat(rule.movedMaterially(17, 18)).isTrue();
        assertThat(rule.movedMaterially(400, 17)).isTrue();
    }

    @Test
    void aLargeCountMayMoveUpToTheTolerance() {
        assertThat(rule.movedMaterially(400, 420)).isFalse();
        assertThat(rule.movedMaterially(400, 380)).isFalse();
        assertThat(rule.movedMaterially(400, 421)).isTrue();
        assertThat(rule.movedMaterially(17, 400)).isTrue();
    }
}
