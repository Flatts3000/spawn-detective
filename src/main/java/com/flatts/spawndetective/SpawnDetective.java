package com.flatts.spawndetective;

import com.flatts.spawndetective.event.SDModBusEvents;
import com.flatts.spawndetective.gametest.SDGameTests;
import com.flatts.spawndetective.network.SDPayloads;
import com.flatts.spawndetective.registry.SDConditions;
import com.flatts.spawndetective.registry.SDDataComponents;
import com.flatts.spawndetective.registry.SDItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawn Detective mod entry point.
 *
 * <p>Single purpose: answer "why won't mobs spawn here?" definitively. The mod
 * adds no world content - one probe item, one command, and an engine
 * ({@link com.flatts.spawndetective.audit.SpawnAuditor}) that replays the real
 * {@link net.minecraft.world.level.NaturalSpawner} pipeline against a single
 * block position and reports which rule rejected it.
 */
@Mod(SpawnDetective.MOD_ID)
public final class SpawnDetective {

    public static final String MOD_ID = "spawndetective";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public SpawnDetective(IEventBus modEventBus, ModContainer modContainer) {
        SDDataComponents.register(modEventBus);
        SDConditions.register(modEventBus);
        SDItems.register(modEventBus);
        modEventBus.addListener(SDModBusEvents::onBuildCreativeTabs);
        modEventBus.addListener(SDPayloads::register);
        SDGameTests.register(modEventBus);

        // COMMON, not SERVER: datapack conditions are evaluated during datapack
        // load, and the recipe gate reads this.
        modContainer.registerConfig(ModConfig.Type.COMMON, SDConfig.SPEC);
    }
}
