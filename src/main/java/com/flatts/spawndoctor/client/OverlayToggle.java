package com.flatts.spawndoctor.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The overlay's on/off entry point, kept in one small client class so the item can
 * call it from inside an {@code isClientSide} branch without dragging the whole
 * render stack onto a dedicated server's classpath.
 */
@OnlyIn(Dist.CLIENT)
public final class OverlayToggle {

    private OverlayToggle() {
    }

    public static void toggle(Player player) {
        boolean on = ClientScanState.toggle();
        if (on) {
            // Ask immediately; waiting for the tick loop would make the toggle feel dead.
            SDClientEvents.requestScan(player.blockPosition());
        }
        player.sendSystemMessage(Component.literal(on
                ? "Spawn overlay ON - red spawns now, yellow is blocked only for now"
                : "Spawn overlay OFF")
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    }
}
