package com.flatts.spawndetective.gametest;

import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.PositionReport;
import com.flatts.spawndetective.audit.RuleResult;
import com.flatts.spawndetective.audit.SpawnAuditor;
import com.flatts.spawndetective.audit.SpawnRule;
import com.flatts.spawndetective.audit.SpawnVerdict;
import com.flatts.spawndetective.audit.Verdict;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The gates that apply to every mob at once, and the obstruction check that so
 * often turns out to be the player themselves.
 *
 * <p>These read live world state rather than a constructed block layout, so each
 * one changes that state, asserts, and puts it back. A test that leaves a gamerule
 * flipped would silently rewrite the meaning of every test batched after it.
 */
final class WorldRuleTests {

    private WorldRuleTests() {
    }

    static void register() {
        SDGameTests.test("gamerule_off_blocks_everything", 1, WorldRuleTests::gameruleOff);
        SDGameTests.test("gamerule_off_is_situational", 40, WorldRuleTests::gameruleIsSituational);
        SDGameTests.test("obstruction_names_the_entity_in_the_way", 20, WorldRuleTests::entityInTheWay);
        SDGameTests.test("obstruction_reports_clear_space", 1, WorldRuleTests::clearSpace);
    }

    /** doMobSpawning off stops every mob everywhere, so the report must lead with it. */
    private static void gameruleOff(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        boolean original = helper.getLevel().getGameRules().get(GameRules.SPAWN_MOBS);
        try {
            helper.getLevel().getGameRules().set(GameRules.SPAWN_MOBS, false, helper.getLevel().getServer());

            PositionReport report = SpawnAuditor.auditPosition(helper.getLevel(), helper.absolutePos(spawn));
            RuleResult rule = report.world().stream()
                .filter(r -> r.rule() == SpawnRule.GAMERULE_MOB_SPAWNING)
                .findFirst()
                .orElseThrow(() -> fail(helper, "the gamerule was not evaluated at all"));

            if (rule.verdict() != Verdict.FAIL) {
                throw fail(helper, "doMobSpawning is off but the rule passed: " + rule.detail());
            }
            if (report.gatesOpen()) {
                throw fail(helper, "world gates reported open with mob spawning disabled");
            }
        } finally {
            helper.getLevel().getGameRules().set(GameRules.SPAWN_MOBS, original, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    /**
     * A gamerule is a setting, so its failure is temporary by definition: turning it
     * back on fixes everything. Reporting it as permanent would send someone digging
     * up their floor over a checkbox.
     */
    private static void gameruleIsSituational(GameTestHelper helper) {
        // A sealed dark chamber, so the gamerule is the ONLY permanent-looking thing
        // wrong. On an open plot the sky lights the floor, light blocks the spawn
        // permanently, and that correctly outranks a reversible setting - which makes
        // an open plot useless for testing what the setting alone reads as.
        BlockPos spawn = chamber(helper);

        helper.runAfterDelay(10L, () -> {
            boolean original = helper.getLevel().getGameRules().get(GameRules.SPAWN_MOBS);
            try {
                helper.getLevel().getGameRules().set(GameRules.SPAWN_MOBS, false, helper.getLevel().getServer());

                BlockPos absolute = helper.absolutePos(spawn);
                PositionReport position = SpawnAuditor.auditPosition(helper.getLevel(), absolute);
                AuditReport.Candidate candidate =
                    SpawnAuditor.auditType(helper.getLevel(), absolute, EntityType.ZOMBIE);
                SpawnVerdict verdict = SpawnVerdict.of(position, candidate);

                if (verdict.canSpawn()) {
                    throw fail(helper, "nothing spawns with doMobSpawning off");
                }
                if (verdict.tone() != SpawnVerdict.Tone.BLOCKED_NOW) {
                    throw fail(helper, "a gamerule is reversible, so it should read as temporary, got "
                        + verdict.tone() + " on " + verdict.blocker().rule() + " ("
                        + verdict.blocker().detail() + ")");
                }
            } finally {
                helper.getLevel().getGameRules().set(GameRules.SPAWN_MOBS, original, helper.getLevel().getServer());
            }
            helper.succeed();
        });
    }

    /**
     * Something standing in the space blocks the spawn, and the report has to say
     * what. This is the mechanism behind the report once blaming a mod for a spawn
     * the player's own body was occupying.
     */
    private static void entityInTheWay(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        helper.spawn(EntityType.COW, spawn);

        helper.runAfterDelay(5L, () -> {
            RuleResult obstruction = ruleFor(helper, spawn, EntityType.ZOMBIE, SpawnRule.SPAWN_OBSTRUCTED);
            if (obstruction.verdict() != Verdict.FAIL) {
                throw fail(helper, "a cow is standing here; the space is not clear");
            }
            String text = (obstruction.value() + " " + obstruction.detail()).toLowerCase();
            if (!text.contains("cow")) {
                throw fail(helper, "the reason should name what is in the way, got: " + obstruction.detail());
            }
            helper.succeed();
        });
    }

    /** The control: an empty space reports clear, and never invents an occupant. */
    private static void clearSpace(GameTestHelper helper) {
        BlockPos spawn = floor(helper);

        RuleResult obstruction = ruleFor(helper, spawn, EntityType.ZOMBIE, SpawnRule.SPAWN_OBSTRUCTED);
        if (obstruction.verdict() == Verdict.FAIL) {
            throw fail(helper, "nothing is here, but obstruction failed: " + obstruction.detail());
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    /** Fill the plot with stone and carve a sealed two-block pocket: dark, valid floor. */
    private static BlockPos chamber(GameTestHelper helper) {
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
            .orElseThrow(() -> fail(helper, "no " + rule + " result"));
    }

    private static GameTestAssertException fail(GameTestHelper helper, String message) {
        return new GameTestAssertException(Component.literal(message), (int) helper.getTick());
    }
}
