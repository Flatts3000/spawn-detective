package com.flatts.spawndetective.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind {@link SpawnRule#ATTEMPT_REACH}, which is the whole claim.
 *
 * <p>Tested here rather than in-world on purpose. The measurement is a probability,
 * and only a test that supplies the column heights directly can assert an exact
 * expected value - a world fixture can confirm the rule fires but never that the
 * number is right. Every gametest also shares one 5x5x5 plot, which can only alter
 * 25 of a chunk's 256 columns, so the case this whole feature exists for (a lone
 * platform in a void world against a full one) is not constructible there at all.
 *
 * <p>The values below come from vanilla's own roll, not from this mod's idea of it:
 * {@code y = randomBetweenInclusive(random, minY, topEmptyY)} over
 * {@code topEmptyY - minY + 1} possibilities, per one of 256 equally likely columns,
 * discarded when it lands on {@code minY} itself.
 */
class SpawnAttemptReachTest {

    /** Overworld floor, so the numbers read like the ones a player would see. */
    private static final int MIN_Y = -64;

    private static int[] columns(int height) {
        int[] tops = new int[SpawnAttemptReach.COLUMNS_PER_CHUNK];
        Arrays.fill(tops, height);
        return tops;
    }

    /** A void chunk: every column empty, which vanilla reports as topEmptyY == minY. */
    private static int[] voidColumns() {
        return columns(MIN_Y);
    }

    @Nested
    @DisplayName("the void case")
    class Void {

        @Test
        @DisplayName("an empty chunk can never anchor an attempt at any height")
        void emptyChunkReachesNothing() {
            // getHeight on an empty column returns minY - 1, so topEmptyY is minY, so
            // the only roll available is minY - which spawnCategoryForChunk discards
            // on "start.getY() >= level.getMinY() + 1". This single fact is why a void
            // world behaves the way it does, and it is vanilla's arithmetic, not ours.
            SpawnAttemptReach reach = SpawnAttemptReach.of(voidColumns(), MIN_Y, 64);

            assertEquals(0, reach.columnsReaching());
            assertEquals(0.0, reach.perAttempt(), 1.0e-12);
            assertTrue(Double.isInfinite(reach.secondsPerAttempt()));
            assertTrue(reach.slow());
        }

        @Test
        @DisplayName("one platform column in a void chunk is 1/256 of a full one")
        void onePlatformColumn() {
            // The reported case: a small dark platform floating in an otherwise empty
            // chunk. It is exactly as eligible as a full floor and 256 times as slow,
            // and no gate in the report can say so.
            int[] tops = voidColumns();
            tops[0] = 65;

            SpawnAttemptReach lone = SpawnAttemptReach.of(tops, MIN_Y, 65);
            SpawnAttemptReach full = SpawnAttemptReach.of(columns(65), MIN_Y, 65);

            assertEquals(1, lone.columnsReaching());
            assertEquals(SpawnAttemptReach.COLUMNS_PER_CHUNK, full.columnsReaching());
            assertEquals(256.0, full.perAttempt() / lone.perAttempt(), 1.0e-9,
                "the two differ by exactly the column count, which is the whole finding");
        }

        @Test
        @DisplayName("a lone platform is reported as slow, a full floor is not")
        void loneVersusFull() {
            int[] tops = voidColumns();
            tops[0] = 65;

            assertTrue(SpawnAttemptReach.of(tops, MIN_Y, 65).slow(),
                "one column of 256 at this depth is minutes between attempts");
            assertFalse(SpawnAttemptReach.of(columns(65), MIN_Y, 65).slow(),
                "a full floor is seconds between attempts and must not be flagged");
        }
    }

    @Nested
    @DisplayName("the arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("a uniform chunk is one over the number of rollable heights")
        void uniformChunk() {
            // topEmptyY 65, minY -64 -> 130 possible values, every column identical.
            SpawnAttemptReach reach = SpawnAttemptReach.of(columns(65), MIN_Y, 65);

            assertEquals(1.0 / 130.0, reach.perAttempt(), 1.0e-12);
        }

        @Test
        @DisplayName("a taller chunk dilutes the same block")
        void tallerColumnsDilute() {
            // The same floor under a mountain is rolled less often than under a plain,
            // because the anchor picks uniformly over the whole column.
            double low = SpawnAttemptReach.of(columns(70), MIN_Y, 65).perAttempt();
            double high = SpawnAttemptReach.of(columns(200), MIN_Y, 65).perAttempt();

            assertTrue(high < low, "a taller column spreads the same roll thinner");
        }

        @Test
        @DisplayName("a height above every column is unreachable from this chunk")
        void aboveEveryColumn() {
            SpawnAttemptReach reach = SpawnAttemptReach.of(columns(65), MIN_Y, 66);

            assertEquals(0, reach.columnsReaching(),
                "nothing in the chunk stands this tall, so no anchor can land here");
        }

        @Test
        @DisplayName("the world floor itself is discarded, not counted")
        void worldFloorIsDiscarded() {
            // spawnCategoryForChunk throws away an anchor at exactly minY. Counting it
            // would report a spawn attempt that vanilla never makes.
            assertEquals(0, SpawnAttemptReach.of(columns(200), MIN_Y, MIN_Y).columnsReaching());
            assertTrue(SpawnAttemptReach.of(columns(200), MIN_Y, MIN_Y + 1).columnsReaching() > 0,
                "one block up is the first height that can be rolled");
        }

        @Test
        @DisplayName("mixed heights sum per column rather than averaging the heights")
        void mixedHeights() {
            // Two columns at very different depths do not behave like 256 columns at
            // their mean: each contributes its own 1/N, and the shallow one dominates.
            int[] tops = voidColumns();
            tops[0] = 65;    // 130 rollable values
            tops[1] = 1_000; // 1065 rollable values, same target height

            SpawnAttemptReach reach = SpawnAttemptReach.of(tops, MIN_Y, 65);

            assertEquals(2, reach.columnsReaching());
            assertEquals((1.0 / 130.0 + 1.0 / 1065.0) / 256.0, reach.perAttempt(), 1.0e-12);
        }
    }

    @Nested
    @DisplayName("the reported row")
    class Reported {

        @Test
        @DisplayName("a healthy chunk passes and carries its measurement")
        void healthyPasses() {
            RuleResult result = SpawnAttemptReach.of(columns(65), MIN_Y, 65).describe();

            assertSame(SpawnRule.ATTEMPT_REACH, result.rule());
            assertSame(Verdict.PASS, result.verdict());
            assertTrue(result.value().contains("256/256"), "got: " + result.value());
        }

        @Test
        @DisplayName("a sparse chunk is marginal, never a failure")
        void sparseIsMarginalNotFailure() {
            // The rule must never become a headline. A spot the spawner rarely reaches
            // is slow, not shut, and "CANNOT SPAWN HERE" about a block that can is the
            // exact class of confident wrongness this mod exists to avoid.
            int[] tops = voidColumns();
            tops[0] = 65;

            RuleResult result = SpawnAttemptReach.of(tops, MIN_Y, 65).describe();

            assertSame(Verdict.MARGINAL, result.verdict());
            assertFalse(result.verdict().blocks(), "a slow spot is not a blocked spot");
            assertTrue(result.verdict().permits());
        }

        @Test
        @DisplayName("an unreachable height says so without claiming a rejection")
        void unreachableIsStillNotAFailure() {
            // A neighbouring chunk's pack walk can still wander in, so this chunk
            // having no route is not proof that nothing ever spawns here.
            RuleResult result = SpawnAttemptReach.of(voidColumns(), MIN_Y, 64).describe();

            assertSame(Verdict.MARGINAL, result.verdict());
            assertTrue(result.detail().contains("neighbouring"),
                "the limit of the claim has to travel with it, got: " + result.detail());
        }

        @Test
        @DisplayName("every row names a height, since the answer is specific to one")
        void rowNamesTheHeight() {
            assertTrue(SpawnAttemptReach.of(columns(65), MIN_Y, 65).describe().detail().contains("Y=65"));
            assertTrue(SpawnAttemptReach.of(voidColumns(), MIN_Y, 65).describe().detail().contains("Y=65"));
        }
    }

    @Nested
    @DisplayName("invariants that must hold for any world")
    class Invariants {

        /**
         * Enough shapes to cover the ones that break arithmetic: empty chunks, ragged
         * terrain, a world floor at either sign, extreme build heights, and targets
         * above, below and inside the surface.
         */
        private static int[][] shapes() {
            int[] ragged = new int[SpawnAttemptReach.COLUMNS_PER_CHUNK];
            for (int i = 0; i < ragged.length; i++) {
                ragged[i] = -60 + (i * 7) % 380;
            }
            int[] oneTall = columns(MIN_Y);
            oneTall[128] = 319;

            return new int[][] {
                new int[0], columns(MIN_Y), columns(MIN_Y + 1), columns(0),
                columns(319), columns(Integer.MAX_VALUE / 4), ragged, oneTall,
            };
        }

        @Test
        @DisplayName("the reported row is never a rejection, whatever the world looks like")
        void neverBlocks() {
            // The rule lives in the world list, so one FAIL anywhere in this space
            // becomes "CANNOT SPAWN HERE" for every mob at the position. A rate is not
            // a gate, and no input may turn it into one.
            for (int[] tops : shapes()) {
                for (int minY : new int[] {-64, 0, -2032}) {
                    for (int target : new int[] {minY, minY + 1, 0, 64, 319, minY - 100, Integer.MAX_VALUE / 8}) {
                        RuleResult row = SpawnAttemptReach.of(tops, minY, target).describe();

                        assertFalse(row.verdict().blocks(),
                            "blocked at minY=" + minY + " target=" + target + ": " + row.detail());
                        assertTrue(row.verdict().permits(), "a rate must always still permit");
                        assertFalse(row.value().isBlank(), "no measurement reported");
                        assertFalse(row.detail().isBlank(), "no reason reported");
                    }
                }
            }
        }

        @Test
        @DisplayName("the probability is always a real number in [0, 1]")
        void probabilityStaysSane() {
            // It is divided by a column height that can be enormous and summed over
            // 256 columns; an overflow or a divide-by-zero here would surface as a
            // confident nonsense figure rather than as an error.
            for (int[] tops : shapes()) {
                for (int minY : new int[] {-64, 0, -2032}) {
                    for (int target : new int[] {minY, minY + 1, 64, 319}) {
                        SpawnAttemptReach reach = SpawnAttemptReach.of(tops, minY, target);

                        assertTrue(Double.isFinite(reach.perAttempt()),
                            "not a finite probability: " + reach.perAttempt());
                        assertTrue(reach.perAttempt() >= 0.0 && reach.perAttempt() <= 1.0,
                            "outside [0, 1]: " + reach.perAttempt());
                        assertTrue(reach.columnsReaching() >= 0
                            && reach.columnsReaching() <= reach.columnsTotal(),
                            "column count outside the chunk: " + reach.columnsReaching());
                    }
                }
            }
        }

        @Test
        @DisplayName("no reachable column means no probability, and vice versa")
        void countAndProbabilityAgree() {
            // The two are read separately - the count in the table column, the rate in
            // the sentence - so a report showing "0 of 256" beside a non-zero rate
            // would be contradicting itself in one row.
            for (int[] tops : shapes()) {
                for (int target : new int[] {-64, -63, 0, 64, 320}) {
                    SpawnAttemptReach reach = SpawnAttemptReach.of(tops, MIN_Y, target);

                    assertEquals(reach.columnsReaching() == 0, reach.perAttempt() == 0.0,
                        "count and rate disagree at target=" + target);
                }
            }
        }

        @Test
        @DisplayName("adding surface at a height never makes it harder to reach")
        void moreSurfaceIsNeverWorse() {
            // Monotonicity is the claim a player acts on: the remedy says enlarge the
            // platform, so growing it must never reduce the reported reach.
            int[] tops = columns(MIN_Y);
            double previous = SpawnAttemptReach.of(tops, MIN_Y, 65).perAttempt();

            for (int i = 0; i < SpawnAttemptReach.COLUMNS_PER_CHUNK; i++) {
                tops[i] = 65;
                double now = SpawnAttemptReach.of(tops, MIN_Y, 65).perAttempt();
                assertTrue(now >= previous, "reach fell after adding a column at index " + i);
                previous = now;
            }
        }
    }
}
