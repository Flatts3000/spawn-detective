package com.flatts.spawndoctor.network;

import com.flatts.spawndoctor.SpawnDoctor;
import com.flatts.spawndoctor.audit.PositionReport;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent when the probe is used: open the screen for this position.
 *
 * <p>Carries only the position-level findings. The per-mob answer arrives
 * separately via {@link MobAuditPayloads}, once a mob is chosen - or immediately,
 * when the reader already has one selected from last time.
 */
public record ShowPositionPayload(PositionReport report) implements CustomPacketPayload {

    public static final Type<ShowPositionPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(SpawnDoctor.MOD_ID, "show_position"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShowPositionPayload> CODEC =
        PositionReport.STREAM_CODEC.map(ShowPositionPayload::new, ShowPositionPayload::report);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
