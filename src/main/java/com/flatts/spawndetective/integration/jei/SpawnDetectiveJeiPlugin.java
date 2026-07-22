package com.flatts.spawndetective.integration.jei;

import com.flatts.spawndetective.SpawnDetective;
import com.flatts.spawndetective.registry.SDItems;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * JEI integration: an info page for the Spawn Probe.
 *
 * <p>Deliberately nothing more. This mod adds no recipes and no machines, so there
 * is no process for JEI to diagram; inventing a recipe category to look integrated
 * would put a page in front of people that teaches them nothing.
 *
 * <p>What JEI is genuinely good for here is discovery. The probe is an item with a
 * non-obvious two-step gesture, and JEI is where people look when they find an item
 * and do not know what it does - so that is exactly what the page says.
 */
@JeiPlugin
public class SpawnDetectiveJeiPlugin implements IModPlugin {

    private static final Identifier UID =
        Identifier.fromNamespaceAndPath(SpawnDetective.MOD_ID, "jei");

    @Override
    public Identifier getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        registration.addIngredientInfo(
            SDItems.SPAWN_PROBE.get(),
            Component.translatable("spawndetective.jei.probe.what"),
            Component.translatable("spawndetective.jei.probe.anchor"),
            Component.translatable("spawndetective.jei.probe.read"),
            Component.translatable("spawndetective.jei.probe.why"));
    }
}
