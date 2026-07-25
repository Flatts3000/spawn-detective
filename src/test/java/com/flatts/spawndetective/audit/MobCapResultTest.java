package com.flatts.spawndetective.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a full mob cap reads, which is not the same question as whether it is full.
 *
 * <p>A full monster cap is the steady state of a normal overworld, not a defect. One
 * player makes the spawn-eligible area 17x17 chunks, which is exactly the
 * {@code MAGIC_NUMBER} the cap formula divides by, so the cap is exactly 70 - and
 * with caves under the world the count sits pinned at that ceiling more or less
 * permanently.
 *
 * <p>Reporting it as a definitive no made "the mob cap is full" the headline for most
 * probes in a real world, which buries the per-block answer the probe was pointed at.
 * This mod already learned that shape once with {@code PLAYER_DISTANCE}: a condition
 * that is nearly always true is nearly always the headline, and a headline that is
 * always the same has stopped being an answer.
 *
 * <p>It is also not true tick to tick. The cap is rebuilt every tick and mobs die and
 * despawn continuously, so spawns keep happening while it sits at its ceiling. The
 * honest reading is competition, not refusal.
 *
 * <p>Tested here rather than in-world because a full cap cannot be built in a
 * GameTest: a headless server has no players, so it has no spawnable chunks and no
 * cap to fill.
 */
class MobCapResultTest {

    @Test
    @DisplayName("under the cap passes, and says how much room is left")
    void underTheCapPasses() {
        RuleResult result = SpawnAuditor.globalCapResult(62, 70);

        assertSame(Verdict.PASS, result.verdict());
        // "62 / 70" and "under the cap" are different answers, and only the first
        // tells someone watching a farm fill up how close they are to the change.
        assertTrue(result.value().contains("62 / 70"), "got: " + result.value());
    }

    @Test
    @DisplayName("a full cap is marginal, not a refusal")
    void fullCapIsMarginal() {
        RuleResult result = SpawnAuditor.globalCapResult(70, 70);

        assertSame(Verdict.MARGINAL, result.verdict());
        assertFalse(result.verdict().blocks(),
            "a full cap oscillates at its ceiling many times a second; spawns keep happening");
        assertTrue(result.verdict().permits());
        assertTrue(result.value().contains("70 / 70"), "the measurement still has to be visible");
    }

    @Test
    @DisplayName("over the cap is still marginal, never worse")
    void overTheCapIsStillMarginal() {
        // The count can exceed the cap: mobs arrive by means other than natural
        // spawning, and the cap is only consulted when deciding whether to add more.
        RuleResult result = SpawnAuditor.globalCapResult(93, 70);

        assertSame(Verdict.MARGINAL, result.verdict());
        assertFalse(result.verdict().blocks());
    }

    @Test
    @DisplayName("a full cap says what to do about it, and it is not despair")
    void fullCapIsActionable() {
        RuleResult result = SpawnAuditor.globalCapResult(70, 70);

        assertTrue(result.effectiveRemedy() != null && !result.effectiveRemedy().isBlank(),
            "a temporary condition is actionable by definition");
        // The action is to reduce the competition, not to accept defeat.
        assertTrue(result.effectiveRemedy().toLowerCase().contains("cave")
            || result.effectiveRemedy().toLowerCase().contains("clear"),
            "got: " + result.effectiveRemedy());
    }

    @Test
    @DisplayName("the per-player cap reads the same way")
    void localCapMatchesGlobal() {
        assertSame(Verdict.PASS, SpawnAuditor.localCapResult(true, 70).verdict());

        RuleResult full = SpawnAuditor.localCapResult(false, 70);
        assertSame(Verdict.MARGINAL, full.verdict());
        assertFalse(full.verdict().blocks(),
            "the per-player cap is the same oscillating condition as the global one");
    }

    @Test
    @DisplayName("neither cap detail is long enough to ellipse in the banner")
    void detailsFitTheBanner() {
        // A full cap is now a caveat, and a caveat is shown in place of "every gate
        // passes" - so one that runs past two banner lines replaces a complete wrong
        // answer with an incomplete one.
        for (RuleResult result : new RuleResult[] {
            SpawnAuditor.globalCapResult(70, 70), SpawnAuditor.localCapResult(false, 70)
        }) {
            assertTrue(result.detail().length() <= 130,
                result.rule() + " detail is " + result.detail().length() + " chars: " + result.detail());
        }
    }
}
