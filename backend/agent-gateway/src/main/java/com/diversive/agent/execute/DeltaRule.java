package com.diversive.agent.execute;

/**
 * When a count has moved too far from what the user confirmed. Someone approved 17, not 400.
 *
 * <p>On a small count, any change counts: a confirmation of 5 guardians must not quietly become 6. On a large
 * count, a change of more than a few percent does.
 *
 * @param smallCount       at or below this many (confirmed or now), any change is material
 * @param tolerancePercent above it, a change larger than this share of the confirmed count is material
 */
public record DeltaRule(long smallCount, int tolerancePercent) {

    public boolean movedMaterially(long confirmed, long current) {
        if (confirmed == current) {
            return false;
        }
        if (Math.min(confirmed, current) <= smallCount) {
            return true;
        }
        return Math.abs(current - confirmed) * 100 > confirmed * tolerancePercent;
    }
}
