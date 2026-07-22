package com.flatts.spawndoctor;

import com.flatts.spawndoctor.event.SDModBusEvents;
import com.flatts.spawndoctor.gametest.SDGameTests;
import com.flatts.spawndoctor.network.SDPayloads;
import com.flatts.spawndoctor.registry.SDDataComponents;
import com.flatts.spawndoctor.registry.SDItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spawn Doctor mod entry point.
 *
 * <p>Single purpose: answer "why won't mobs spawn here?" definitively. The mod
 * adds no world content - one probe item, one command, and an engine
 * ({@link com.flatts.spawndoctor.audit.SpawnAuditor}) that replays the real
 * {@link net.minecraft.world.level.NaturalSpawner} pipeline against a single
 * block position and reports which rule rejected it.
 */
@Mod(SpawnDoctor.MOD_ID)
public final class SpawnDoctor {

    public static final String MOD_ID = "spawndoctor";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public SpawnDoctor(IEventBus modEventBus, ModContainer modContainer) {
        SDDataComponents.register(modEventBus);
        SDItems.register(modEventBus);
        modEventBus.addListener(SDModBusEvents::onBuildCreativeTabs);
        modEventBus.addListener(SDPayloads::register);
        SDGameTests.register(modEventBus);
    }
}
