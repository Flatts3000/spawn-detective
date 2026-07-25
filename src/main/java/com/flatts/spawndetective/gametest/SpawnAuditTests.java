package com.flatts.spawndetective.gametest;

import com.flatts.spawndetective.SpawnDetective;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.RuleResult;
import com.flatts.spawndetective.audit.PositionReport;
import com.flatts.spawndetective.audit.SpawnAttemptReach;
import com.flatts.spawndetective.audit.SpawnAuditor;
import com.flatts.spawndetective.audit.SpawnVerdict;
import com.flatts.spawndetective.audit.SpawnRule;
import com.flatts.spawndetective.audit.Verdict;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Proof that the auditor names the right cause.
 *
 * <p>Each test constructs a position whose only defect is known in advance, then
 * asserts both the blocking rule and the human-readable cause. Asserting the
 * detail text matters as much as the rule: "PLACEMENT failed" is not an answer,
 * "the floor is Air" is, and only the second is what the mod promises.
 */
@GameTestHolder(SpawnDetective.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpawnAuditTests {

    private SpawnAuditTests() {
    }

    /**
     * The measurement must follow the world, not a snapshot of it.
     *
     * <p>{@code SpawnAttemptReachTest} pins the arithmetic exactly; what it cannot
     * show is that the arithmetic is fed the world's actual surface heights. Reading
     * the wrong heightmap, reading a stale one, or masking the coordinates wrongly
     * all produce a plausible number and a pure test would pass on every one of them.
     *
     * <p>Comparing a reading before a build against one after it does <b>not</b> work
     * here, and the first draft of this test learned that the hard way: the batch runs
     * plots side by side in a shared chunk, so neighbours raise and clear their own
     * columns between the two readings. It measured 48 columns, built one pillar, and
     * measured 18. The engine was right and the test was badly isolated.
     *
     * <p>What is safe is two readings taken back to back in one tick - GameTests run
     * on the server thread, so nothing can move between them. A column reaching height
     * Y also reaches every height below it, so the count can only fall as Y rises, and
     * one column crosses out of it at exactly {@code topEmptyY + 1}. Reading that
     * boundary off the live heightmap is the whole point: {@code topEmptyY} is
     * {@code getHeight(WORLD_SURFACE) + 1}, and that {@code + 1} is what decides
     * whether the block a player is standing on is reachable at all.
     *
     * <p>The boundary is taken from the heightmap rather than from the pillar's top
     * block, which is the second thing this test learned. The plot does not own the
     * whole column - the framework has its own blocks above it - so the built pillar
     * is not necessarily the highest thing there, and assuming it was put the column
     * on the same side of both readings.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 20)
    public static void attempt_reach_tracks_the_heightmap(GameTestHelper helper) {
        BlockPos column = new BlockPos(1, 1, 1);
        for (int dy = 0; dy <= 3; dy++) {
            helper.setBlock(column.above(dy), Blocks.STONE);
        }
        BlockPos top = helper.absolutePos(column.above(3));

        helper.runAfterDelay(5L, () -> {
            ChunkPos chunkPos = new ChunkPos(top);
            LevelChunk chunk = helper.getLevel().getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) {
                throw fail(helper, "the plot's own chunk should be loaded");
            }

            // The block just placed has to be in the heightmap the measurement reads,
            // or the reading is of a stale world.
            int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, top.getX(), top.getZ());
            if (surface < top.getY()) {
                throw fail(helper, "placed stone at y=" + top.getY()
                    + " but the surface heightmap still reads " + surface);
            }

            int topEmptyY = surface + 1;
            SpawnAttemptReach reaching = SpawnAttemptReach.measure(
                helper.getLevel(), new BlockPos(top.getX(), topEmptyY, top.getZ()));
            SpawnAttemptReach beyond = SpawnAttemptReach.measure(
                helper.getLevel(), new BlockPos(top.getX(), topEmptyY + 1, top.getZ()));
            if (reaching == null || beyond == null) {
                throw fail(helper, "the chunk stopped being readable mid-test");
            }

            if (reaching.columnsReaching() < beyond.columnsReaching() + 1) {
                throw fail(helper, "this column tops out at " + topEmptyY
                    + ", so it must be counted there and not one above: "
                    + reaching.columnsReaching() + " then " + beyond.columnsReaching());
            }
            if (!(reaching.perAttempt() > beyond.perAttempt())) {
                throw fail(helper, "a height with more surface at it must not read as rarer: "
                    + reaching.perAttempt() + " vs " + beyond.perAttempt());
            }

            // The reported row has to carry the same number the measurement holds -
            // two readings of one chunk disagreeing is the failure this whole feature
            // was added to stop.
            RuleResult row = SpawnAuditor
                .auditPosition(helper.getLevel(), new BlockPos(top.getX(), topEmptyY, top.getZ()))
                .world().stream()
                .filter(r -> r.rule() == SpawnRule.ATTEMPT_REACH)
                .findFirst()
                .orElseThrow(() -> fail(helper, "no attempt-reach row after the build"));
            if (!row.value().startsWith(reaching.columnsReaching() + "/")) {
                throw fail(helper, "the row reports " + row.value() + " but the measurement says "
                    + reaching.columnsReaching() + " columns");
            }
            helper.succeed();
        });
    }

    /**
     * A cap must never be blamed for something that is not the cap.
     *
     * <p>Regression test for a bug this suite caught the moment the caps reached the
     * single-mob path. The global cap is {@code max * spawnableChunks / 289}, and
     * with nobody close enough to make any chunk spawnable that collapses to zero -
     * at which point vanilla's own {@code count < cap} refuses and the row read
     * <b>"cap full: 0 of 0, over 0 spawnable chunks"</b>. True of the arithmetic,
     * and a lie about the world: it told a player to go kill mobs that do not exist,
     * and as the first rule in the walk it became the headline for every mob.
     *
     * <p>The real finding is that no player is near, which PLAYER_IN_SPAWN_RANGE
     * already reports. Same shape as a category declaring no per-chunk maximum, and
     * handled the same way - skipped, with the reason named.
     *
     * <p>A GameTest server has no players, so this is the live case, not a mock.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void caps_degrade_rather_than_blame(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), pos, EntityType.ZOMBIE);

        for (RuleResult result : candidate.rules()) {
            if (result.rule() != SpawnRule.CATEGORY_GLOBAL_CAP && result.rule() != SpawnRule.CATEGORY_LOCAL_CAP) {
                continue;
            }
            if (result.verdict() == Verdict.FAIL) {
                throw fail(helper, result.rule() + " blamed the cap with no player in the dimension: "
                    + result.detail());
            }
            if (result.detail().isBlank()) {
                throw fail(helper, result.rule() + " gave no reason for its verdict");
            }
        }

        // And the verdict must not reach for a cap either. This is what actually
        // broke: three unrelated tests started reporting the cap as the cause of a
        // missing floor and of a lit room.
        SpawnVerdict verdict = SpawnVerdict.of(
            SpawnAuditor.auditPosition(helper.getLevel(), pos), candidate);
        if (verdict.blocker() != null
            && (verdict.blocker().rule() == SpawnRule.CATEGORY_GLOBAL_CAP
                || verdict.blocker().rule() == SpawnRule.CATEGORY_LOCAL_CAP)) {
            throw fail(helper, "the headline blamed a cap that is not the cause: "
                + verdict.blocker().detail());
        }
        helper.succeed();
    }

    /**
     * The attempt-reach row must come from the real chunk and must never block.
     *
     * <p>The arithmetic itself is pinned in {@code SpawnAttemptReachTest}, where an
     * exact probability can be asserted. What only a live level can show is that the
     * heightmap read works at all, and that a rule which is informational by design
     * has not quietly acquired the power to say no - it sits in the world list, so a
     * FAIL here would become the headline for every mob at the position.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void attempt_reach_reads_the_live_chunk(GameTestHelper helper) {
        PositionReport report = SpawnAuditor.auditPosition(
            helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)));

        RuleResult reach = report.world().stream()
            .filter(r -> r.rule() == SpawnRule.ATTEMPT_REACH)
            .findFirst()
            .orElseThrow(() -> fail(helper, "no ATTEMPT_REACH row in the position report"));

        if (reach.verdict() == Verdict.FAIL) {
            throw fail(helper, "attempt reach must never fail - a slow spot is not a shut one: "
                + reach.detail());
        }
        if (reach.verdict() == Verdict.UNKNOWN) {
            throw fail(helper, "the probed chunk is loaded, so its heights must be readable: "
                + reach.detail());
        }
        // "/256" is the chunk's column count reaching the column, i.e. proof the
        // measurement came from a heightmap rather than from a default.
        if (!reach.value().contains("/256")) {
            throw fail(helper, "expected a column count out of 256, got: " + reach.value());
        }
        helper.succeed();
    }

    /**
     * The mob caps must appear on the single-mob path.
     *
     * <p>They did not, and that is half of what issue #9 was really about. The screen,
     * the Jade tooltip and {@code /spawndetective for} all resolve through
     * {@code auditType}, which walked only the per-type gates - so a player sitting
     * against a full mob cap read a report with every line green and no cap row on it
     * anywhere. A gate omitted from the report is as wrong as a gate reported wrongly,
     * and harder to notice.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void caps_reach_the_single_mob_audit(GameTestHelper helper) {
        AuditReport.Candidate candidate = SpawnAuditor.auditType(
            helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)), EntityType.ZOMBIE);

        Set<SpawnRule> walked = EnumSet.noneOf(SpawnRule.class);
        for (RuleResult result : candidate.rules()) {
            if (!walked.add(result.rule())) {
                throw fail(helper, "rule " + result.rule() + " evaluated twice for one mob");
            }
        }

        for (SpawnRule cap : List.of(SpawnRule.CATEGORY_GLOBAL_CAP, SpawnRule.CATEGORY_LOCAL_CAP)) {
            if (!walked.contains(cap)) {
                throw fail(helper, cap + " is missing from auditType, so it never reaches the screen, "
                    + "Jade or /spawndetective for");
            }
        }

        // Order is the contract, not a detail: the caps are chunk-layer rules and
        // vanilla consults them before any per-type gate, so blocker() has to find
        // them first or the report names the second cause.
        if (candidate.rules().getFirst().rule() != SpawnRule.CATEGORY_GLOBAL_CAP) {
            throw fail(helper, "the caps must lead the walk, but it starts with "
                + candidate.rules().getFirst().rule());
        }
        helper.succeed();
    }

    /**
     * The world section must evaluate every world/chunk/position rule exactly once.
     * A rule silently dropped from the walk is the worst possible failure mode -
     * the report would look complete while omitting the actual cause.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void audit_covers_every_world_rule(GameTestHelper helper) {
        AuditReport report = SpawnAuditor.audit(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)));

        Set<SpawnRule> expected = EnumSet.of(
            SpawnRule.GAMERULE_MOB_SPAWNING,
            SpawnRule.DIFFICULTY,
            SpawnRule.WORLD_BORDER,
            SpawnRule.CHUNK_ENTITY_TICKING,
            SpawnRule.PLAYER_IN_SPAWN_RANGE,
            SpawnRule.ATTEMPT_REACH,
            SpawnRule.ANCHOR_NOT_CONDUCTOR,
            SpawnRule.PLAYER_DISTANCE,
            SpawnRule.WORLD_SPAWN_DISTANCE);

        Set<SpawnRule> seen = EnumSet.noneOf(SpawnRule.class);
        for (RuleResult result : report.world()) {
            if (!seen.add(result.rule())) {
                throw fail(helper, "rule " + result.rule() + " evaluated twice");
            }
        }
        if (!seen.equals(expected)) {
            throw fail(helper, "world rule coverage drifted: expected " + expected + " but walked " + seen);
        }
        helper.succeed();
    }

    /** A position buried in stone can only fail on placement, and must say the block is solid. */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void enclosed_position_blocks_on_placement(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        for (int dx = 0; dx <= 2; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = 0; dz <= 2; dz++) {
                    helper.setBlock(relative.offset(dx - 1, dy - 1, dz - 1), Blocks.STONE);
                }
            }
        }

        RuleResult blocker = blockerFor(helper, relative, EntityType.ZOMBIE);
        assertRule(helper, blocker, SpawnRule.PLACEMENT);
        assertDetailContains(helper, blocker, "full solid block");
        helper.succeed();
    }

    /** Air under the spawn position is a missing floor, and must be named as such. */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void no_floor_blocks_on_placement(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 2, 1);
        helper.setBlock(relative, Blocks.AIR);
        helper.setBlock(relative.below(), Blocks.AIR);

        RuleResult blocker = blockerFor(helper, relative, EntityType.ZOMBIE);
        assertRule(helper, blocker, SpawnRule.PLACEMENT);
        assertDetailContains(helper, blocker, "floor");
        helper.succeed();
    }

    /**
     * A sealed dark chamber with a valid stone floor, lit by one glowstone block.
     * Light is then the only defect, so the spawn-reason differential must attribute
     * the failure to light rather than to the floor. This is the headline case the
     * mod exists for.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void bright_chamber_attributes_light(GameTestHelper helper) {
        BlockPos spawn = carveChamber(helper);
        // Glowstone in the wall beside the spawn block, never under it: putting it in
        // the floor would also make the floor invalid and the test would pass for the
        // wrong reason.
        helper.setBlock(spawn.offset(1, 0, 0), Blocks.GLOWSTONE);

        helper.runAfterDelay(LIGHT_SETTLE_TICKS, () -> {
            RuleResult blocker = blockerFor(helper, spawn, EntityType.ZOMBIE);
            assertRule(helper, blocker, SpawnRule.SPAWN_RULES);
            // Both halves matter: the cause must be light, and the measured value
            // must reach the column - "light" with no number is not an answer.
            assertDetailContains(helper, blocker, "light");
            assertDetailContains(helper, blocker, "block light 14");
            if (!blocker.value().contains("light")) {
                throw fail(helper, "the column value should carry the light measurement, got: "
                    + blocker.value());
            }
            if (blocker.effectiveRemedy() == null
                || !blocker.effectiveRemedy().toLowerCase().contains("light source")) {
                throw fail(helper, "expected a remedy naming the light source, got: "
                    + blocker.effectiveRemedy());
            }
            helper.succeed();
        });
    }

    /** The control for the case above: the same sealed chamber, unlit, must not fail on light. */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void dark_chamber_passes_spawn_rules(GameTestHelper helper) {
        BlockPos spawn = carveChamber(helper);

        helper.runAfterDelay(LIGHT_SETTLE_TICKS, () -> {
            AuditReport.Candidate candidate = SpawnAuditor.auditType(
                helper.getLevel(), helper.absolutePos(spawn), EntityType.ZOMBIE);

            RuleResult spawnRules = candidate.rules().stream()
                .filter(r -> r.rule() == SpawnRule.SPAWN_RULES)
                .findFirst()
                .orElseThrow(() -> fail(helper, "no SPAWN_RULES result"));

            if (spawnRules.verdict() == Verdict.FAIL) {
                throw fail(helper, "a sealed dark chamber should not fail the spawn rules, got: "
                    + spawnRules.detail());
            }
            // A cave has no sky by definition, and almost no monster requires sky.
            // Blaming sky access for a cave was a real misdiagnosis; see
            // attributionIsStable for the noise that caused it.
            if (spawnRules.detail().toLowerCase().contains("sky access")) {
                throw fail(helper, "a dark cave must not be blamed on sky access: " + spawnRules.detail());
            }
            helper.succeed();
        });
    }

    /**
     * The same position must produce the same verdict twice running.
     *
     * <p>Regression test for a misdiagnosis seen live: a dark cave at Y=39 reported
     * "no sky access" as the cause for all eight monsters. The predicate rolls the
     * RNG, and the attribution compares a NATURAL sample set against SPAWNER and
     * TRIAL_SPAWNER sets. Drawing fresh randomness for each set meant luck alone
     * could make one pass and another fail, and that difference was then read as
     * causal. All three now draw from one seed, so the only thing varying between
     * them is the spawn reason - a controlled experiment rather than three
     * independent coin flips.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void attribution_is_stable(GameTestHelper helper) {
        BlockPos spawn = carveChamber(helper);
        helper.setBlock(spawn.offset(1, 0, 0), Blocks.GLOWSTONE);

        helper.runAfterDelay(LIGHT_SETTLE_TICKS, () -> {
            BlockPos absolute = helper.absolutePos(spawn);
            String first = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                AuditReport.Candidate candidate =
                    SpawnAuditor.auditType(helper.getLevel(), absolute, EntityType.ZOMBIE);
                String verdict = candidate.blocker()
                    .map(b -> b.rule() + "/" + b.summary())
                    .orElse("viable");
                if (first == null) {
                    first = verdict;
                } else if (!first.equals(verdict)) {
                    throw fail(helper, "attribution is unstable across runs: '" + first
                        + "' then '" + verdict + "'");
                }
            }
            helper.succeed();
        });
    }

    /**
     * When a permanent reason and a temporary one both apply, the verdict must name
     * the permanent one.
     *
     * <p>Regression test with a specific origin. The first screen build reported
     * "NOTHING CAN SPAWN HERE - at least 24 blocks from the nearest player, 1.3
     * blocks" for a lit block. That is true and worthless: you are always within 24
     * blocks of the block you are pointing at, so the tool's primary gesture always
     * produced the same non-answer while the real cause (light) sat unmentioned. The
     * anchor gesture removed the cause; this keeps the ordering that made it wrong.
     *
     * <p>This chamber has both a standing blocker (light) and a situational one
     * (player distance - a headless GameTest server has no player at all). The
     * verdict must pick light.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void headline_prefers_standing_cause(GameTestHelper helper) {
        BlockPos spawn = carveChamber(helper);
        helper.setBlock(spawn.offset(1, 0, 0), Blocks.GLOWSTONE);

        helper.runAfterDelay(LIGHT_SETTLE_TICKS, () -> {
            BlockPos absolute = helper.absolutePos(spawn);
            PositionReport position = SpawnAuditor.auditPosition(helper.getLevel(), absolute);
            AuditReport.Candidate candidate =
                SpawnAuditor.auditType(helper.getLevel(), absolute, EntityType.ZOMBIE);
            SpawnVerdict verdict = SpawnVerdict.of(position, candidate);

            if (verdict.tone() != SpawnVerdict.Tone.BLOCKED_ALWAYS) {
                throw fail(helper, "expected a permanent verdict, got " + verdict.tone()
                    + " on " + verdict.blocker().rule());
            }
            String named = (verdict.blocker().rule().title() + " " + verdict.blocker().detail()).toLowerCase();
            if (named.contains("player")) {
                throw fail(helper, "blamed player proximity over the standing cause: " + named);
            }
            if (!named.contains("light") && !named.contains("spawn rules")) {
                throw fail(helper, "did not name the standing cause: " + named);
            }
            helper.succeed();
        });
    }



    /**
     * No mob may be blamed on sky access unless its rules actually consult the sky.
     *
     * <p>Chasing a live misdiagnosis: a dark cave reported "slime +6 more - needs
     * sky". Slime's rules never mention sky - underground it wants a slime chunk
     * below Y 40, and on the surface a swamp between Y 50 and 70 - so sky cannot be
     * the cause for it under any circumstances. This walks every monster the
     * registry offers through a sealed sky-less chamber and fails on any sky claim,
     * since a chamber with a valid floor leaves nothing for sky to explain.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void no_sky_blame_without_sky_rule(GameTestHelper helper) {
        BlockPos spawn = carveChamber(helper);

        helper.runAfterDelay(LIGHT_SETTLE_TICKS, () -> {
            BlockPos absolute = helper.absolutePos(spawn);
            List<String> wrong = new ArrayList<>();

            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                if (type.getCategory() != MobCategory.MONSTER) {
                    continue;
                }
                AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), absolute, type);
                // The column value is the claim. Prose may still offer sky as one
                // possibility among several - that is a lead, not an assertion, and
                // leads are allowed precisely because the cause cannot be narrowed.
                candidate.rules().stream()
                    .filter(r -> r.rule() == SpawnRule.SPAWN_RULES)
                    .filter(r -> r.value().toLowerCase().contains("sky"))
                    .findFirst()
                    .ifPresent(r -> wrong.add(
                        BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath() + " -> " + r.value()));
            }

            if (!wrong.isEmpty()) {
                throw fail(helper, "sky blamed in a sealed chamber for: "
                    + String.join(" | ", wrong.subList(0, Math.min(3, wrong.size()))));
            }
            helper.succeed();
        });
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Ticks to wait before reading light. The light engine settles asynchronously,
     * so asserting on the same tick as the block edits reads stale values - the
     * first draft of these tests did exactly that and measured sky light 15 inside
     * a sealed room.
     */
    private static final int LIGHT_SETTLE_TICKS = 10;

    /**
     * Fill the 5x5x5 plot with stone and carve a two-block-tall pocket in the middle.
     * Returns the relative position a mob would stand in: valid stone floor, clear
     * headroom, no sky access. Everything except light is correct by construction.
     */
    private static BlockPos carveChamber(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        BlockPos spawn = new BlockPos(2, 2, 2);
        helper.setBlock(spawn, Blocks.AIR);
        helper.setBlock(spawn.above(), Blocks.AIR);
        return spawn;
    }

    /** GameTest assertions take a plain message here; this keeps call sites readable. */
    private static GameTestAssertException fail(GameTestHelper helper, String message) {
        return new GameTestAssertException(message);
    }

    private static RuleResult blockerFor(GameTestHelper helper, BlockPos relative, EntityType<?> type) {
        AuditReport.Candidate candidate = SpawnAuditor.auditType(
            helper.getLevel(), helper.absolutePos(relative), type);
        Optional<RuleResult> blocker = candidate.blocker();
        if (blocker.isEmpty()) {
            throw fail(helper, "expected " + type.getDescriptionId() + " to be blocked here, but every rule passed");
        }
        return blocker.get();
    }

    private static void assertRule(GameTestHelper helper, RuleResult result, SpawnRule expected) {
        if (result.rule() != expected) {
            throw fail(helper, "expected " + expected + " to be the blocker, got " + result.rule() + ": " + result.detail());
        }
    }

    private static void assertDetailContains(GameTestHelper helper, RuleResult result, String fragment) {
        if (!result.detail().toLowerCase().contains(fragment.toLowerCase())) {
            throw fail(helper, "expected the reason to mention '" + fragment + "', got: " + result.detail());
        }
    }
}
