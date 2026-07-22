package com.flatts.spawndoctor.registry;

import com.flatts.spawndoctor.SpawnDoctor;
import com.mojang.serialization.Codec;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Data components. One: the block the probe is watching. */
public final class SDDataComponents {

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
        DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, SpawnDoctor.MOD_ID);

    /**
     * The anchored position, stored on the item exactly as a lodestone compass
     * stores its target.
     *
     * <p>A {@link GlobalPos} rather than a bare {@link net.minecraft.core.BlockPos}
     * because an anchor set in the Nether means nothing in the Overworld, and
     * silently auditing the same coordinates in the wrong dimension would be a
     * confident wrong answer of the worst kind.
     *
     * <p>Living on the stack rather than in a player map means it persists across
     * relogs, survives being handed to someone else, and can be read for the
     * tooltip - all for free, and all the way vanilla already does this.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> ANCHOR =
        COMPONENTS.register("anchor", () -> DataComponentType.<GlobalPos>builder()
            .persistent(GlobalPos.CODEC)
            .networkSynchronized(GlobalPos.STREAM_CODEC)
            .build());

    private SDDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }

    /** Convenience so call sites are not littered with the generic dance. */
    public static DataComponentType<GlobalPos> anchor() {
        return ANCHOR.get();
    }

    /** Unused, but keeps the Codec import meaningful if the component grows fields. */
    static Codec<GlobalPos> codec() {
        return GlobalPos.CODEC;
    }
}
