package com.flatts.spawndetective.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link SpawnVerdict} is the one place the whole report collapses to an answer, so
 * it gets the most tests.
 *
 * <p>It exists as its own class because of a shipped bug: the resolution used to be
 * inline in the screen renderer, consulted only the mob's half of the pipeline, and
 * printed "SKELETON CAN SPAWN HERE" directly above a section reading "blocked". No
 * test could reach it there. These are the tests that became possible once it moved.
 */
class SpawnVerdictTest {

    private static final BlockPos POS = new BlockPos(0, 64, 0);

    private static PositionReport position(RuleResult... world) {
        return new PositionReport("minecraft:overworld", POS, "minecraft:plains", List.of(world), List.of());
    }

    private static AuditReport.Candidate candidate(RuleResult... rules) {
        return new AuditReport.Candidate(EntityType.ZOMBIE, 1, 1, List.of(rules));
    }

    private static RuleResult passing(SpawnRule rule) {
        return RuleResult.pass(rule, "ok", "fine");
    }

    private static RuleResult blocking(SpawnRule rule) {
        return RuleResult.fail(rule, "no", "blocked");
    }

    @Nested
    @DisplayName("when nothing blocks")
    class Clear {

        @Test
        @DisplayName("reports CAN_SPAWN with no blocker")
        void allClear() {
            SpawnVerdict verdict = SpawnVerdict.of(
                position(passing(SpawnRule.PLAYER_DISTANCE)),
                candidate(passing(SpawnRule.PLACEMENT)));

            assertSame(SpawnVerdict.Tone.CAN_SPAWN, verdict.tone());
            assertTrue(verdict.canSpawn());
            assertNull(verdict.blocker(), "nothing blocks, so nothing should be named");
        }

        @Test
        @DisplayName("verdicts that are not failures do not block")
        void nonFailuresDoNotBlock() {
            // SKIPPED and UNKNOWN mean "no finding", not "rejected". Treating either as
            // a block would invent a reason the auditor never measured.
            SpawnVerdict verdict = SpawnVerdict.of(
                position(RuleResult.unknown(SpawnRule.CATEGORY_GLOBAL_CAP, "no spawn tick yet")),
                candidate(RuleResult.skipped(SpawnRule.POSITION_CHECK, "not a Mob")));

            assertTrue(verdict.canSpawn());
        }

        @Test
        @DisplayName("a marginal rule does not block - it spawns, just not always")
        void marginalDoesNotBlock() {
            SpawnVerdict verdict = SpawnVerdict.of(
                position(),
                candidate(RuleResult.marginal(SpawnRule.SPAWN_RULES, "light 7", "14% of rolls", null)));

            assertTrue(verdict.canSpawn(), "a mob that spawns 14% of the time still spawns");
        }

        @Test
        @DisplayName("but it is carried as a caveat, so the yes is not read as a promise")
        void marginalBecomesTheCaveat() {
            // The shipped over-promise: 14% of rolls and once-an-hour attempt reach
            // both rendered as a flat "CAN SPAWN HERE - every gate passes", identical
            // to a mob that spawns on every attempt. The tone is right; the answer was
            // missing half of itself.
            RuleResult marginal = RuleResult.marginal(SpawnRule.SPAWN_RULES, "light 7", "14% of rolls", null);
            SpawnVerdict verdict = SpawnVerdict.of(position(), candidate(marginal));

            assertSame(SpawnVerdict.Tone.CAN_SPAWN, verdict.tone(), "a caveat must not change the answer");
            assertSame(marginal, verdict.caveat());
        }

        @Test
        @DisplayName("no caveat when every rule passes outright")
        void noCaveatWhenClean() {
            SpawnVerdict verdict = SpawnVerdict.of(
                position(passing(SpawnRule.ATTEMPT_REACH)), candidate(passing(SpawnRule.PLACEMENT)));

            assertNull(verdict.caveat(), "nothing qualifies this yes, so nothing should be attached to it");
        }

        @Test
        @DisplayName("the place qualifies the answer before the mob does")
        void worldCaveatComesFirst() {
            // Same ordering as the blocker search, and the same reason: vanilla decides
            // at the world layer first, so naming the mob's caveat over the position's
            // would report the second qualification rather than the first.
            RuleResult reach = RuleResult.marginal(SpawnRule.ATTEMPT_REACH, "1/256, 28min", "sparse", null);
            RuleResult light = RuleResult.marginal(SpawnRule.SPAWN_RULES, "light 7", "14% of rolls", null);

            assertSame(reach, SpawnVerdict.of(position(reach), candidate(light)).caveat());
        }
    }

    @Nested
    @DisplayName("when something blocks")
    class Blocked {

        @Test
        @DisplayName("a permanent blocker reports BLOCKED_ALWAYS")
        void standingBlocker() {
            SpawnVerdict verdict = SpawnVerdict.of(
                position(), candidate(blocking(SpawnRule.PLACEMENT)));

            assertSame(SpawnVerdict.Tone.BLOCKED_ALWAYS, verdict.tone());
            assertNotNull(verdict.blocker());
            assertSame(SpawnRule.PLACEMENT, verdict.blocker().rule());
        }

        @Test
        @DisplayName("a temporary blocker reports BLOCKED_NOW")
        void situationalBlocker() {
            SpawnVerdict verdict = SpawnVerdict.of(
                position(blocking(SpawnRule.PLAYER_DISTANCE)), candidate());

            assertSame(SpawnVerdict.Tone.BLOCKED_NOW, verdict.tone());
            assertSame(SpawnRule.PLAYER_DISTANCE, verdict.blocker().rule());
        }

        @Test
        @DisplayName("permanent outranks temporary, whichever comes first in the pipeline")
        void standingOutranksSituational() {
            // The player standing too close is a world rule and therefore earlier, but
            // "the floor is wrong" is the news: one reverts by walking away, the other
            // never does, and they call for opposite actions.
            SpawnVerdict verdict = SpawnVerdict.of(
                position(blocking(SpawnRule.PLAYER_DISTANCE)),
                candidate(blocking(SpawnRule.PLACEMENT)));

            assertSame(SpawnVerdict.Tone.BLOCKED_ALWAYS, verdict.tone());
            assertSame(SpawnRule.PLACEMENT, verdict.blocker().rule());
        }

        @Test
        @DisplayName("a world blocker is found even when the mob's own gates all pass")
        void worldBlockerIsNotIgnored() {
            // The exact shipped bug: mob gates clean, world gate shut, banner said
            // "CAN SPAWN HERE" above a section reading "blocked".
            SpawnVerdict verdict = SpawnVerdict.of(
                position(blocking(SpawnRule.WORLD_BORDER)),
                candidate(passing(SpawnRule.PLACEMENT), passing(SpawnRule.SPAWN_RULES)));

            assertTrue(!verdict.canSpawn(), "a shut world gate blocks every mob");
            assertSame(SpawnRule.WORLD_BORDER, verdict.blocker().rule());
        }

        @Test
        @DisplayName("world rules are searched before mob rules within the same persistence")
        void worldComesFirst() {
            // Vanilla rejects at the world layer first, so naming a mob rule would be
            // reporting the second reason rather than the first.
            SpawnVerdict verdict = SpawnVerdict.of(
                position(blocking(SpawnRule.WORLD_BORDER)),
                candidate(blocking(SpawnRule.PLACEMENT)));

            assertSame(SpawnRule.WORLD_BORDER, verdict.blocker().rule());
        }

        @Test
        @DisplayName("a blocker suppresses the caveat - the blocker is the answer")
        void blockerOutranksCaveat() {
            // Two qualifications compete. "The floor is wrong" is what to act on;
            // "and the spawner rarely reaches this height anyway" is a second sentence
            // that only makes the first harder to find.
            SpawnVerdict verdict = SpawnVerdict.of(
                position(RuleResult.marginal(SpawnRule.ATTEMPT_REACH, "1/256, 28min", "sparse", null)),
                candidate(blocking(SpawnRule.PLACEMENT)));

            assertSame(SpawnRule.PLACEMENT, verdict.blocker().rule());
            assertNull(verdict.caveat());
        }

        @Test
        @DisplayName("a blocked verdict always names its blocker")
        void blockedAlwaysNamesSomething() {
            for (SpawnRule rule : SpawnRule.values()) {
                SpawnVerdict verdict = SpawnVerdict.of(position(), candidate(blocking(rule)));
                assertNotNull(verdict.blocker(),
                    "blocked on " + rule + " but named no blocker; an unexplained no is not an answer");
                assertEquals(rule, verdict.blocker().rule());
            }
        }
    }
}
