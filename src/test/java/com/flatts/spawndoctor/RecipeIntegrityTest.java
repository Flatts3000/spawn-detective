package com.flatts.spawndoctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The recipe is hand-written JSON, and JSON fails quietly.
 *
 * <p>A mistyped item id, a tag that does not exist, or a result pointing at
 * nothing does not throw at build time and does not crash at runtime - the recipe
 * is simply dropped during datapack load, and the item becomes uncraftable with no
 * error anyone will notice. These checks turn each of those into a build failure.
 */
class RecipeIntegrityTest {

    private static final Path RECIPE = recipe();

    private static Path recipe() {
        try {
            return Path.of(RecipeIntegrityTest.class
                .getResource("/data/spawndoctor/recipe/spawn_probe.json").toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("the probe recipe is not on the test classpath", e);
        }
    }

    private static JsonObject json() {
        try {
            return JsonParser.parseString(Files.readString(RECIPE, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the result is a real registered item")
    void resultExists() {
        String id = json().getAsJsonObject("result").get("id").getAsString();
        assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)),
            "the recipe produces an item that does not exist: " + id);
        assertEquals("spawndoctor:spawn_probe", id);
    }

    @Test
    @DisplayName("every ingredient resolves to a real item or a non-empty tag")
    void ingredientsResolve() {
        JsonObject key = json().getAsJsonObject("key");
        List<String> broken = new ArrayList<>();

        for (String slot : key.keySet()) {
            String ingredient = key.get(slot).getAsString();
            if (ingredient.startsWith("#")) {
                // Contents cannot be checked here: item tags come from datapacks and
                // the JUnit bootstrap loads none, so every tag reads as empty. What
                // IS checkable, and what actually goes wrong, is the name - so the
                // tag must be one vanilla declares.
                if (!declaredTags().contains(ingredient.substring(1))) {
                    broken.add(ingredient + " (not a tag vanilla declares)");
                }
            } else if (!BuiltInRegistries.ITEM.containsKey(Identifier.parse(ingredient))) {
                broken.add(ingredient + " (no such item)");
            }
        }

        assertTrue(broken.isEmpty(), "unresolvable ingredients: " + broken);
    }

    /** Every {@code TagKey<Item>} vanilla declares, by id path. */
    private static java.util.Set<String> declaredTags() {
        java.util.Set<String> tags = new java.util.HashSet<>();
        for (java.lang.reflect.Field field : net.minecraft.tags.ItemTags.class.getDeclaredFields()) {
            if (!TagKey.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                tags.add(((TagKey<?>) field.get(null)).location().toString());
            } catch (ReflectiveOperationException ignored) {
                // A field we cannot read is one we cannot vouch for; skip it.
            }
        }
        return tags;
    }

    @Test
    @DisplayName("the pattern uses exactly the keys it declares")
    void patternMatchesKeys() {
        JsonObject recipe = json();
        JsonObject key = recipe.getAsJsonObject("key");

        List<String> used = new ArrayList<>();
        for (JsonElement row : recipe.getAsJsonArray("pattern")) {
            for (char symbol : row.getAsString().toCharArray()) {
                if (symbol != ' ' && !used.contains(String.valueOf(symbol))) {
                    used.add(String.valueOf(symbol));
                }
            }
        }

        for (String symbol : used) {
            assertTrue(key.has(symbol), "pattern uses '" + symbol + "' with no key entry");
        }
        for (String declared : key.keySet()) {
            assertTrue(used.contains(declared), "key declares '" + declared + "' but the pattern never uses it");
        }
    }

    @Test
    @DisplayName("the recipe is gated on the crafting config flag")
    void gatedOnConfig() {
        // Without the condition the config flag would be decorative: turning crafting
        // off would leave the recipe in the game, which is worse than not offering
        // the setting at all.
        JsonArray conditions = json().getAsJsonArray("neoforge:conditions");
        assertTrue(conditions != null && !conditions.isEmpty(), "the recipe carries no conditions");

        JsonObject condition = conditions.get(0).getAsJsonObject();
        assertEquals("spawndoctor:config_enabled", condition.get("type").getAsString());
        assertEquals("crafting", condition.get("config").getAsString());
    }

    @Test
    @DisplayName("the condition's config key is one the enum actually knows")
    void conditionKeyIsKnown() {
        // The key is a closed enum so a typo fails at decode time rather than
        // silently disabling the thing it was meant to gate. This asserts the JSON
        // and the enum agree in the first place.
        String configured = json().getAsJsonArray("neoforge:conditions")
            .get(0).getAsJsonObject().get("config").getAsString();

        boolean known = java.util.Arrays.stream(
                com.flatts.spawndoctor.data.ConfigEnabledCondition.Key.values())
            .anyMatch(k -> k.getSerializedName().equals(configured));

        assertTrue(known, "no ConfigEnabledCondition.Key is named '" + configured + "'");
    }

    @Test
    @DisplayName("crafting is on by default")
    void craftingDefaultsOn() {
        // The people who most need this are survival players working out why a farm
        // is dead; defaulting off would put it out of reach of exactly them.
        assertTrue(SDConfig.CRAFTING_ENABLED.getDefault());
    }
}
