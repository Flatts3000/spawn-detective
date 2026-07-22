package com.flatts.spawndoctor.item;

import com.flatts.spawndoctor.audit.AuditReport;
import com.flatts.spawndoctor.audit.SpawnAuditor;
import com.flatts.spawndoctor.report.ChatReport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * The Spawn Probe - point it at a block, get the answer.
 *
 * <p>Right-click audits the block <i>above</i> the one clicked, because that is
 * where a mob would stand; sneak-right-click audits the clicked block itself, for
 * the water and lava cases. The audit runs entirely on the server (it reads the
 * live spawn state and fires NeoForge's spawn events, neither of which exists
 * client-side) and the result is written to the player's chat.
 */
public class SpawnProbeItem extends Item {

    public SpawnProbeItem(Properties properties) {
        super(properties);
    }

    /**
     * Right-click in the air toggles the live overlay.
     *
     * <p>The toggle is client state, so it happens on the client half of the call
     * only. Referencing the client class inside an {@code isClientSide} branch is
     * safe on a dedicated server: the branch never runs there, so the JVM never
     * loads the class.
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            com.flatts.spawndoctor.client.OverlayToggle.toggle(player);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS; // Let the server decide; swing the arm.
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        BlockPos target = context.isSecondaryUseActive()
            ? context.getClickedPos()
            : context.getClickedPos().above();

        report(level, target, player::sendSystemMessage);
        return InteractionResult.SUCCESS;
    }

    /**
     * Run the audit and hand each rendered line to a sink.
     *
     * <p>Takes a sink rather than a player so the same path serves the probe, the
     * command from a player, and the command from the server console or a command
     * block - none of which have a player to message.
     */
    public static void report(ServerLevel level, BlockPos pos, Consumer<Component> sink) {
        AuditReport report = SpawnAuditor.audit(level, pos);
        for (Component line : ChatReport.render(report)) {
            sink.accept(line);
        }
    }
}
