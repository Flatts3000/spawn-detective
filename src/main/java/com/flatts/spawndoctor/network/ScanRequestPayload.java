package com.flatts.spawndoctor.network;

import com.flatts.spawndoctor.SpawnDoctor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client asks the server to grade the blocks around a position.
 *
 * <p>The client sends the centre it wants rather than the server using the player's
 * position, so the overlay stays anchored while the player moves within it - a grid
 * that re-centres every tick shimmers and is unusable for aiming at one block. The
 * server still clamps the request; see {@link SDPayloads}.
 */
public record ScanRequestPayload(BlockPos center, int radiusXZ, int radiusY) implements CustomPacketPayload {

    public static final Type<ScanRequestPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(SpawnDoctor.MOD_ID, "scan_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanRequestPayload> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ScanRequestPayload::center,
        ByteBufCodecs.VAR_INT, ScanRequestPayload::radiusXZ,
        ByteBufCodecs.VAR_INT, ScanRequestPayload::radiusY,
        ScanRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
