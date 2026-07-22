package com.flatts.spawndoctor.network;

import com.flatts.spawndoctor.audit.AreaScanner;
import com.flatts.spawndoctor.client.ClientScanState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
        registrar.playToServer(ScanRequestPayload.TYPE, ScanRequestPayload.CODEC, SDPayloads::onScanRequest);
        registrar.playToClient(ScanResultPayload.TYPE, ScanResultPayload.CODEC, SDPayloads::onScanResult);
    }

    private static void onScanRequest(ScanRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        // enqueueWork: the handler runs on the network thread, and the scan reads
        // block and light state that only the server thread may touch.
        context.enqueueWork(() -> {
            ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            if (level == null) {
                return;
            }

            BlockPos center = payload.center();
            if (player.distanceToSqr(center.getX(), center.getY(), center.getZ()) > MAX_CENTER_DISTANCE_SQR) {
                return; // Not near what they asked about; ignore rather than serve it.
            }

            int radiusXZ = Mth.clamp(payload.radiusXZ(), 1, MAX_RADIUS_XZ);
            int radiusY = Mth.clamp(payload.radiusY(), 1, MAX_RADIUS_Y);
            if (volume(radiusXZ, radiusY) > AreaScanner.MAX_VOLUME) {
                radiusXZ = MAX_RADIUS_XZ / 2;
                radiusY = MAX_RADIUS_Y / 2;
            }

            byte[] grid = AreaScanner.scan(level, center, radiusXZ, radiusY);
            PacketDistributor.sendToPlayer(player, new ScanResultPayload(center, radiusXZ, radiusY, grid));
        });
    }

    /**
     * Clientbound handlers registered with {@code playToClient} are only ever invoked
     * on a client, and the JVM does not load {@link ClientScanState} until this body
     * runs - so a dedicated server never touches the client class.
     */
    private static void onScanResult(ScanResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientScanState.accept(payload));
    }

    private static int volume(int radiusXZ, int radiusY) {
        int spanXZ = radiusXZ * 2 + 1;
        return spanXZ * spanXZ * (radiusY * 2 + 1);
    }
}
