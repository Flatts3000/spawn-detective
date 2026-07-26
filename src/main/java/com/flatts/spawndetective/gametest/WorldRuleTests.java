package com.flatts.spawndetective.gametest;

import com.flatts.spawndetective.SpawnDetective;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
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
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameRules;

/**
 * The gates that apply to every mob at once, and the obstruction check that so
 * often turns out to be the player themselves.
 *
 * <p>These read live world state rather than a constructed block layout, so each
 * one changes that state, asserts, and puts it back. A test that leaves a gamerule
 * flipped would silently rewrite the meaning of every test batched after it.
 */
@GameTestHolder(SpawnDetective.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldRuleTests {

    private WorldRuleTests() {
    }


    /** doMobSpawning off stops every mob everywhere, so the report must lead with it. */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void gamerule_off_blocks_everything(GameTestHelper helper) {
        BlockPos spawn = floor(helper);
        boolean original = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
        try {
            helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, helper.getLevel().getServer());

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
            helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(original, helper.getLevel().getServer());
        }
        helper.succeed();
    }

    /**
     * A gamerule is a setting, so its failure is temporary by definition: turning it
     * back on fixes everything. Reporting it as permanent would send someone digging
     * up their floor over a checkbox.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void gamerule_off_is_situational(GameTestHelper helper) {
        // A sealed dark chamber, so the gamerule is the ONLY permanent-looking thing
        // wrong. On an open plot the sky lights the floor, light blocks the spawn
        // permanently, and that correctly outranks a reversible setting - which makes
        // an open plot useless for testing what the setting alone reads as.
        BlockPos spawn = chamber(helper);

        helper.runAfterDelay(10L, () -> {
            boolean original = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            try {
                helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, helper.getLevel().getServer());

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
                helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(original, helper.getLevel().getServer());
            }
            helper.succeed();
        });
    }

    /**
     * Peaceful is a gate for hostile categories and for nothing else, and the report
     * has to hold both halves of that at once.
     *
     * <p>Named for the misdiagnosis: the screen answered "CHICKEN IS BLOCKED RIGHT
     * NOW - Difficulty: peaceful" in a swamp that was still spawning chickens. The
     * row lives in the world list, which every mob at the position shares, so it
     * became the headline for all of them.
     *
     * <p>The monster half matters just as much and is the reason this asserts a tone
     * rather than only a rule. Peaceful short-circuits
     * {@code Monster.checkMonsterSpawnRules} before it looks at anything, so the
     * sampled per-type predicate rejects every roll and the report used to answer a
     * zombie with a permanent-sounding "cannot spawn here - the mob's own spawn
     * rules", naming light and floor as leads, for a setting one command reverts.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void peaceful_stops_monsters_only(GameTestHelper helper) {
        // Two spots, because the two mobs need opposite ones and a shared spot makes
        // one of the two assertions vacuous. A standing blocker outranks the
        // difficulty however the scope is tagged, so each mob has to be somewhere it
        // has none: lit grass for the chicken, a sealed dark pocket for the zombie.
        // On lit grass a zombie is refused by its own darkness condition, and in the
        // dark a chicken is refused by its light one.
        BlockPos animalSpot = new BlockPos(4, 2, 4);
        helper.setBlock(animalSpot.below(), Blocks.GRASS_BLOCK);
        helper.setBlock(animalSpot, Blocks.AIR);
        helper.setBlock(animalSpot.above(), Blocks.AIR);

        BlockPos monsterSpot = darkPocket(helper);

        // Tick 20, not 10. Tests in a batch run simultaneously, and every test in this
        // class that changes a world setting does it inside a single tick and puts it
        // back; separate ticks are what keeps them from reading each other's window.
        // gamerule_off_is_situational owns tick 10, and sharing it made this test read
        // a zombie blocked by doMobSpawning instead of by the difficulty.
        helper.runAfterDelay(20L, () -> {
            BlockPos animalPos = helper.absolutePos(animalSpot);
            BlockPos monsterPos = helper.absolutePos(monsterSpot);
            Difficulty original = helper.getLevel().getDifficulty();
            boolean spawning = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
            try {
                // GameTestServer builds its world with doMobSpawning off, and that rule
                // is first in the list, so every blocked verdict in this suite would
                // otherwise be answered by the gamerule before the difficulty is
                // reached. Turned on for this window only, and put back below.
                helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, helper.getLevel().getServer());

                // What the world gates say with the difficulty untouched. Peaceful
                // must not change it: it closes no gate that every mob shares.
                boolean gatesBefore = SpawnAuditor.auditPosition(helper.getLevel(), animalPos).gatesOpen();

                // Flip, read and restore inside one tick. Peaceful discards hostile
                // mobs on their next tick, and other tests in this batch are running.
                helper.getLevel().getServer().setDifficulty(Difficulty.PEACEFUL, true);

                PositionReport position = SpawnAuditor.auditPosition(helper.getLevel(), animalPos);
                RuleResult rule = position.world().stream()
                    .filter(r -> r.rule() == SpawnRule.DIFFICULTY)
                    .findFirst()
                    .orElseThrow(() -> fail(helper, "the difficulty was not evaluated at all"));
                if (rule.verdict() != Verdict.FAIL) {
                    throw fail(helper, "the difficulty is peaceful but the rule passed: " + rule.detail());
                }
                if (position.gatesOpen() != gatesBefore) {
                    throw fail(helper, "peaceful changed what the world gates say for every mob, but "
                        + "it only stops the hostile categories");
                }

                SpawnVerdict animal = verdictFor(helper, animalPos, position, EntityType.CHICKEN);
                if (animal.tone() == SpawnVerdict.Tone.BLOCKED_ALWAYS) {
                    // Guards the assertion below rather than the mod: a standing
                    // blocker outranks the difficulty however the scope is tagged, so
                    // if one exists here the next check can no longer fail.
                    throw fail(helper, "nothing permanent should be wrong with a chicken on lit grass, "
                        + "but " + animal.blocker().rule() + " says " + animal.blocker().detail());
                }
                if (animal.blocker() != null && animal.blocker().rule() == SpawnRule.DIFFICULTY) {
                    throw fail(helper, "a chicken is not a monster, but peaceful was named as its "
                        + "reason: " + animal.blocker().detail());
                }

                PositionReport dark = SpawnAuditor.auditPosition(helper.getLevel(), monsterPos);
                SpawnVerdict monster = verdictFor(helper, monsterPos, dark, EntityType.ZOMBIE);
                if (monster.blocker() == null || monster.blocker().rule() != SpawnRule.DIFFICULTY) {
                    throw fail(helper, "peaceful is why a zombie cannot spawn, but the report blamed "
                        + (monster.blocker() == null ? "nothing" : monster.blocker().rule().toString()));
                }
                if (monster.tone() != SpawnVerdict.Tone.BLOCKED_NOW) {
                    throw fail(helper, "the difficulty is a setting, so it should read as temporary, got "
                        + monster.tone());
                }

                // The rule peaceful short-circuits from in front of. It used to answer
                // for the difficulty in the wrong words and at the wrong permanence:
                // "the mob's own spawn rules", listing light and floor as leads.
                // Nothing about this spot is measurable while the predicate refuses
                // before it reads the position.
                RuleResult ownRules = ruleFor(helper, monsterSpot, EntityType.ZOMBIE, SpawnRule.SPAWN_RULES);
                if (ownRules.verdict() != Verdict.UNKNOWN) {
                    throw fail(helper, "the mob's own spawn rules reported " + ownRules.verdict()
                        + " on peaceful, but peaceful refuses before they are reached: " + ownRules.detail());
                }
            } finally {
                helper.getLevel().getServer().setDifficulty(original, true);
                helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(spawning, helper.getLevel().getServer());
            }
            helper.succeed();
        });
    }

    /**
     * Something standing in the space blocks the spawn, and the report has to say
     * what. This is the mechanism behind the report once blaming a mod for a spawn
     * the player's own body was occupying.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 20)
    public static void obstruction_names_the_entity_in_the_way(GameTestHelper helper) {
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void obstruction_reports_clear_space(GameTestHelper helper) {
        BlockPos spawn = floor(helper);

        RuleResult obstruction = ruleFor(helper, spawn, EntityType.ZOMBIE, SpawnRule.SPAWN_OBSTRUCTED);
        if (obstruction.verdict() == Verdict.FAIL) {
            throw fail(helper, "nothing is here, but obstruction failed: " + obstruction.detail());
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * As {@link #chamber} but only in one corner, so the rest of the plot keeps its
     * sky and a second spot in the same test can be a lit one.
     */
    private static BlockPos darkPocket(GameTestHelper helper) {
        for (int x = 0; x <= 2; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z <= 2; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        BlockPos spawn = new BlockPos(1, 2, 1);
        helper.setBlock(spawn, Blocks.AIR);
        helper.setBlock(spawn.above(), Blocks.AIR);
        return spawn;
    }

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

    private static SpawnVerdict verdictFor(
        GameTestHelper helper, BlockPos absolute, PositionReport position, EntityType<?> type
    ) {
        return SpawnVerdict.of(position, SpawnAuditor.auditType(helper.getLevel(), absolute, type));
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
        return new GameTestAssertException(message);
    }
}
