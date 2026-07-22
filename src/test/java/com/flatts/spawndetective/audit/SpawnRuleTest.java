package com.flatts.spawndetective.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link SpawnRule} enum is a contract, not just a list.
 *
 * <p>Two properties are load-bearing and neither is obvious from reading the file:
 * the declaration order IS the pipeline order (the report walks it top to bottom),
 * and every rule's {@code Persistence} decides whether a failure reads as permanent
 * or temporary - which is the difference between "your floor is wrong" and "you are
 * standing here". Both are the kind of thing a careless edit silently breaks, so
 * they are pinned here rather than trusted to review.
 */
class SpawnRuleTest {

    /**
     * The rules that revert on their own, spelled out.
     *
     * <p>Duplicated from the enum deliberately. A test that derives its expectation
     * from the code under test cannot fail, and mis-tagging a rule is exactly the
     * mistake that would make a permanent problem read as temporary.
     */
    private static final Set<SpawnRule> SITUATIONAL = EnumSet.of(
        SpawnRule.GAMERULE_MOB_SPAWNING,
        SpawnRule.DIFFICULTY,
        SpawnRule.SERVER_SPAWN_ENEMIES,
        SpawnRule.CHUNK_ENTITY_TICKING,
        SpawnRule.PLAYER_IN_SPAWN_RANGE,
        SpawnRule.CATEGORY_GLOBAL_CAP,
        SpawnRule.CATEGORY_LOCAL_CAP,
        SpawnRule.PLAYER_DISTANCE,
        SpawnRule.DESPAWN_DISTANCE,
        SpawnRule.SPAWN_CHARGE,
        SpawnRule.SPAWN_OBSTRUCTED);

    @Test
    @DisplayName("declaration order is pipeline order: layers never go backwards")
    void layersAreMonotonic() {
        List<SpawnRule.Layer> order = List.of(
            SpawnRule.Layer.WORLD, SpawnRule.Layer.CHUNK,
            SpawnRule.Layer.POSITION, SpawnRule.Layer.MOB);

        int furthest = 0;
        for (SpawnRule rule : SpawnRule.values()) {
            int index = order.indexOf(rule.layer());
            assertTrue(index >= furthest,
                rule + " is declared after a later layer; the report walks this order and would "
                    + "name the wrong first cause");
            furthest = index;
        }
    }

    @Test
    @DisplayName("every rule has a usable title")
    void everyRuleHasATitle() {
        for (SpawnRule rule : SpawnRule.values()) {
            assertNotNull(rule.title(), rule + " has no title");
            assertFalse(rule.title().isBlank(), rule + " has a blank title");
        }
    }

    @Test
    @DisplayName("titles are unique, so a report line names one rule unambiguously")
    void titlesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (SpawnRule rule : SpawnRule.values()) {
            assertTrue(seen.add(rule.title()), "duplicate rule title: " + rule.title());
        }
    }

    @Test
    @DisplayName("persistence tagging matches the documented set")
    void persistenceIsTaggedAsDocumented() {
        Set<SpawnRule> tagged = EnumSet.noneOf(SpawnRule.class);
        for (SpawnRule rule : SpawnRule.values()) {
            if (!rule.standing()) {
                tagged.add(rule);
            }
        }
        assertEquals(SITUATIONAL, tagged,
            "a rule changed persistence; that flips whether its failure reads as permanent");
    }

    @Test
    @DisplayName("titles read as neutral nouns, not as assertions of the good outcome")
    void titlesDoNotAssertSuccess() {
        // "Category is under the global cap: 75 / 70 FULL" was a real screen. A title
        // phrased as the passing case contradicts itself the moment the rule fails.
        List<String> assertive = List.of(" is under ", " is not ", "at least ", " fits without ");
        for (SpawnRule rule : SpawnRule.values()) {
            String title = rule.title().toLowerCase();
            for (String phrase : assertive) {
                assertFalse(title.contains(phrase),
                    rule + " asserts its passing case in the title (\"" + rule.title()
                        + "\"), which reads as a contradiction when it fails");
            }
        }
    }

    @Test
    @DisplayName("every situational rule offers a remedy")
    void situationalRulesTellYouWhatToDo() {
        // A temporary blocker is always actionable by definition - something can be
        // changed to clear it - so having nothing to suggest means the rule is either
        // mis-tagged or under-explained.
        for (SpawnRule rule : SITUATIONAL) {
            assertNotNull(rule.remedy(), rule + " is situational but suggests nothing to do about it");
        }
    }

    @Test
    @DisplayName("the enum covers every layer")
    void everyLayerIsUsed() {
        Set<SpawnRule.Layer> used = EnumSet.noneOf(SpawnRule.Layer.class);
        Arrays.stream(SpawnRule.values()).forEach(rule -> used.add(rule.layer()));
        assertEquals(EnumSet.allOf(SpawnRule.Layer.class), used, "a layer has no rules in it");
    }
}
