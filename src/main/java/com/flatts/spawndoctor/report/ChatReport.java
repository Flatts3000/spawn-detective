package com.flatts.spawndoctor.report;

import com.flatts.spawndoctor.audit.AuditReport;
import com.flatts.spawndoctor.audit.RuleResult;
import com.flatts.spawndoctor.audit.SpawnAuditor;
import com.flatts.spawndoctor.audit.SpawnRule;
import com.flatts.spawndoctor.audit.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * Renders an {@link AuditReport} as chat lines.
 *
 * <p>Chat is a narrow surface, so the shape is: one headline answer, then only
 * what failed. A mob that can spawn gets one green line; a mob that cannot gets
 * its first blocker inline and the full rule walk on hover. Nobody wants to read
 * twenty passing gates to find the one that failed.
 */
public final class ChatReport {

    private ChatReport() {
    }

    public static List<Component> render(AuditReport report) {
        List<Component> lines = new ArrayList<>();

        lines.add(Component.literal("── Spawn Doctor ──").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(" " + report.pos().toShortString()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" in " + report.dimension()).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" (" + report.biome() + ")").withStyle(ChatFormatting.DARK_GRAY)));

        lines.add(headline(report));

        // World and chunk gates: only the failures, unless everything passed.
        List<RuleResult> worldFailures = report.world().stream()
            .filter(r -> !r.verdict().permits())
            .toList();
        if (worldFailures.isEmpty()) {
            lines.add(indent(Component.literal("World & chunk: all " + report.world().size() + " gates pass")
                .withStyle(ChatFormatting.DARK_GREEN)));
        } else {
            for (RuleResult result : worldFailures) {
                lines.add(indent(ruleLine(result)));
            }
        }

        for (AuditReport.Category category : report.categories()) {
            lines.addAll(renderCategory(category));
        }

        return lines;
    }

    /**
     * The full rule walk for a single mob type, for {@code /spawndoctor for <entity>}.
     *
     * <p>Unlike the position report this prints every rule, passing ones included:
     * when someone asks about one specific mob they are usually checking their
     * reasoning, and the gates that <i>did</i> pass are half of that answer.
     */
    public static List<Component> renderSingle(ServerLevel level, BlockPos pos, AuditReport.Candidate candidate) {
        List<Component> lines = new ArrayList<>();
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).toString();

        lines.add(Component.literal("── Spawn Doctor ──").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(" " + name).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" at " + pos.toShortString()).withStyle(ChatFormatting.GRAY)));

        Optional<RuleResult> blocker = candidate.blocker();
        if (blocker.isEmpty()) {
            lines.add(Component.literal("This mob CAN spawn here")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        } else {
            lines.add(Component.literal("Blocked by " + blocker.get().rule().title())
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        }

        for (RuleResult result : candidate.rules()) {
            lines.add(indent(ruleLine(result)));
        }
        lines.add(indent(Component.literal(SpawnAuditor.describeLight(level, pos))
            .withStyle(ChatFormatting.DARK_GRAY)));

        blocker.map(RuleResult::rule).map(SpawnRule::remedy).ifPresent(remedy ->
            lines.add(Component.literal(remedy).withStyle(ChatFormatting.YELLOW)));

        return lines;
    }

    /** The one-line answer to the question the player actually asked. */
    private static Component headline(AuditReport report) {
        if (report.anythingCanSpawn()) {
            long viable = report.categories().stream()
                .flatMap(c -> c.candidates().stream())
                .filter(AuditReport.Candidate::viable)
                .count();
            return Component.literal("Mobs CAN spawn here")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal(" - " + viable + " mob type(s) pass every gate")
                    .withStyle(ChatFormatting.GRAY));
        }

        Optional<RuleResult> worldBlocker = report.worldBlocker();
        MutableComponent line = Component.literal("Nothing can spawn here")
            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        if (worldBlocker.isPresent()) {
            RuleResult blocker = worldBlocker.get();
            return line.append(Component.literal(" - " + blocker.rule().title() + ": " + blocker.detail())
                .withStyle(ChatFormatting.GRAY));
        }
        return line.append(Component.literal(" - see the per-mob reasons below").withStyle(ChatFormatting.GRAY));
    }

    private static List<Component> renderCategory(AuditReport.Category category) {
        List<Component> lines = new ArrayList<>();
        boolean anyViable = category.anyViable();

        // A category with no biome entries at all is normal (no water creatures in a
        // desert) and not worth a line unless it is the one the player asked about.
        if (category.candidates().isEmpty() && category.gatesOpen()) {
            return lines;
        }

        MutableComponent header = Component.literal(category.category().getName().toUpperCase())
            .withStyle(anyViable ? ChatFormatting.GREEN : ChatFormatting.RED);
        header.append(Component.literal(" (" + category.candidates().size() + " possible mob types)")
            .withStyle(ChatFormatting.DARK_GRAY));
        lines.add(header);

        for (RuleResult result : category.rules()) {
            if (!result.verdict().permits()) {
                lines.add(indent(ruleLine(result)));
            }
        }
        if (!category.gatesOpen()) {
            return lines; // Caps are shut; per-mob detail would be noise.
        }

        for (AuditReport.Candidate candidate : category.candidates()) {
            lines.add(indent(candidateLine(candidate)));
        }
        return lines;
    }

    private static Component candidateLine(AuditReport.Candidate candidate) {
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).toString();
        MutableComponent line = Component.literal(String.format("%-28s ", name))
            .withStyle(ChatFormatting.WHITE);
        line.append(Component.literal(String.format("%3.0f%% ", candidate.rollChance()))
            .withStyle(ChatFormatting.DARK_GRAY));

        Optional<RuleResult> blocker = candidate.blocker();
        if (blocker.isEmpty()) {
            line.append(Component.literal("can spawn").withStyle(ChatFormatting.GREEN));
        } else {
            RuleResult result = blocker.get();
            line.append(Component.literal(result.rule().title()).withStyle(ChatFormatting.RED))
                .append(Component.literal(" - " + result.detail()).withStyle(ChatFormatting.GRAY));
        }

        return line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(fullWalk(candidate))));
    }

    /** The complete rule walk for one mob, shown on hover. */
    private static Component fullWalk(AuditReport.Candidate candidate) {
        MutableComponent tooltip = Component.literal(
                BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).toString())
            .withStyle(ChatFormatting.AQUA)
            .append(Component.literal(String.format("  weight %d of %d",
                candidate.weight(), candidate.totalWeight())).withStyle(ChatFormatting.DARK_GRAY));

        for (RuleResult result : candidate.rules()) {
            tooltip.append(Component.literal("\n")).append(ruleLine(result));
        }

        Optional<RuleResult> blocker = candidate.blocker();
        blocker.map(RuleResult::rule).map(SpawnRule::remedy).ifPresent(remedy ->
            tooltip.append(Component.literal("\n\n" + remedy).withStyle(ChatFormatting.YELLOW)));

        return tooltip;
    }

    private static Component ruleLine(RuleResult result) {
        Verdict verdict = result.verdict();
        return Component.literal(String.format("%-9s", verdict.label())).withStyle(verdict.color())
            .append(Component.literal(result.rule().title()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(": " + result.detail()).withStyle(ChatFormatting.GRAY));
    }

    private static Component indent(Component inner) {
        return Component.literal("  ").append(inner);
    }
}
