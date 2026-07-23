package com.flatts.spawndetective.network;

import com.flatts.spawndetective.SpawnDetective;
import com.flatts.spawndetective.audit.AuditReport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * The "why won't <i>this</i> mob spawn here" round trip, for the screen's mob picker.
 *
 * <p>Separate from {@link ShowReportPayload} because it answers a different
 * question. The position report covers what the biome offers; this covers any mob
 * in the registry, including ones the biome would never roll - which is exactly
 * the case people ask about, since "why won't X spawn" is usually asked precisely
 * when X is missing from the list.
 */
public final class MobAuditPayloads {

    private MobAuditPayloads() {
    }

    /** Client -> server: audit one mob type at a position. */
    // 'entityType' rather than 'type': a record component named 'type' would clash
    // with CustomPacketPayload.type(), which every payload must implement.
    public record Request(BlockPos pos, EntityType<?> entityType) implements CustomPacketPayload {

        public static final Type<Request> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpawnDetective.MOD_ID, "mob_audit_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Request::pos,
            ByteBufCodecs.registry(Registries.ENTITY_TYPE), Request::entityType,
            Request::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: that mob's walk through the per-type gates. */
    public record Result(BlockPos pos, AuditReport.Candidate candidate) implements CustomPacketPayload {

        public static final Type<Result> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpawnDetective.MOD_ID, "mob_audit_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Result::pos,
            AuditReport.Candidate.STREAM_CODEC, Result::candidate,
            Result::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
