package com.flatts.spawndetective.audit;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * How often a spawn attempt in this chunk anchors at this Y at all.
 *
 * <p>This is the one thing a per-block verdict cannot express, and the reason a
 * fully-eligible block in a void world can sit empty for hours. Every gate can pass
 * and the mob still never arrive, because the natural spawner's random sampler
 * almost never picks that height.
 *
 * <p>It is not an estimate. {@code NaturalSpawner.spawnCategoryForChunk} picks its
 * anchor with:
 *
 * <pre>
 *   BlockPos start = getRandomPosWithin(level, chunk);
 *   if (start.getY() &gt;= level.getMinBuildHeight() + 1) { spawnCategoryForPosition(...); }
 *
 *   int x = chunkPos.getMinBlockX() + random.nextInt(16);
 *   int z = chunkPos.getMinBlockZ() + random.nextInt(16);
 *   int topEmptyY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
 *   int y = Mth.randomBetweenInclusive(random, level.getMinBuildHeight(), topEmptyY);
 * </pre>
 *
 * and inside {@code spawnCategoryForPosition} the three pack groups walk x and z
 * only - {@code yStart} never moves. So a block is reachable by an attempt anchored
 * in its own chunk exactly when that uniform roll lands on its Y, which is plain
 * arithmetic over the chunk's live {@code WORLD_SURFACE} heightmap:
 *
 * <pre>
 *   per column:  N = topEmptyY - minY + 1
 *                P = 1/N   when minY + 1 &lt;= targetY &lt;= topEmptyY, else 0
 *   overall:     mean of P over the chunk's 256 columns
 * </pre>
 *
 * <p>An empty column reports {@code getHeight == minY - 1}, so {@code topEmptyY}
 * is {@code minY}, so the only possible roll is {@code minY} - which the
 * {@code >= minY + 1} gate discards. <b>A column with nothing in it produces no
 * spawn attempt at all.</b> That single fact is the whole void-world mechanic, and
 * it falls straight out of vanilla's own arithmetic rather than out of a guess.
 *
 * <p><b>What this does not claim.</b> It is not the farm's spawn rate. The pack
 * walk dilutes it further in x and z, an anchor in a neighbouring chunk can wander
 * in, and every later gate still applies. It reports one measured quantity - how
 * often an attempt in <i>this</i> chunk lands on <i>this</i> Y - and nothing more.
 */
public record SpawnAttemptReach(int targetY, int columnsReaching, int columnsTotal, double perAttempt) {

    /** Columns the anchor roll picks from: {@code nextInt(16)} in each of x and z. */
    public static final int COLUMNS_PER_CHUNK = 256;

    /**
     * Anchor rolls per second, for the cadence quoted in the report.
     *
     * <p>{@code ServerChunkCache.tickSpawningChunk} runs {@code spawnForChunk} once
     * per eligible chunk per tick, and that makes one anchor roll per category - so
     * 20 a second at 20 TPS. Persistent categories are the exception:
     * {@code getFilteredSpawningCategories} only admits them when
     * {@code gameTime % 400 == 0}, so CREATURE rolls once every 400 ticks. The
     * report quotes the monster cadence and says so, rather than quoting a figure
     * that is right for one category and wrong for another.
     */
    private static final double ATTEMPTS_PER_SECOND = 20.0;

    /**
     * Above this mean wait, the reading is reported as {@link Verdict#MARGINAL}.
     *
     * <p>A threshold on a presentation decision, not on a spawn rule - nothing here
     * ever rejects a spawn. A minute between <i>attempts</i> (not spawns, attempts,
     * before any gate is even consulted) is the point where the geometry is the
     * limiter rather than anything the rest of the report is measuring, and where a
     * bare green verdict starts over-promising.
     */
    private static final double SLOW_ATTEMPT_SECONDS = 60.0;

    /**
     * The measurement, from the chunk's surface heights.
     *
     * <p>Pure on purpose. The arithmetic is the whole claim, and a world fixture
     * cannot assert an exact expected probability the way a test over an array can.
     *
     * @param topEmptyY one {@code getHeight(WORLD_SURFACE, x, z) + 1} per column
     * @param minY      {@code level.getMinBuildHeight()}
     * @param targetY   the Y being asked about
     */
    public static SpawnAttemptReach of(int[] topEmptyY, int minY, int targetY) {
        int reaching = 0;
        double total = 0.0;
        for (int top : topEmptyY) {
            // The discard gate in spawnCategoryForChunk: an anchor at minY itself is
            // thrown away, so a column can only ever contribute from minY + 1 up.
            if (targetY < minY + 1 || targetY > top) {
                continue;
            }
            reaching++;
            total += 1.0 / (top - minY + 1);
        }
        return new SpawnAttemptReach(targetY, reaching, topEmptyY.length,
            topEmptyY.length == 0 ? 0.0 : total / topEmptyY.length);
    }

    /**
     * The measurement for a live position, or null when the chunk is not loaded.
     *
     * <p>{@code getChunkNow} rather than {@code getChunk}: this runs on every probe
     * and every Jade look, and a diagnostic has no business forcing a chunk load to
     * answer a question about somewhere else.
     *
     * <p>Public so an in-world test can compare two readings of the same chunk across
     * a change to it. {@link #of} proves the arithmetic, but only a live level proves
     * that the arithmetic is being fed the world's actual surface heights - a read of
     * the wrong heightmap, or of a stale one, is invisible to a pure test.
     */
    public static @Nullable SpawnAttemptReach measure(ServerLevel level, BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return null;
        }

        int[] topEmptyY = new int[COLUMNS_PER_CHUNK];
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                topEmptyY[dx * 16 + dz] =
                    chunk.getHeight(Heightmap.Types.WORLD_SURFACE, baseX + dx, baseZ + dz) + 1;
            }
        }
        return of(topEmptyY, level.getMinBuildHeight(), pos.getY());
    }

    /** Read the probed chunk and report the rule. A miss is UNKNOWN, never a guess. */
    public static RuleResult audit(ServerLevel level, BlockPos pos) {
        try {
            SpawnAttemptReach reach = measure(level, pos);
            return reach == null
                ? RuleResult.unknown(SpawnRule.ATTEMPT_REACH, "chunk " + new ChunkPos(pos)
                    + " is not loaded here, so its surface heights cannot be read")
                : reach.describe();
        } catch (Throwable t) {
            return RuleResult.unknown(SpawnRule.ATTEMPT_REACH,
                "could not read this chunk's surface heights: " + t);
        }
    }

    /** Mean seconds between attempts landing on this Y, or infinite when none can. */
    public double secondsPerAttempt() {
        return this.perAttempt <= 0.0 ? Double.POSITIVE_INFINITY : 1.0 / (this.perAttempt * ATTEMPTS_PER_SECOND);
    }

    /** True when the geometry, rather than any gate, is what a player is waiting on. */
    public boolean slow() {
        return this.secondsPerAttempt() > SLOW_ATTEMPT_SECONDS;
    }

    /**
     * The rule row: PASS or MARGINAL, and never FAIL. A slow spot is not a shut one.
     *
     * <p>The height comes from the record rather than from a parameter. It is quoted
     * in the prose beside a count that was computed for it, and taking it separately
     * would let the two disagree - a report reading "columns reach Y=70" over a
     * measurement made for Y=65, which is exactly the kind of confidently wrong
     * sentence this mod exists to not produce.
     *
     * <p>The detail is kept inside two banner lines. A caveat is shown in place of
     * "every gate passes", so one that ellipses mid-sentence would replace a complete
     * wrong answer with an incomplete one.
     */
    public RuleResult describe() {
        String value = this.columnsReaching + "/" + this.columnsTotal + ", " + terseInterval();

        if (this.columnsReaching == 0) {
            return RuleResult.marginal(SpawnRule.ATTEMPT_REACH, value,
                "no column in this chunk reaches Y=" + this.targetY
                    + "; only a pack wandering in from a neighbouring chunk could reach it",
                "Nothing in this chunk stands this tall, so a mob would have to walk in from elsewhere.");
        }

        // Kept under two banner lines (~112 characters at this panel width), with the
        // cadence qualifier ahead of the figure rather than trailing it. Clipping
        // "for monsters" off the end would leave a rate that reads as true of every
        // category, and it is right for one.
        String measured = this.columnsReaching + " of " + this.columnsTotal
            + " columns in this chunk reach Y=" + this.targetY
            + ", so a monster attempt lands about " + proseInterval();

        return this.slow()
            ? RuleResult.marginal(SpawnRule.ATTEMPT_REACH, value, "only " + measured, null)
            : RuleResult.pass(SpawnRule.ATTEMPT_REACH, value, measured);
    }

    /** For the table column, which is narrow and right-aligned. */
    private String terseInterval() {
        double seconds = this.secondsPerAttempt();
        if (Double.isInfinite(seconds)) {
            return "never";
        }
        if (seconds < 90.0) {
            return Math.round(seconds) + "s";
        }
        if (seconds < 5400.0) {
            return Math.round(seconds / 60.0) + "min";
        }
        return Math.round(seconds / 3600.0) + "h";
    }

    /** For the sentence, where there is room to say it properly. */
    private String proseInterval() {
        double seconds = this.secondsPerAttempt();
        if (Double.isInfinite(seconds)) {
            return "never";
        }
        if (seconds < 90.0) {
            return String.format(Locale.ROOT, "every %.0f seconds", seconds);
        }
        if (seconds < 5400.0) {
            return String.format(Locale.ROOT, "every %.0f minutes", seconds / 60.0);
        }
        return String.format(Locale.ROOT, "every %.1f hours", seconds / 3600.0);
    }
}
