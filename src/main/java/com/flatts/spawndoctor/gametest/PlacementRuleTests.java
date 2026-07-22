package com.flatts.spawndoctor.gametest;

import com.flatts.spawndoctor.audit.AuditReport;
import com.flatts.spawndoctor.audit.RuleResult;
import com.flatts.spawndoctor.audit.SpawnAuditor;
import com.flatts.spawndoctor.audit.SpawnRule;
import com.flatts.spawndoctor.audit.Verdict;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;

/**
 * One test per way a spot can be physically wrong.
 *
 * <p>{@code SpawnPlacements.isSpawnPositionOk} answers all of these with a single
 * boolean, which is never useful to a reader - "placement: false" tells you nothing
 * about whether to break a block, place one, or drain something. The auditor walks
 * the same checks by hand to name the specific one, so each branch of that walk
 * needs a scenario proving it names the right thing.
 *
 * <p>Every case here is deterministic: no light, no RNG, no caps. Just geometry.
 */
final class PlacementRuleTests {

    private PlacementRuleTests() {
    }

    static void register() {
        SDGameTests.test("placement_names_solid_block", 1, PlacementRuleTests::solidBlock);
        SDGameTests.test("placement_names_missing_floor", 1, PlacementRuleTests::missingFloor);
        SDGameTests.test("placement_names_missing_headroom", 1, PlacementRuleTests::missingHeadroom);
        SDGameTests.test("placement_names_fluid", 1, PlacementRuleTests::fluidInTheSpace);
        SDGameTests.test("placement_names_signal_source", 1, PlacementRuleTests::signalSource);
        SDGameTests.test("placement_water_mob_needs_water", 1, PlacementRuleTests::waterMobOnLand);
        SDGameTests.test("placement_water_mob_accepts_water", 1, PlacementRuleTests::waterMobInWater);
        SDGameTests.test("placement_unrestricted_mob_accepts_anywhere", 1, PlacementRuleTests::unrestricted);
        SDGameTests.test("hitbox_rejects_a_mob_too_tall", 1, PlacementRuleTests::tooTall);
    }

    /** A spawn position filled with stone: solid, and it should say so. */
    private static void solidBlock(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.setBlock(spawn, Blocks.STONE);

        assertPlacement(helper, spawn, EntityType.ZOMBIE, "full solid block");
        helper.succeed();
    }

    /** Nothing to stand on. */
    private static void missingFloor(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.setBlock(spawn.below(), Blocks.AIR);

        assertPlacement(helper, spawn, EntityType.ZOMBIE, "floor");
        helper.succeed();
    }

    /** A valid floor but a ceiling one block up: a zombie is two blocks tall. */
    private static void missingHeadroom(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.setBlock(spawn.above(), Blocks.STONE);

        assertPlacement(helper, spawn, EntityType.ZOMBIE, "headroom");
        helper.succeed();
    }

    /** Water in the space a land mob would stand in. */
    private static void fluidInTheSpace(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.setBlock(spawn, Blocks.WATER);

        assertPlacement(helper, spawn, EntityType.ZOMBIE, "fluid");
        helper.succeed();
    }

    /**
     * A powered block in the space. Obscure, invisible in game, and a real cause of
     * "why is this corner of my farm dead" - which is exactly the sort of thing this
     * mod exists to surface.
     */
    private static void signalSource(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.setBlock(spawn, Blocks.REDSTONE_TORCH);

        assertPlacement(helper, spawn, EntityType.ZOMBIE, "redstone");
        helper.succeed();
    }

    /** A water mob on dry land fails placement, and the reason names water. */
    private static void waterMobOnLand(GameTestHelper helper) {
        BlockPos spawn = floor(helper);

        RuleResult placement = ruleFor(helper, spawn, EntityType.COD, SpawnRule.PLACEMENT);
        if (placement.verdict() != Verdict.FAIL) {
            throw fail(helper, "a cod should not pass placement on dry land");
        }
        assertMentions(helper, placement, "water");
        helper.succeed();
    }

    /** The same mob in water passes: the rule is about the medium, not the mob. */
    private static void waterMobInWater(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.setBlock(spawn, Blocks.WATER);
        helper.setBlock(spawn.above(), Blocks.WATER);

        RuleResult placement = ruleFor(helper, spawn, EntityType.COD, SpawnRule.PLACEMENT);
        if (placement.verdict() == Verdict.FAIL) {
            throw fail(helper, "a cod should pass placement in water, got: " + placement.detail());
        }
        helper.succeed();
    }

    /** A mob with no placement restrictions passes regardless of the geometry. */
    private static void unrestricted(GameTestHelper helper) {
        BlockPos spawn = floor(helper);

        RuleResult placement = ruleFor(helper, spawn, EntityType.PHANTOM, SpawnRule.PLACEMENT);
        if (placement.verdict() == Verdict.FAIL) {
            throw fail(helper, "a phantom has no placement restrictions, got: " + placement.detail());
        }
        helper.succeed();
    }

    /**
     * Collision is a separate rule from placement, and the difference matters: a
     * one-block gap has a valid floor and clear headroom for something short, and
     * still cannot hold something tall.
     */
    private static void tooTall(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.setBlock(spawn.above(), Blocks.STONE);

        RuleResult collision = ruleFor(helper, spawn, EntityType.IRON_GOLEM, SpawnRule.NO_COLLISION);
        if (collision.verdict() != Verdict.FAIL) {
            throw fail(helper, "an iron golem does not fit in a one-block gap, got: " + collision.detail());
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    /** A stone floor with two blocks of clear air above it, and the standing position. */
    private static BlockPos floor(GameTestHelper helper) {
        BlockPos spawn = new BlockPos(2, 2, 2);
        helper.setBlock(spawn.below(), Blocks.STONE);
        helper.setBlock(spawn, Blocks.AIR);
        helper.setBlock(spawn.above(), Blocks.AIR);
        return spawn;
    }

    private static RuleResult ruleFor(
        GameTestHelper helper, BlockPos relative, EntityType<?> type, SpawnRule rule
    ) {
        AuditReport.Candidate candidate =
            SpawnAuditor.auditType(helper.getLevel(), helper.absolutePos(relative), type);
        return candidate.rules().stream()
            .filter(r -> r.rule() == rule)
            .findFirst()
            .orElseThrow(() -> fail(helper, "no " + rule + " result for "
                + type.getDescriptionId()));
    }

    private static void assertPlacement(
        GameTestHelper helper, BlockPos relative, EntityType<?> type, String fragment
    ) {
        RuleResult placement = ruleFor(helper, relative, type, SpawnRule.PLACEMENT);
        if (placement.verdict() != Verdict.FAIL) {
            throw fail(helper, "expected placement to fail, got " + placement.verdict()
                + ": " + placement.detail());
        }
        assertMentions(helper, placement, fragment);
    }

    /** The reason has to name the specific problem; "placement failed" is not an answer. */
    private static void assertMentions(GameTestHelper helper, RuleResult result, String fragment) {
        String text = (result.value() + " " + result.detail()).toLowerCase();
        if (!text.contains(fragment.toLowerCase())) {
            throw fail(helper, "expected the reason to mention '" + fragment
                + "', got value='" + result.value() + "' detail='" + result.detail() + "'");
        }
    }

    private static GameTestAssertException fail(GameTestHelper helper, String message) {
        return new GameTestAssertException(Component.literal(message), (int) helper.getTick());
    }
}
