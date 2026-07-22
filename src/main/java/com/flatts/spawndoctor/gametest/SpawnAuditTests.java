package com.flatts.spawndoctor.gametest;

import com.flatts.spawndoctor.audit.AuditReport;
import com.flatts.spawndoctor.audit.RuleResult;
import com.flatts.spawndoctor.audit.SpawnAuditor;
import com.flatts.spawndoctor.audit.SpawnRule;
import com.flatts.spawndoctor.audit.Verdict;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
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
final class SpawnAuditTests {

    private SpawnAuditTests() {
    }

    static void register() {
        SDGameTests.test("audit_covers_every_world_rule", 1, SpawnAuditTests::auditCoversEveryWorldRule);
        SDGameTests.test("enclosed_position_blocks_on_placement", 1, SpawnAuditTests::enclosedBlocksOnPlacement);
        SDGameTests.test("no_floor_blocks_on_placement", 1, SpawnAuditTests::noFloorBlocksOnPlacement);
        SDGameTests.test("bright_chamber_attributes_light", 40, SpawnAuditTests::brightChamberAttributesLight);
        SDGameTests.test("dark_chamber_passes_spawn_rules", 40, SpawnAuditTests::darkChamberPasses);
    }

    /**
     * The world section must evaluate every world/chunk/position rule exactly once.
     * A rule silently dropped from the walk is the worst possible failure mode -
     * the report would look complete while omitting the actual cause.
     */
    private static void auditCoversEveryWorldRule(GameTestHelper helper) {
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
    private static void enclosedBlocksOnPlacement(GameTestHelper helper) {
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
    private static void noFloorBlocksOnPlacement(GameTestHelper helper) {
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
    private static void brightChamberAttributesLight(GameTestHelper helper) {
        BlockPos spawn = carveChamber(helper);
        // Glowstone in the wall beside the spawn block, never under it: putting it in
        // the floor would also make the floor invalid and the test would pass for the
        // wrong reason.
        helper.setBlock(spawn.offset(1, 0, 0), Blocks.GLOWSTONE);

        helper.runAfterDelay(LIGHT_SETTLE_TICKS, () -> {
            RuleResult blocker = blockerFor(helper, spawn, EntityType.ZOMBIE);
            assertRule(helper, blocker, SpawnRule.SPAWN_RULES);
            assertDetailContains(helper, blocker, "cause: light");
            helper.succeed();
        });
    }

    /** The control for the case above: the same sealed chamber, unlit, must not fail on light. */
    private static void darkChamberPasses(GameTestHelper helper) {
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

    /** GameTest assertions take a Component and the current tick; this keeps call sites readable. */
    private static GameTestAssertException fail(GameTestHelper helper, String message) {
        return new GameTestAssertException(Component.literal(message), (int) helper.getTick());
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
