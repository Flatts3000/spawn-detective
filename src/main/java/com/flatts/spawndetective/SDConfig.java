package com.flatts.spawndetective;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The mod's config. One flag so far, and the bar for adding another is high: a
 * diagnostic tool with settings that change what it reports is a diagnostic tool
 * nobody can trust the output of.
 */
public final class SDConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * Whether the Spawn Probe has a crafting recipe.
     *
     * <p>On by default, because the people who most need this are survival players
     * working out why their farm is dead, and gating the tool behind operator
     * commands puts it out of reach of exactly them. Packs that would rather hand
     * it out themselves, or treat it as an admin instrument, turn this off and the
     * recipe disappears from the game and from JEI.
     *
     * <p>Common rather than server config: recipes are datapack state, and the
     * condition that reads this is evaluated during datapack load.
     */
    public static final ModConfigSpec.BooleanValue CRAFTING_ENABLED = BUILDER
        .comment("Whether the Spawn Probe can be crafted.",
            "Off means the item is only obtainable from the creative tab or /give.")
        .define("crafting.enabled", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SDConfig() {
    }

    /**
     * Read the flag without throwing before the config has loaded.
     *
     * <p>Datapack conditions run after common config load, so this is normally
     * fine. It still guards, and fails <b>open</b> rather than closed: an unreadable
     * config should leave the recipe present, since the surprising outcome for a
     * player is a tool that quietly cannot be made.
     */
    public static boolean craftingEnabled() {
        return !SPEC.isLoaded() || CRAFTING_ENABLED.get();
    }
}
