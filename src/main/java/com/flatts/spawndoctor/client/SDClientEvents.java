package com.flatts.spawndoctor.client;

import com.flatts.spawndoctor.SpawnDoctor;
import com.flatts.spawndoctor.network.ScanRequestPayload;
import com.flatts.spawndoctor.network.ScanResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client-side driving of the overlay: when to ask the server for a fresh scan, and
 * when to throw the old one away.
 */
@EventBusSubscriber(modid = SpawnDoctor.MOD_ID, value = Dist.CLIENT)
public final class SDClientEvents {

    /** Blocks the player may drift from the scan centre before a re-scan is worth it. */
    private static final int RECENTER_DISTANCE = 6;

    /**
     * Minimum ticks between requests. The scan is real server work; without a floor
     * here, a player walking a straight line would ask for one every few ticks.
     */
    private static final int MIN_REQUEST_INTERVAL = 20;

    private static final int RADIUS_XZ = 16;
    private static final int RADIUS_Y = 6;

    private static int cooldown;

    private SDClientEvents() {
    }

    @SubscribeEvent
    public static void onRenderGeometry(SubmitCustomGeometryEvent event) {
        SpawnOverlayRenderer.onSubmitGeometry(event);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ClientScanState.enabled()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        BlockPos here = player.blockPosition();
        ScanResultPayload current = ClientScanState.current();
        if (current != null && current.center().distSqr(here) < (double) RECENTER_DISTANCE * RECENTER_DISTANCE) {
            return; // Still inside the scanned box; re-centring now would only make it shimmer.
        }

        requestScan(here);
    }

    /** Ask the server to grade the box around a position. */
    public static void requestScan(BlockPos center) {
        cooldown = MIN_REQUEST_INTERVAL;
        ClientPacketDistributor.sendToServer(new ScanRequestPayload(center, RADIUS_XZ, RADIUS_Y));
    }

    /** Leaving a world must drop the grid - its coordinates mean nothing in the next one. */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientScanState.clear();
    }
}
