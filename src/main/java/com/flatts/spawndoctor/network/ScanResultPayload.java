package com.flatts.spawndoctor.network;

import com.flatts.spawndoctor.SpawnDoctor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A graded box, sent back to the client for rendering.
 *
 * <p>The grid is a dense {@code byte[]} of {@link com.flatts.spawndoctor.audit.SpawnGrade}
 * ids rather than a list of positions. Dense wins here: at a 12-block radius that is
 * ~8 KB flat, whereas a sparse list costs 9 bytes per marked block and a cave floor
 * marks most of them. It also compresses well, which a position list does not.
 */
public record ScanResultPayload(BlockPos center, int radiusXZ, int radiusY, byte[] grid) implements CustomPacketPayload {

    public static final Type<ScanResultPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath(SpawnDoctor.MOD_ID, "scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanResultPayload> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ScanResultPayload::center,
        ByteBufCodecs.VAR_INT, ScanResultPayload::radiusXZ,
        ByteBufCodecs.VAR_INT, ScanResultPayload::radiusY,
        ByteBufCodecs.BYTE_ARRAY, ScanResultPayload::grid,
        ScanResultPayload::new);

    public int spanXZ() {
        return this.radiusXZ * 2 + 1;
    }

    public int spanY() {
        return this.radiusY * 2 + 1;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
