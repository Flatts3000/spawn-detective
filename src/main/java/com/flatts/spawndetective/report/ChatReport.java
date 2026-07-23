package com.flatts.spawndetective.report;

import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.RuleResult;
import com.flatts.spawndetective.audit.SpawnAuditor;
import com.flatts.spawndetective.audit.SpawnVerdict;
import com.flatts.spawndetective.audit.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * The command's text output, for the console and command blocks - anywhere without
 * a screen to open.
 *
 * <p>Every verdict here comes from {@link SpawnVerdict}, the same resolver the
 * screen and the Jade tooltip use. It did not always: this surface used to compute
 * its own aggregate headline over every mob at once, which produced sentences like
 * "slime +6 more - needs sky" - several findings averaged into a claim true of none
 * of them. That model was removed from the screen and left running here, so for a
 * while the same block got a good answer through one surface and a worse one
 * through another. One resolver, or they drift.
 *
 * <p>The sweep itself is worth keeping in a way the screen deliberately is not: a
 * screen answers about one mob you named, while "tell me everything that can spawn
 * on this block" is a genuine question that only a command has room to answer.
 */
public final class ChatReport {

    private ChatReport() {
    }

    /** The whole-position sweep: every mob the biome offers, each judged in full. */
    public static List<Component> render(AuditReport report) {
        List<Component> lines = new ArrayList<>();

        lines.add(Component.literal("Spawn Detective ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(report.pos().toShortString()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  " + report.biome()).withStyle(ChatFormatting.DARK_GRAY)));

        // Only the failures. Twenty passing gates between the reader and the one that
        // failed is how a report becomes something people stop reading.
        for (RuleResult result : report.world()) {
            if (!result.verdict().permits()) {
                lines.add(indent(ruleLine(result)));
            }
        }

        for (AuditReport.Category category : report.categories()) {
            if (!category.relevant()) {
                continue; // The biome offers nothing here; saying so answers no question.
            }
            lines.addAll(renderCategory(report, category));
        }

        if (lines.size() == 1) {
            lines.add(indent(Component.literal("this biome offers no mobs at this position")
                .withStyle(ChatFormatting.GRAY)));
        }
        return lines;
    }

    /** One mob, walked in full - what {@code /spawndetective for <entity>} prints. */
    public static List<Component> renderSingle(
        ServerLevel level, BlockPos pos, List<RuleResult> world, AuditReport.Candidate candidate
    ) {
        List<Component> lines = new ArrayList<>();
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).toString();
        SpawnVerdict verdict = SpawnVerdict.of(world, candidate);

        lines.add(Component.literal("Spawn Detective ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(name).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  at " + pos.toShortString()).withStyle(ChatFormatting.DARK_GRAY)));
        lines.add(verdictLine(verdict, name));

        // Unlike the sweep, this prints the passing gates too: someone asking about
        // one specific mob is usually checking their own reasoning, and what passed
        // is half of that answer.
        for (RuleResult result : world) {
            lines.add(indent(ruleLine(result)));
        }
        for (RuleResult result : candidate.rules()) {
            lines.add(indent(ruleLine(result)));
        }
        lines.add(indent(Component.literal(SpawnAuditor.describeLight(level, pos))
            .withStyle(ChatFormatting.DARK_GRAY)));

        if (verdict.blocker() != null && verdict.blocker().effectiveRemedy() != null) {
            lines.add(Component.literal("-> " + verdict.blocker().effectiveRemedy())
                .withStyle(ChatFormatting.YELLOW));
        }
        return lines;
    }

    private static List<Component> renderCategory(AuditReport report, AuditReport.Category category) {
        List<Component> lines = new ArrayList<>();
        List<Component> mobs = new ArrayList<>();
        int viable = 0;

        for (AuditReport.Candidate candidate : category.candidates()) {
            SpawnVerdict verdict = SpawnVerdict.of(report.world(), candidate);
            if (verdict.canSpawn()) {
                viable++;
            }
            mobs.add(indent(candidateLine(candidate, verdict)));
        }

        lines.add(Component.literal(category.category().getName().toUpperCase(Locale.ROOT))
            .withStyle(viable > 0 ? ChatFormatting.GREEN : ChatFormatting.RED)
            .append(Component.literal("  " + viable + " of " + category.candidates().size() + " can spawn")
                .withStyle(ChatFormatting.DARK_GRAY)));

        // A shut cap stops every mob under it for one reason; repeating that reason on
        // each of them would bury it in its own copies.
        for (RuleResult result : category.rules()) {
            if (!result.verdict().permits()) {
                lines.add(indent(ruleLine(result)));
            }
        }
        if (category.gatesOpen()) {
            lines.addAll(mobs);
        }
        return lines;
    }

    private static Component candidateLine(AuditReport.Candidate candidate, SpawnVerdict verdict) {
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).getPath();
        MutableComponent line = Component.literal(String.format(Locale.ROOT, "%-22s", name))
            .withStyle(ChatFormatting.WHITE)
            .append(Component.literal(String.format(Locale.ROOT, "%3.0f%%  ", candidate.rollChance()))
                .withStyle(ChatFormatting.DARK_GRAY));

        RuleResult blocker = verdict.blocker();
        if (blocker == null) {
            line.append(Component.literal("can spawn").withStyle(ChatFormatting.GREEN));
        } else {
            line.append(Component.literal(blocker.rule().title() + ": " + blocker.summary())
                .withStyle(verdict.tone() == SpawnVerdict.Tone.BLOCKED_NOW
                    ? ChatFormatting.YELLOW : ChatFormatting.RED));
        }

        return line.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, walk(candidate))));
    }

    private static Component verdictLine(SpawnVerdict verdict, String name) {
        RuleResult blocker = verdict.blocker();
        return switch (verdict.tone()) {
            case CAN_SPAWN -> Component.literal(name + " can spawn here")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
            case BLOCKED_NOW -> Component.literal(name + " is blocked right now")
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal("  " + blocker.rule().title() + ": " + blocker.summary())
                    .withStyle(ChatFormatting.GRAY));
            case BLOCKED_ALWAYS -> Component.literal(name + " cannot spawn here")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal("  " + blocker.rule().title() + ": " + blocker.summary())
                    .withStyle(ChatFormatting.GRAY));
        };
    }

    /** The full rule walk for one mob, on hover. */
    private static Component walk(AuditReport.Candidate candidate) {
        MutableComponent tooltip = Component.literal(
                BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).toString())
            .withStyle(ChatFormatting.AQUA);
        for (RuleResult result : candidate.rules()) {
            tooltip.append(Component.literal("\n")).append(ruleLine(result));
        }
        return tooltip;
    }

    private static Component ruleLine(RuleResult result) {
        Verdict verdict = result.verdict();
        return Component.literal(String.format(Locale.ROOT, "%-9s", verdict.label())).withStyle(verdict.color())
            .append(Component.literal(result.rule().title()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(": " + result.summary()).withStyle(ChatFormatting.GRAY));
    }

    private static Component indent(Component inner) {
        return Component.literal("  ").append(inner);
    }
}
