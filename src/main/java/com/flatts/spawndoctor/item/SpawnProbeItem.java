package com.flatts.spawndoctor.item;

import com.flatts.spawndoctor.audit.AuditReport;
import com.flatts.spawndoctor.audit.SpawnAuditor;
import com.flatts.spawndoctor.network.ShowPositionPayload;
import com.flatts.spawndoctor.registry.SDDataComponents;
import com.flatts.spawndoctor.report.ChatReport;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Spawn Probe: anchor a block, walk away, read the answer.
 *
 * <p>The two-step gesture exists because of a measurement problem. To point at a
 * block you must stand beside it, and standing there breaks two real spawn rules -
 * the 24-block player bubble, and the obstruction check, since your own body
 * occupies the space a mob needs. An earlier version papered over that by auditing
 * "as if you had stepped away", which made every headline an assumption.
 *
 * <p>Anchoring fixes it honestly: mark the block, walk off, and the reading is
 * taken with you genuinely at a distance. Nothing is discounted. If you are still
 * too close the report says so with your real distance - which is itself the
 * useful answer, because walking further is the fix.
 */
public class SpawnProbeItem extends Item {

    public SpawnProbeItem(Properties properties) {
        super(properties);
    }

    /**
     * Sneak-right-click a block: anchor the space above it, where a mob would stand.
     * Plain right-click: read the anchor.
     *
     * <p>With nothing anchored, a plain click asks you to anchor rather than quietly
     * reading whatever you clicked. That fallback existed and was worse than
     * useless: it produced a real report about a block you had not chosen, standing
     * right next to it, which is exactly the reading the anchor gesture exists to
     * avoid. One gesture doing two different things silently is how a tool teaches
     * people to distrust it.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS; // Let the server decide; swing the arm.
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = context.getItemInHand();
        // The space above the clicked block, because that is where a mob stands -
        // clicking a floor should mark the room, not the floor.
        BlockPos clicked = context.getClickedPos().above();

        if (context.isSecondaryUseActive()) {
            stack.set(SDDataComponents.anchor(), new GlobalPos(level.dimension(), clicked));
            player.sendSystemMessage(Component.translatable("spawndoctor.anchor.set", clicked.toShortString())
                .withStyle(ChatFormatting.AQUA));
            return InteractionResult.SUCCESS;
        }

        read(level, player, stack);
        return InteractionResult.SUCCESS;
    }

    /**
     * In the air: plain right-click reads the anchor, sneak clears it.
     *
     * <p>Reading from the air is the point of the design - it is what you do once
     * you have walked away from the block you anchored.
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = player.getItemInHand(hand);

        if (player.isSecondaryUseActive()) {
            if (stack.has(SDDataComponents.anchor())) {
                stack.remove(SDDataComponents.anchor());
                serverPlayer.sendSystemMessage(
                    Component.translatable("spawndoctor.anchor.cleared").withStyle(ChatFormatting.GRAY));
            }
            return InteractionResult.SUCCESS;
        }

        read(serverLevel, serverPlayer, stack);
        return InteractionResult.SUCCESS;
    }

    /** Read the anchored block, or explain how to anchor one. */
    private static void read(ServerLevel level, ServerPlayer player, ItemStack stack) {
        GlobalPos anchor = stack.get(SDDataComponents.anchor());
        if (anchor == null) {
            player.sendSystemMessage(
                Component.translatable("spawndoctor.anchor.none").withStyle(ChatFormatting.YELLOW));
            return;
        }
        // Coordinates mean different things in different dimensions, and auditing the
        // same numbers in the wrong one would be a confident wrong answer.
        if (!anchor.dimension().equals(level.dimension())) {
            player.sendSystemMessage(Component.translatable("spawndoctor.anchor.elsewhere",
                anchor.dimension().identifier().toString()).withStyle(ChatFormatting.YELLOW));
            return;
        }
        open(level, player, anchor.pos());
    }

    /**
     * Run the position half of the audit and open the screen.
     *
     * <p>Nobody is discounted: anchoring means the player really is elsewhere, so
     * the player-distance and obstruction rules are reported as measured.
     */
    private static void open(ServerLevel level, ServerPlayer player, BlockPos pos) {
        PacketDistributor.sendToPlayer(player,
            new ShowPositionPayload(SpawnAuditor.auditPosition(level, pos)));
    }

    /**
     * The gesture, on the item itself.
     *
     * <p>A two-step gesture that is only explained in chat is a two-step gesture
     * people get wrong. The tooltip is where they look first.
     */
    @Override
    public void appendHoverText(
        ItemStack stack, TooltipContext context, TooltipDisplay display,
        Consumer<Component> lines, TooltipFlag flag
    ) {
        GlobalPos anchor = stack.get(SDDataComponents.anchor());
        if (anchor == null) {
            lines.accept(Component.translatable("spawndoctor.probe.tip.anchor").withStyle(ChatFormatting.GRAY));
            return;
        }
        lines.accept(Component.translatable("spawndoctor.probe.tip.anchored", anchor.pos().toShortString())
            .withStyle(ChatFormatting.AQUA));
        lines.accept(Component.translatable("spawndoctor.probe.tip.read").withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * Run the full sweep and hand each rendered line to a sink.
     *
     * <p>Kept for the command, which must also serve the server console and command
     * blocks - neither of which has a screen to open.
     */
    public static void report(ServerLevel level, BlockPos pos, Consumer<Component> sink) {
        AuditReport report = SpawnAuditor.audit(level, pos);
        for (Component line : ChatReport.render(report)) {
            sink.accept(line);
        }
    }
}
