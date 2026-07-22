package com.flatts.spawndetective.event;

import com.flatts.spawndetective.registry.SDItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Mod-bus listeners, wired explicitly from the mod constructor rather than by
 * annotation. The probe joins a vanilla tab; one utility item does not justify a
 * creative tab of its own.
 */
public final class SDModBusEvents {

    private SDModBusEvents() {
    }

    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SDItems.SPAWN_PROBE.get());
        }
    }
}
