package com.flatts.spawndetective.data;

import com.flatts.spawndetective.SDConfig;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;
import net.neoforged.neoforge.common.conditions.ICondition;

/**
 * A datapack condition that passes only when a named config flag is on.
 *
 * <p>NeoForge ships {@code mod_loaded}, {@code item_exists} and friends, but
 * nothing that reads a mod's own config, so a recipe that should appear only when a
 * feature is enabled needs this. Gating at the datapack layer rather than in code
 * means a disabled recipe is genuinely absent - it does not craft, and JEI does not
 * advertise it - instead of existing but refusing, which reads as a bug.
 *
 * <p>The {@code config} key is a closed enum rather than a free string so a typo in
 * a JSON fails at decode time, loudly, instead of silently disabling whatever it
 * was meant to gate.
 */
public record ConfigEnabledCondition(Key config) implements ICondition {

    public static final MapCodec<ConfigEnabledCondition> CODEC =
        RecordCodecBuilder.mapCodec(instance -> instance.group(
            Key.CODEC.fieldOf("config").forGetter(ConfigEnabledCondition::config)
        ).apply(instance, ConfigEnabledCondition::new));

    @Override
    public boolean test(IContext context) {
        return this.config.isEnabled();
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }

    /** Every config flag that may gate JSON. One entry per gate, by design. */
    public enum Key implements StringRepresentable {
        CRAFTING("crafting") {
            @Override
            boolean read() {
                return SDConfig.craftingEnabled();
            }
        };

        public static final StringRepresentable.EnumCodec<Key> CODEC =
            StringRepresentable.fromEnum(Key::values);

        private final String name;

        Key(String name) {
            this.name = name;
        }

        abstract boolean read();

        boolean isEnabled() {
            return read();
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
