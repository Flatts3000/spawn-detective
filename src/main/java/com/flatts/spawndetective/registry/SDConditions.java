package com.flatts.spawndetective.registry;

import com.flatts.spawndetective.SpawnDetective;
import com.flatts.spawndetective.data.ConfigEnabledCondition;
import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Datapack condition codecs. Registering here is what makes the {@code "type"}
 * string usable inside a {@code neoforge:conditions} block.
 */
public final class SDConditions {

    public static final DeferredRegister<MapCodec<? extends ICondition>> CONDITION_CODECS =
        DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, SpawnDetective.MOD_ID);

    public static final Supplier<MapCodec<? extends ICondition>> CONFIG_ENABLED =
        CONDITION_CODECS.register("config_enabled", () -> ConfigEnabledCondition.CODEC);

    private SDConditions() {
    }

    public static void register(IEventBus modEventBus) {
        CONDITION_CODECS.register(modEventBus);
    }
}
