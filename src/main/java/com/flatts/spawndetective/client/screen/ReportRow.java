package com.flatts.spawndetective.client.screen;

import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.RuleResult;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * One line in the report screen's scrolling body.
 *
 * <p>The screen flattens the whole nested report into a single list of rows and
 * then draws a window into it. That is what makes scrolling, collapsing and
 * hit-testing trivial: everything is index arithmetic on one list, rather than a
 * tree walk that has to be repeated identically in the render pass and the click
 * handler - which is exactly where these screens usually develop off-by-one bugs.
 */
public sealed interface ReportRow {

    /** Pixel height of every row. Uniform so scrolling is plain index maths. */
    int HEIGHT = 12;

    /** A collapsible section header. */
    record Section(
        String key,
        Component title,
        Component summary,
        boolean expanded,
        boolean ok
    ) implements ReportRow {}

    /** One mob type inside an expanded category. */
    record Mob(
        Component name,
        Component chance,
        Component status,
        boolean ok,
        AuditReport.Candidate candidate
    ) implements ReportRow {}

    /** One rule verdict inside an expanded section. */
    record Rule(RuleResult result, int indent) implements ReportRow {}

    /** A search hit the reader can click to focus. */
    record Suggestion(EntityType<?> type, Component name, Component id) implements ReportRow {}

    /** Blank spacing between sections. */
    record Spacer() implements ReportRow {}

    /** The tooltip to show when this row is hovered, or null for none. */
    default @Nullable Component tooltip() {
        return null;
    }

    /** The section key this row toggles when clicked, or null if it is not clickable. */
    default @Nullable String toggles() {
        return this instanceof Section section ? section.key() : null;
    }
}
