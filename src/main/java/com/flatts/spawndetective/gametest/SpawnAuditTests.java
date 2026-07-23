package com.flatts.spawndetective.gametest;

import com.flatts.spawndetective.SpawnDetective;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.RuleResult;
import com.flatts.spawndetective.audit.PositionReport;
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
import net.minecraft.world.level.block.Blocks;

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
