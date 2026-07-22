package com.flatts.spawndoctor.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RuleResult} carries three strings that are easy to confuse, and confusing
 * them is what produced "Inside world border | inside the world border" - a whole
 * table column spent restating its own label, then truncating.
 */
class RuleResultTest {

    @Test
    @DisplayName("summary is the measurement, not the prose")
    void summaryIsTheValue() {
        RuleResult result = RuleResult.fail(SpawnRule.PLAYER_DISTANCE,
            "0.9 blocks / 24",
            "0.9 blocks from Dev, inside the 24-block no-spawn bubble");

        assertEquals("0.9 blocks / 24", result.summary(),
            "the column shows the measurement; the sentence is for hover");
    }

    @Test
    @DisplayName("a specific remedy overrides the rule's generic one")
    void specificRemedyWins() {
        // SPAWN_RULES can fail six ways. Once the auditor knows which, reciting all
        // six is worse than saying nothing.
        RuleResult result = RuleResult.fail(SpawnRule.SPAWN_RULES, "light 14", "too bright",
            "A light source gives this block light level 14. Remove it to allow spawns.");

        assertTrue(result.effectiveRemedy().contains("light level 14"));
        assertFalse(result.effectiveRemedy().equals(SpawnRule.SPAWN_RULES.remedy()));
    }

    @Test
    @DisplayName("without a specific remedy it falls back to the rule's own")
    void genericRemedyFallback() {
        RuleResult result = RuleResult.fail(SpawnRule.PLAYER_DISTANCE, "1 block", "too close");
        assertEquals(SpawnRule.PLAYER_DISTANCE.remedy(), result.effectiveRemedy());
    }

    @Test
    @DisplayName("of() picks the branch and its matching text")
    void ofSelectsBranch() {
        RuleResult ok = RuleResult.of(SpawnRule.WORLD_BORDER, true, "inside", "in", "outside", "out");
        RuleResult bad = RuleResult.of(SpawnRule.WORLD_BORDER, false, "inside", "in", "outside", "out");

        assertSame(Verdict.PASS, ok.verdict());
        assertEquals("inside", ok.summary());
        assertSame(Verdict.FAIL, bad.verdict());
        assertEquals("outside", bad.summary());
    }

    @Test
    @DisplayName("only FAIL blocks a spawn")
    void onlyFailBlocks() {
        // UNKNOWN means the auditor could not measure it, and SKIPPED means it did not
        // apply. Neither is evidence of rejection, and treating them as one would
        // invent findings.
        assertTrue(RuleResult.fail(SpawnRule.PLACEMENT, "v", "d").verdict().blocks());
        assertFalse(RuleResult.pass(SpawnRule.PLACEMENT, "v", "d").verdict().blocks());
        assertFalse(RuleResult.unknown(SpawnRule.PLACEMENT, "d").verdict().blocks());
        assertFalse(RuleResult.skipped(SpawnRule.PLACEMENT, "d").verdict().blocks());
        assertFalse(RuleResult.marginal(SpawnRule.PLACEMENT, "v", "d", null).verdict().blocks());
    }

    @Test
    @DisplayName("PASS and MARGINAL permit a spawn; nothing else does")
    void permitsIsPassOrMarginal() {
        assertTrue(Verdict.PASS.permits());
        assertTrue(Verdict.MARGINAL.permits());
        assertFalse(Verdict.FAIL.permits());
        assertFalse(Verdict.UNKNOWN.permits());
        assertFalse(Verdict.SKIPPED.permits());
    }
}
