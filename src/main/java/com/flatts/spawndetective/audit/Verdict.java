package com.flatts.spawndetective.audit;

import net.minecraft.ChatFormatting;

/**
 * Outcome of one {@link SpawnRule} evaluation.
 *
 * <p>{@link #MARGINAL} exists because several vanilla spawn rules are not
 * deterministic - {@code Monster.isDarkEnoughToSpawn} samples the RNG twice, so
 * a position can pass on one game tick and fail on the next. Reporting a single
 * boolean for those rules would be a lie; the auditor samples them instead and
 * reports the pass rate.
 */
public enum Verdict {
    /** The rule allows a spawn here, on every sample. */
    PASS(ChatFormatting.GREEN, "PASS"),
    /** The rule allows a spawn here only some of the time. Detail carries the rate. */
    MARGINAL(ChatFormatting.YELLOW, "MARGINAL"),
    /** The rule rejects a spawn here. This is an answer. */
    FAIL(ChatFormatting.RED, "FAIL"),
    /** Not applicable - an earlier rule already decided, or the rule does not apply to this mob. */
    SKIPPED(ChatFormatting.DARK_GRAY, "n/a"),
    /** The rule could not be evaluated (usually a mod threw during the check). Detail says why. */
    UNKNOWN(ChatFormatting.LIGHT_PURPLE, "UNKNOWN");

    private final ChatFormatting color;
    private final String label;

    Verdict(ChatFormatting color, String label) {
        this.color = color;
        this.label = label;
    }

    public ChatFormatting color() {
        return this.color;
    }

    public String label() {
        return this.label;
    }

    /** True when this verdict means "a spawn is possible here, at least sometimes". */
    public boolean permits() {
        return this == PASS || this == MARGINAL;
    }

    /** True when this verdict is a definitive rejection worth reporting as the answer. */
    public boolean blocks() {
        return this == FAIL;
    }
}
