package com.flatts.spawndoctor.registry;

import com.flatts.spawndoctor.SpawnDoctor;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.EntityType;
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

    /**
     * The mob the holder last asked about.
     *
     * <p>On the stack rather than in client memory for two reasons: it survives a
     * relog, which is what "remember my selection" has to mean to be worth having;
     * and the server can read it off the held item, which is the only way Jade can
     * show that mob's verdict for whatever block you are looking at.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<EntityType<?>>> SELECTED_MOB =
        COMPONENTS.register("selected_mob", () -> DataComponentType.<EntityType<?>>builder()
            .persistent(BuiltInRegistries.ENTITY_TYPE.byNameCodec())
            .networkSynchronized(ByteBufCodecs.registry(Registries.ENTITY_TYPE))
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

    public static DataComponentType<EntityType<?>> selectedMob() {
        return SELECTED_MOB.get();
    }
}
