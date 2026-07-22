package com.flatts.spawndetective.integration.jade;

import com.flatts.spawndetective.SpawnDetective;
import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.PositionReport;
import com.flatts.spawndetective.audit.SpawnAuditor;
import com.flatts.spawndetective.audit.SpawnVerdict;
import com.flatts.spawndetective.item.SpawnProbeItem;
import com.flatts.spawndetective.registry.SDDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade integration: the selected mob's verdict for whatever block you are looking
 * at, without clicking anything.
 *
 * <p>This is the passive counterpart to the probe. The probe answers precisely for
 * one anchored block; this answers continuously as you sweep a room, which is what
 * you actually want while lighting one up. It deliberately shows the <b>same</b>
 * verdict the screen would - both go through {@link SpawnVerdict} - because two
 * surfaces disagreeing about one block is how a diagnostic tool loses its reader.
 *
 * <p>The audit is real server work, so it is gated hard: {@code shouldRequestData}
 * returns false unless the player is holding a probe that already has a mob
 * selected. Someone not using the mod never pays for it.
 *
 * <p>Jade is {@code compileOnly} and a manual {@code run/mods} drop-in; when it is
 * absent this class is never loaded.
 */
@WailaPlugin
public final class SpawnDetectiveJadePlugin implements IWailaPlugin {

    private static final Identifier UID =
        Identifier.fromNamespaceAndPath(SpawnDetective.MOD_ID, "spawn_check");

    /** NBT keys for the server -> client hop. Short, because this rides every look. */
    private static final String KEY_MOB = "mob";
    private static final String KEY_TONE = "tone";
    private static final String KEY_REASON = "reason";

    private static final SpawnCheckProvider PROVIDER = new SpawnCheckProvider();

    /**
     * Jade 26.1 forbids a data provider from also implementing a component
     * provider, so the server half registers through this single-interface
     * delegate sharing the client half's UID.
     */
    private record DataDelegate(Identifier uid) implements IServerDataProvider<BlockAccessor> {
        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            SpawnCheckProvider.append(data, accessor);
        }

        @Override
        public boolean shouldRequestData(BlockAccessor accessor) {
            return selectedMob(accessor) != null;
        }

        @Override
        public Identifier getUid() {
            return this.uid;
        }
    }

    @Override
    public void register(IWailaCommonRegistration registration) {
        // Every block: spawning is a property of the space, not of any particular
        // block type, and the interesting answers are usually about plain stone.
        registration.registerBlockDataProvider(new DataDelegate(UID), Block.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(PROVIDER, Block.class);
    }

    /** The mob the looking player's held probe remembers, or null if they are not using one. */
    private static @Nullable EntityType<?> selectedMob(Accessor<?> accessor) {
        if (!(accessor.getPlayer() instanceof net.minecraft.world.entity.player.Player player)) {
            return null;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof SpawnProbeItem) {
                EntityType<?> selected = held.get(SDDataComponents.selectedMob());
                if (selected != null) {
                    return selected;
                }
            }
        }
        return null;
    }

    /** The client half: renders whatever the server attached. */
    private static final class SpawnCheckProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains(KEY_TONE)) {
                return;
            }
            String mob = data.getStringOr(KEY_MOB, "");
            SpawnVerdict.Tone tone = toneOf(data.getIntOr(KEY_TONE, 0));
            String reason = data.getStringOr(KEY_REASON, "");

            tooltip.add(Component.literal(mob + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(label(tone)).withStyle(colour(tone))));
            if (!reason.isEmpty()) {
                tooltip.add(Component.literal(reason).withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        @Override
        public Identifier getUid() {
            return UID;
        }

        /**
         * The server half. Audits the space <i>above</i> the block being looked at,
         * because that is where a mob would stand - looking at a floor should answer
         * about the room, not about the floor.
         */
        static void append(CompoundTag data, BlockAccessor accessor) {
            EntityType<?> type = selectedMob(accessor);
            if (type == null || !(accessor.getLevel() instanceof ServerLevel level)
                || !(accessor.getPlayer() instanceof ServerPlayer)) {
                return;
            }

            BlockPos pos = accessor.getPosition().above();
            try {
                PositionReport position = SpawnAuditor.auditPosition(level, pos);
                AuditReport.Candidate candidate = SpawnAuditor.auditType(level, pos, type);
                SpawnVerdict verdict = SpawnVerdict.of(position, candidate);

                data.putString(KEY_MOB, BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath());
                data.putInt(KEY_TONE, verdict.tone().ordinal());
                data.putString(KEY_REASON, verdict.blocker() == null ? ""
                    : verdict.blocker().rule().title() + ": " + verdict.blocker().summary());
            } catch (Throwable t) {
                // A look-at tooltip must never take the server down over a mod's
                // misbehaving spawn predicate. Say nothing rather than crash.
                SpawnDetective.LOGGER.debug("Jade spawn check failed at {}", pos, t);
            }
        }
    }

    private static SpawnVerdict.Tone toneOf(int ordinal) {
        SpawnVerdict.Tone[] tones = SpawnVerdict.Tone.values();
        return ordinal >= 0 && ordinal < tones.length ? tones[ordinal] : SpawnVerdict.Tone.BLOCKED_ALWAYS;
    }

    private static String label(SpawnVerdict.Tone tone) {
        return switch (tone) {
            case CAN_SPAWN -> "can spawn";
            case BLOCKED_NOW -> "blocked right now";
            case BLOCKED_ALWAYS -> "cannot spawn";
        };
    }

    private static ChatFormatting colour(SpawnVerdict.Tone tone) {
        return switch (tone) {
            case CAN_SPAWN -> ChatFormatting.RED;      // A spawn here is the danger, not the success.
            case BLOCKED_NOW -> ChatFormatting.YELLOW;
            case BLOCKED_ALWAYS -> ChatFormatting.GREEN;
        };
    }
}
