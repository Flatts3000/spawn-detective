package com.flatts.spawndoctor.registry;

import com.flatts.spawndoctor.SpawnDoctor;
import com.flatts.spawndoctor.item.SpawnProbeItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registry. One item: the probe. */
public final class SDItems {

    public static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(SpawnDoctor.MOD_ID);

    /**
     * The Spawn Probe. Stacks to 1 - it is a tool, and a stack of them would only
     * ever be a nuisance in an inventory.
     */
    public static final DeferredItem<SpawnProbeItem> SPAWN_PROBE = ITEMS.registerItem(
        "spawn_probe",
        props -> new SpawnProbeItem(props.stacksTo(1))
    );

    private SDItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
