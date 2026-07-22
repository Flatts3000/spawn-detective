package com.flatts.spawndetective.network;

import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.SpawnAuditor;
import com.flatts.spawndetective.client.screen.SpawnReportScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Network registration and the two handlers. */
public final class SDPayloads {

    /** Largest radius a client may request. */
    private static final int MAX_RADIUS_XZ = 24;
    private static final int MAX_RADIUS_Y = 12;

    /**
     * How far from the requested centre a player may be. A scan is server work done
     * on request, so it has to be bounded to the requester's own surroundings -
     * otherwise the packet is a free "tell me the state of anywhere in the world"
     * probe, and a cheap way to make the server do expensive work.
     */
    private static final double MAX_CENTER_DISTANCE_SQR = 64.0 * 64.0;

    private SDPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(ShowPositionPayload.TYPE, ShowPositionPayload.CODEC,
            SDPayloads::onShowPosition);
        registrar.playToServer(MobAuditPayloads.Request.TYPE, MobAuditPayloads.Request.CODEC,
            SDPayloads::onMobAuditRequest);
        registrar.playToClient(MobAuditPayloads.Result.TYPE, MobAuditPayloads.Result.CODEC,
            SDPayloads::onMobAuditResult);
    }

    private static void onShowPosition(ShowPositionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> SpawnReportScreen.open(payload.report()));
    }

    /**
     * Audit one mob type for the screen's picker.
     *
     * <p>Bounded to the requester's surroundings for the same reason the area scan
     * is: it is server work done on demand, and an unbounded position argument would
     * make it a free probe of anywhere in the world.
     */
    private static void onMobAuditRequest(MobAuditPayloads.Request payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = payload.pos();
            if (player.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) > MAX_CENTER_DISTANCE_SQR) {
                return;
            }
            // Record the choice on the probe itself. Costs no extra packet - the
            // request already tells us - and it is what lets the selection survive a
            // relog and lets Jade read the mob off the held item.
            rememberOnProbe(player, payload.entityType());

            AuditReport.Candidate candidate = SpawnAuditor.auditType(level, pos, payload.entityType());
            PacketDistributor.sendToPlayer(player, new MobAuditPayloads.Result(pos, candidate));
        });
    }

    /** Write the chosen mob onto whichever hand is holding a probe. */
    private static void rememberOnProbe(ServerPlayer player, net.minecraft.world.entity.EntityType<?> type) {
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof com.flatts.spawndetective.item.SpawnProbeItem) {
                held.set(com.flatts.spawndetective.registry.SDDataComponents.selectedMob(), type);
            }
        }
    }

    private static void onMobAuditResult(MobAuditPayloads.Result payload, IPayloadContext context) {
        context.enqueueWork(() -> SpawnReportScreen.acceptAnswer(payload.pos(), payload.candidate()));
    }
}
