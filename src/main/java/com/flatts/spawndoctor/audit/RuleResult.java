package com.flatts.spawndoctor.audit;

import org.jspecify.annotations.Nullable;

/**
 * One rule's verdict at one position, with the numbers behind it.
 *
 * <p>{@code detail} is the part that turns a verdict into an answer: not
 * "light: FAIL" but "light: FAIL (block light 8, needs 0)". Every FAIL should
 * carry the measured value and the threshold it missed.
 */
public record RuleResult(SpawnRule rule, Verdict verdict, String detail) {

    public static RuleResult pass(SpawnRule rule, String detail) {
        return new RuleResult(rule, Verdict.PASS, detail);
    }

    public static RuleResult fail(SpawnRule rule, String detail) {
        return new RuleResult(rule, Verdict.FAIL, detail);
    }

    public static RuleResult marginal(SpawnRule rule, String detail) {
        return new RuleResult(rule, Verdict.MARGINAL, detail);
    }

    public static RuleResult unknown(SpawnRule rule, String detail) {
        return new RuleResult(rule, Verdict.UNKNOWN, detail);
    }

    public static RuleResult skipped(SpawnRule rule, String detail) {
        return new RuleResult(rule, Verdict.SKIPPED, detail);
    }

    /** PASS/FAIL from a boolean, with a detail string for each branch. */
    public static RuleResult of(SpawnRule rule, boolean ok, String passDetail, String failDetail) {
        return ok ? pass(rule, passDetail) : fail(rule, failDetail);
    }

    /**
     * Verdict from a sample count over a non-deterministic rule.
     * All samples passing is a PASS, none is a FAIL, anything between is MARGINAL
     * with the observed rate.
     */
    public static RuleResult sampled(SpawnRule rule, int passes, int samples, @Nullable String detail) {
        String suffix = detail == null ? "" : " - " + detail;
        if (passes == 0) {
            return fail(rule, "0 of " + samples + " rolls pass" + suffix);
        }
        if (passes == samples) {
            return pass(rule, "all " + samples + " rolls pass" + suffix);
        }
        int percent = Math.round(100.0F * passes / samples);
        return marginal(rule, percent + "% of rolls pass (" + passes + "/" + samples + ")" + suffix);
    }
}
