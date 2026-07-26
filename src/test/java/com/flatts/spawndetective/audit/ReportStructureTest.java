package com.flatts.spawndetective.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape of {@link PositionReport} and {@link AuditReport}: which sections count
 * as relevant, what "viable" means with and without temporary blockers, and how the
 * aggregate headline picks its subject.
 */
class ReportStructureTest {

    private static final BlockPos POS = new BlockPos(0, 64, 0);

    private static RuleResult pass(SpawnRule rule) {
        return RuleResult.pass(rule, "ok", "fine");
    }

    private static RuleResult fail(SpawnRule rule) {
        return RuleResult.fail(rule, "no", "blocked");
    }

    private static AuditReport.Candidate candidate(EntityType<?> type, RuleResult... rules) {
        return new AuditReport.Candidate(type, 1, 4, List.of(rules));
    }

    // ------------------------------------------------------------ PositionReport

    @Test
    @DisplayName("position gates are open only when nothing fails")
    void positionGates() {
        PositionReport open = new PositionReport("d", POS, "b",
            List.of(pass(SpawnRule.WORLD_BORDER)), List.of());
        PositionReport shut = new PositionReport("d", POS, "b",
            List.of(pass(SpawnRule.WORLD_BORDER), fail(SpawnRule.CHUNK_ENTITY_TICKING)), List.of());

        assertTrue(open.gatesOpen());
        assertFalse(shut.gatesOpen());
        assertEquals(1, open.passing());
    }

    @Test
    @DisplayName("a gate only some mobs are subject to does not close the position")
    void positionGatesIgnoreScopedRules() {
        // "Whatever the mob" is the claim gatesOpen() makes, and peaceful is not that:
        // it stops the hostile categories and leaves the swamp full of chickens. The
        // screen reads this to decide whether to open the world section on "blocked".
        PositionReport peaceful = new PositionReport("d", POS, "b",
            List.of(pass(SpawnRule.WORLD_BORDER), fail(SpawnRule.DIFFICULTY)), List.of());

        assertTrue(peaceful.gatesOpen());
        assertTrue(peaceful.blocker().isEmpty(), "there is no blocker every mob here shares");
    }

    @Test
    @DisplayName("the position's own blocker ignores temporary rules")
    void positionBlockerIsStandingOnly() {
        // A shut cap or a nearby player is real, but it is not a property of the
        // place, and this accessor answers "what is wrong with here".
        PositionReport report = new PositionReport("d", POS, "b",
            List.of(fail(SpawnRule.PLAYER_DISTANCE), fail(SpawnRule.WORLD_BORDER)), List.of());

        assertTrue(report.blocker().isPresent());
        assertSame(SpawnRule.WORLD_BORDER, report.blocker().get().rule());
    }

    // -------------------------------------------------------------- Candidate

    @Test
    @DisplayName("viable means nothing blocks; viableStanding ignores the temporary")
    void candidateViability() {
        // Deliberately not the mob cap. A full cap reports MARGINAL rather than FAIL,
        // so "a full cap blocks this" is a state the auditor cannot produce, and a
        // test asserting it would document behaviour the mod does not have. The
        // spawn-cost budget is the situational rule that does still refuse outright.
        AuditReport.Candidate blockedNow = candidate(EntityType.ZOMBIE, fail(SpawnRule.SPAWN_CHARGE));

        assertFalse(blockedNow.viable(), "an exceeded spawn-cost budget does block it right now");
        assertTrue(blockedNow.viableStanding(), "but nothing permanent is wrong, so it would spawn later");
    }

    @Test
    @DisplayName("a permanent blocker fails both")
    void permanentFailsBoth() {
        AuditReport.Candidate blocked = candidate(EntityType.ZOMBIE, fail(SpawnRule.PLACEMENT));

        assertFalse(blocked.viable());
        assertFalse(blocked.viableStanding());
    }

    @Test
    @DisplayName("a candidate ignores rules its category is not subject to")
    void candidateIgnoresScopedRules() {
        // Contract test on the record: the auditor keeps the hostile-only rules in the
        // world list today, but viable() answers for one named mob and must agree with
        // SpawnVerdict wherever the rule is carried from.
        assertTrue(candidate(EntityType.CHICKEN, fail(SpawnRule.DIFFICULTY)).viable());
        assertFalse(candidate(EntityType.ZOMBIE, fail(SpawnRule.DIFFICULTY)).viable());
    }

    @Test
    @DisplayName("roll chance is the entry's share of the list")
    void rollChance() {
        assertEquals(25.0F, candidate(EntityType.ZOMBIE).rollChance(), 0.01F);
        // A type asked about directly has no list entry, so it has no odds to report.
        assertEquals(0.0F, new AuditReport.Candidate(EntityType.ZOMBIE, 0, 0, List.of()).rollChance(), 0.01F);
    }

    // --------------------------------------------------------------- Category

    @Test
    @DisplayName("a category the biome offers nothing for is not relevant")
    void emptyCategoryIsIrrelevant() {
        // Telling someone axolotls do not spawn in their forest is not an answer to
        // any question they asked.
        AuditReport.Category empty = new AuditReport.Category(
            MobCategory.AXOLOTLS, List.of(fail(SpawnRule.BIOME_SPAWN_LIST)), List.of());

        assertFalse(empty.relevant());
    }

    @Test
    @DisplayName("a shut category gate makes every mob under it non-viable")
    void shutCategoryGate() {
        // Contract test on the record, not a report the auditor emits: since the caps
        // went MARGINAL, no category rule fails situationally any more. The accessors
        // still have to behave for any rule combination - a future rule, or a mod's
        // category - so the logic is pinned here rather than left to whatever happens
        // to be reachable today.
        AuditReport.Category capped = new AuditReport.Category(
            MobCategory.MONSTER,
            List.of(fail(SpawnRule.CATEGORY_GLOBAL_CAP)),
            List.of(candidate(EntityType.ZOMBIE)));

        assertTrue(capped.relevant());
        assertFalse(capped.gatesOpen());
        assertEquals(0L, capped.viableCount());
        assertTrue(capped.standingGatesOpen(), "the cap is temporary, so nothing permanent shuts it");
    }

    // ----------------------------------------------------------- AuditReport

    @Test
    @DisplayName("relevant categories lead with monsters")
    void monstersLead() {
        AuditReport report = new AuditReport("d", POS, "b", List.of(), List.of(
            new AuditReport.Category(MobCategory.AMBIENT, List.of(), List.of(candidate(EntityType.BAT))),
            new AuditReport.Category(MobCategory.MONSTER, List.of(), List.of(candidate(EntityType.ZOMBIE))),
            new AuditReport.Category(MobCategory.CREATURE, List.of(), List.of(candidate(EntityType.COW)))));

        List<MobCategory> order = report.relevantCategories().stream()
            .map(AuditReport.Category::category)
            .toList();

        assertEquals(List.of(MobCategory.MONSTER, MobCategory.CREATURE, MobCategory.AMBIENT), order,
            "almost every question is about monsters");
    }

    @Test
    @DisplayName("empty categories are dropped from the display list entirely")
    void emptyCategoriesDropped() {
        AuditReport report = new AuditReport("d", POS, "b", List.of(), List.of(
            new AuditReport.Category(MobCategory.WATER_CREATURE, List.of(), List.of()),
            new AuditReport.Category(MobCategory.MONSTER, List.of(), List.of(candidate(EntityType.ZOMBIE)))));

        assertEquals(1, report.relevantCategories().size());
    }

    @Test
    @DisplayName("a sweep still reports which mobs are viable")
    void sweepCountsViableMobs() {
        // The aggregate headline is gone - it averaged findings into a claim true of
        // none of them - but the sweep itself still has to answer "what can spawn
        // here", one mob at a time.
        AuditReport report = new AuditReport("d", POS, "b", List.of(), List.of(
            new AuditReport.Category(MobCategory.MONSTER, List.of(), List.of(
                candidate(EntityType.ZOMBIE, pass(SpawnRule.PLACEMENT)),
                candidate(EntityType.CREEPER, fail(SpawnRule.PLACEMENT))))));

        assertTrue(report.anythingCanSpawn());
        assertEquals(1L, report.relevantCategories().getFirst().viableCount());
    }
}
