package com.flatts.spawndoctor.command;

import com.flatts.spawndoctor.SpawnDoctor;
import com.flatts.spawndoctor.audit.AuditReport;
import com.flatts.spawndoctor.audit.RuleResult;
import com.flatts.spawndoctor.audit.SpawnAuditor;
import com.flatts.spawndoctor.item.SpawnProbeItem;
import com.flatts.spawndoctor.report.ChatReport;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The command surface:
 *
 * <pre>
 *   /spawndoctor                        audit where the caller is standing
 *   /spawndoctor at &lt;pos&gt;               audit a position
 *   /spawndoctor for &lt;entity&gt;           audit one mob type here
 *   /spawndoctor at &lt;pos&gt; for &lt;entity&gt;  audit one mob type at a position
 * </pre>
 *
 * <p>The {@code for} form matters because it skips the biome spawn list: "why won't
 * zombies spawn here" is answerable even in a biome whose list has no zombies, and
 * that case is exactly when the question gets asked.
 *
 * <p>Op-gated (gamemaster level) because the report exposes server-wide mob cap
 * state, which an ordinary player on a multiplayer server should not be able to
 * poll at will.
 */
@EventBusSubscriber(modid = SpawnDoctor.MOD_ID)
public final class SpawnDoctorCommand {

    private SpawnDoctorCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext build) {
        dispatcher.register(Commands.literal("spawndoctor")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .executes(ctx -> auditAll(ctx.getSource(), callerPos(ctx)))
            .then(forArgument(build, SpawnDoctorCommand::callerPos))
            .then(Commands.literal("at")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> auditAll(ctx.getSource(), argPos(ctx)))
                    .then(forArgument(build, SpawnDoctorCommand::argPos)))));
    }

    /**
     * The shared {@code for <entity>} branch. Both the bare and the {@code at} form
     * need it, and they differ only in where the position comes from.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> forArgument(
        CommandBuildContext build, PositionSource position
    ) {
        return Commands.literal("for")
            .then(Commands.argument("entity", ResourceArgument.resource(build, Registries.ENTITY_TYPE))
                .executes(ctx -> auditOne(
                    ctx.getSource(),
                    position.get(ctx),
                    ResourceArgument.getSummonableEntityType(ctx, "entity").value())));
    }

    @FunctionalInterface
    private interface PositionSource {
        BlockPos get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static BlockPos callerPos(CommandContext<CommandSourceStack> ctx) {
        return BlockPos.containing(ctx.getSource().getPosition());
    }

    private static BlockPos argPos(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return BlockPosArgument.getLoadedBlockPos(ctx, "pos");
    }

    private static int auditAll(CommandSourceStack source, BlockPos pos) {
        SpawnProbeItem.report(source.getLevel(), pos, sink(source));
        return 1;
    }

    private static int auditOne(CommandSourceStack source, BlockPos pos, EntityType<?> type) {
        ServerLevel level = source.getLevel();
        AuditReport.Candidate candidate = SpawnAuditor.auditType(level, pos, type);
        // The world rules travel with the candidate: a mob's own gates can all pass
        // while a shut world gate stops it anyway, and a verdict that ignored them
        // would contradict the rules printed beneath it.
        List<RuleResult> world = SpawnAuditor.auditPosition(level, pos).world();
        for (Component line : ChatReport.renderSingle(level, pos, world, candidate)) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    /** sendSuccess, not a player message: this must work from the console and command blocks. */
    private static Consumer<Component> sink(CommandSourceStack source) {
        return line -> source.sendSuccess(() -> line, false);
    }
}
