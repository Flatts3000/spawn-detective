package com.flatts.spawndetective.client.screen;

import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.PositionReport;
import com.flatts.spawndetective.audit.RuleResult;
import com.flatts.spawndetective.audit.SpawnVerdict;
import com.flatts.spawndetective.audit.Verdict;
import com.flatts.spawndetective.network.MobAuditPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * One block, one mob, one answer.
 *
 * <p>The screen is built around a single question - "would <i>this mob</i> spawn
 * at <i>this block</i>?" - and everything else is arranged to serve it. That shape
 * was arrived at the hard way. An earlier version audited every mob the biome
 * offered and tried to summarise the lot in one banner, which produced verdicts
 * like "slime +6 more - needs sky": several different findings averaged into a
 * sentence true of nothing in particular. Averaging answers does not produce an
 * answer.
 *
 * <p>So:
 * <ul>
 *   <li><b>No mob, no verdict.</b> With nothing selected the screen asks, and
 *       offers the mobs this biome actually spawns as leads. It does not guess at
 *       what you meant, and the server does no per-mob work until you say.</li>
 *   <li><b>The selection is sticky.</b> Checking twenty blocks for zombies should
 *       cost one choice, not twenty.</li>
 *   <li><b>The banner is that mob's verdict</b>, in its own words, with the
 *       measurement that produced it.</li>
 * </ul>
 */
public class SpawnReportScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 232;
    private static final int PADDING = 8;
    private static final int HEADER_HEIGHT = 26;
    private static final int SEARCH_HEIGHT = 22;
    private static final int BANNER_HEIGHT = 58;

    /** Gap kept between a left-aligned label and the right-aligned value beside it. */
    private static final int COLUMN_GAP = 8;

    /** Search hits shown at once. Enough to choose from, few enough to scan. */
    private static final int MAX_SUGGESTIONS = 8;

    // A dark neutral panel: this is a dense data readout, and coloured verdict text
    // needs a low-chroma ground to read against.
    private static final int COLOR_PANEL = 0xF01A1B1E;
    private static final int COLOR_BORDER = 0xFF3A3D42;
    private static final int COLOR_HEADER = 0xFF232529;
    private static final int COLOR_DIVIDER = 0xFF3A3D42;
    private static final int COLOR_ROW_HOVER = 0x20FFFFFF;
    private static final int COLOR_SECTION_BG = 0xFF232529;

    private static final int TEXT_PRIMARY = 0xFFE8E8EA;
    private static final int TEXT_MUTED = 0xFF8A8D93;
    private static final int TEXT_FAINT = 0xFF5F636A;
    private static final int TEXT_GOOD = 0xFF5BD675;
    private static final int TEXT_BAD = 0xFFFF6B60;
    private static final int TEXT_WARN = 0xFFFFC93C;
    private static final int TEXT_ACCENT = 0xFF56C4D6;

    private static final String WORLD_SECTION = "world";
    private static final String WHY_SECTION = "why";

    private final PositionReport position;

    /** Sections the reader has opened. "Why" starts open; it is the answer. */
    private static final java.util.Set<String> EXPANDED = new java.util.HashSet<>(java.util.Set.of(WHY_SECTION));

    private @Nullable EditBox search;
    private List<ReportRow> rows = List.of();
    private int scroll;
    private int left;
    private int top;

    public SpawnReportScreen(PositionReport position) {
        super(Component.literal("Spawn Detective"));
        this.position = position;
        // Open whichever section holds the evidence. A blocked world gate outranks
        // the mob's own, since it is what vanilla rejects on first.
        if (!position.gatesOpen()) {
            EXPANDED.add(WORLD_SECTION);
        }
    }

    /**
     * Open the screen for a freshly probed position, and immediately ask about the
     * remembered mob so the answer is on screen by the time the reader looks at it.
     */
    public static void open(PositionReport position) {
        SpawnReportScreen screen = new SpawnReportScreen(position);
        Minecraft.getInstance().setScreen(screen);

        EntityType<?> remembered = MobSelection.selected();
        if (remembered != null) {
            // Always re-ask, even for the block we answered a moment ago. Probing is
            // an explicit request for a current reading, and the usual reason to probe
            // the same block twice is that you just changed something - placed a
            // torch, broke the floor. A brief "checking..." is a far smaller cost than
            // showing a verdict that was true before the change.
            MobSelection.invalidate();
            screen.ask(remembered);
        }
    }

    /** Called from the network handler when the server answers. */
    public static void acceptAnswer(BlockPos pos, AuditReport.Candidate candidate) {
        MobSelection.acceptAnswer(pos, candidate);
        if (Minecraft.getInstance().screen instanceof SpawnReportScreen screen) {
            screen.rebuildRows();
        }
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_WIDTH) / 2;
        this.top = (this.height - PANEL_HEIGHT) / 2;

        this.search = new EditBox(this.font, this.left + PADDING, this.top + HEADER_HEIGHT + 4,
            PANEL_WIDTH - PADDING * 2, 14, Component.literal("Find a mob"));
        this.search.setHint(Component.literal("search a mob, or pick one below"));
        this.search.setMaxLength(64);
        this.search.setResponder(text -> rebuildRows());
        addRenderableWidget(this.search);

        rebuildRows();
    }

    private void ask(EntityType<?> type) {
        MobSelection.select(type);
        ClientPacketDistributor.sendToServer(new MobAuditPayloads.Request(this.position.pos(), type));
        rebuildRows();
    }

    // ------------------------------------------------------------------- layout

    private void rebuildRows() {
        List<ReportRow> built = new ArrayList<>();
        String query = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);

        if (!query.isEmpty()) {
            // While searching, the hits are the only thing worth showing - anything
            // else competes with the choice being made.
            List<ReportRow> hits = suggestions(query);
            if (hits.isEmpty()) {
                built.add(new ReportRow.Section("no-hits",
                    Component.literal("No mob matches that"), Component.literal(""), false, false));
            } else {
                built.addAll(hits);
            }
            this.rows = List.copyOf(built);
            clampScroll();
            return;
        }

        AuditReport.Candidate answer = answer();
        if (answer == null) {
            // Nothing chosen yet: propose what this biome actually spawns here rather
            // than demanding a search term from someone who does not know what to type.
            built.add(new ReportRow.Section("pick",
                Component.literal("Mobs this biome spawns here"),
                Component.literal(this.position.suggestions().size() + " to choose from"), true, true));
            for (EntityType<?> type : this.position.suggestions()) {
                built.add(suggestionRow(type));
            }
            if (this.position.suggestions().isEmpty()) {
                built.add(new ReportRow.Rule(RuleResult.skipped(
                    com.flatts.spawndetective.audit.SpawnRule.BIOME_SPAWN_LIST,
                    "this biome offers no mobs here - search for one anyway"), 1));
            }
            built.add(new ReportRow.Spacer());
        } else {
            built.add(new ReportRow.Section(WHY_SECTION,
                Component.literal("This mob's gates"),
                Component.literal(answer.viable() ? "all pass" : "blocked"),
                EXPANDED.contains(WHY_SECTION), answer.viable()));
            if (EXPANDED.contains(WHY_SECTION)) {
                for (RuleResult result : answer.rules()) {
                    built.add(new ReportRow.Rule(result, 1));
                }
                built.add(new ReportRow.Spacer());
            }
        }

        boolean worldOk = this.position.gatesOpen();
        built.add(new ReportRow.Section(WORLD_SECTION, Component.literal("World & chunk"),
            Component.literal(worldOk
                ? this.position.passing() + "/" + this.position.world().size() + " pass"
                : "blocked"),
            EXPANDED.contains(WORLD_SECTION), worldOk));
        if (EXPANDED.contains(WORLD_SECTION)) {
            for (RuleResult result : this.position.world()) {
                built.add(new ReportRow.Rule(result, 1));
            }
        }

        this.rows = List.copyOf(built);
        clampScroll();
    }

    private static String describe(SpawnVerdict verdict) {
        RuleResult blocker = verdict.blocker();
        return blocker == null ? "" : blocker.rule().title() + ": " + blocker.summary();
    }

    private static @Nullable String remedyOf(SpawnVerdict verdict) {
        RuleResult blocker = verdict.blocker();
        return blocker == null ? null : blocker.effectiveRemedy();
    }

    private AuditReport.@Nullable Candidate answer() {
        return MobSelection.answerFor(this.position.pos());
    }

    private static ReportRow.Suggestion suggestionRow(EntityType<?> type) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return new ReportRow.Suggestion(type,
            Component.literal(type.getDescription().getString()),
            Component.literal(id.toString()));
    }

    /**
     * Registry entries matching the query.
     *
     * <p>Matches on both the readable name and the namespaced id, because in a
     * modded pack people know a mob by whichever of the two they last saw, and
     * matching only one fails exactly when the pack is large enough for search to
     * matter.
     */
    private static List<ReportRow> suggestions(String query) {
        List<ReportRow> hits = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue; // Arrows and boats do not spawn; offering them is noise.
            }
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            String readable = type.getDescription().getString();
            if (!id.toString().toLowerCase(Locale.ROOT).contains(query)
                && !readable.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            hits.add(suggestionRow(type));
            if (hits.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        return hits;
    }

    private int bodyTop() {
        return this.top + HEADER_HEIGHT + SEARCH_HEIGHT + BANNER_HEIGHT;
    }

    private int bodyBottom() {
        return this.top + PANEL_HEIGHT - PADDING;
    }

    private int visibleRows() {
        return (bodyBottom() - bodyTop()) / ReportRow.HEIGHT;
    }

    private void clampScroll() {
        this.scroll = Mth.clamp(this.scroll, 0, Math.max(0, this.rows.size() - visibleRows()));
    }

    // ------------------------------------------------------------------- render

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(this.left - 1, this.top - 1, this.left + PANEL_WIDTH + 1, this.top + PANEL_HEIGHT + 1, COLOR_BORDER);
        gui.fill(this.left, this.top, this.left + PANEL_WIDTH, this.top + PANEL_HEIGHT, COLOR_PANEL);

        drawHeader(gui);
        drawBanner(gui);
        drawBody(gui, mouseX, mouseY);

        // Widgets last. Calling super first painted the search field and then buried
        // it under this panel's own background - present, focusable, and invisible.
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
    }

    private void drawHeader(GuiGraphicsExtractor gui) {
        gui.fill(this.left, this.top, this.left + PANEL_WIDTH, this.top + HEADER_HEIGHT, COLOR_HEADER);
        gui.fill(this.left, this.top + HEADER_HEIGHT - 1, this.left + PANEL_WIDTH, this.top + HEADER_HEIGHT,
            COLOR_DIVIDER);

        int x = this.left + PADDING;
        gui.text(this.font, Component.literal("SPAWN DETECTIVE"), x, this.top + 5, TEXT_ACCENT);

        String subtitle = this.position.biome() + "  ·  " + shortDimension();
        gui.text(this.font, Component.literal(trim(subtitle, PANEL_WIDTH - PADDING * 2)),
            x, this.top + 15, TEXT_FAINT);

        Component coords = Component.literal(this.position.pos().toShortString());
        gui.text(this.font, coords, this.left + PANEL_WIDTH - PADDING - this.font.width(coords),
            this.top + 5, TEXT_MUTED);

        // How far the reader is standing from the block they anchored. This is the
        // number the whole anchor gesture exists to make honest, so it is shown
        // whether it helps or not - and coloured, because under 24 blocks the
        // report is measuring a spawn that your presence is actively preventing.
        var local = Minecraft.getInstance().player;
        if (local != null) {
            double away = Math.sqrt(local.distanceToSqr(
                this.position.pos().getX() + 0.5, this.position.pos().getY(), this.position.pos().getZ() + 0.5));
            Component distance = Component.literal(String.format(Locale.ROOT, "%.0f blocks away", away));
            gui.text(this.font, distance, this.left + PANEL_WIDTH - PADDING - this.font.width(distance),
                this.top + 15, away > 24.0 ? TEXT_FAINT : TEXT_WARN);
        }
    }

    private void drawBanner(GuiGraphicsExtractor gui) {
        int y = this.top + HEADER_HEIGHT + SEARCH_HEIGHT;
        int x = this.left + PADDING + 4;
        int textWidth = PANEL_WIDTH - (x - this.left) - PADDING;

        AuditReport.Candidate answer = answer();
        EntityType<?> selected = MobSelection.selected();

        int accent;
        String verdict;
        List<String> detail;
        String remedy = null;

        if (answer == null) {
            // Deliberately not a verdict. Nothing has been asked yet, and inventing an
            // answer to an unasked question is how the old aggregate banner went wrong.
            accent = TEXT_MUTED;
            verdict = selected == null ? "CHOOSE A MOB" : "CHECKING...";
            detail = List.of(selected == null
                ? "Pick a mob to find out whether it can spawn at this block."
                : "Asking the server about " + name(selected) + ".");
        } else {
            // Rendering only. The verdict itself is resolved in SpawnVerdict, where
            // it spans both halves of the pipeline and can be tested - computing it
            // here is how the banner once contradicted the section beneath it.
            SpawnVerdict resolved = SpawnVerdict.of(this.position, answer);
            String mob = name(answer.type()).toUpperCase(Locale.ROOT);

            // A switch expression rather than a statement, so the compiler enforces
            // that every tone produces a complete banner.
            record Banner(int accent, String verdict, List<String> detail, @Nullable String remedy) {}
            Banner banner = switch (resolved.tone()) {
                case CAN_SPAWN -> new Banner(TEXT_GOOD, mob + " CAN SPAWN HERE",
                    wrap("Every gate passes, at this position and in this world right now.", textWidth, 2),
                    null);
                case BLOCKED_NOW -> new Banner(TEXT_WARN, mob + " IS BLOCKED RIGHT NOW",
                    wrap(describe(resolved), textWidth, 2), remedyOf(resolved));
                case BLOCKED_ALWAYS -> new Banner(TEXT_BAD, mob + " CANNOT SPAWN HERE",
                    wrap(describe(resolved), textWidth, 2), remedyOf(resolved));
            };

            accent = banner.accent();
            verdict = banner.verdict();
            detail = banner.detail();
            remedy = banner.remedy();
        }

        gui.fill(this.left, y, this.left + 2, y + BANNER_HEIGHT, accent);

        int line = y + 6;
        gui.text(this.font, Component.literal(glyphFor(accent) + "  " + verdict), x, line, accent);
        line += 12;
        for (String part : detail) {
            gui.text(this.font, Component.literal(part), x, line, TEXT_PRIMARY);
            line += 10;
        }
        if (remedy != null && line + 10 <= y + BANNER_HEIGHT) {
            for (String part : wrap("→ " + remedy, textWidth, 2)) {
                gui.text(this.font, Component.literal(part), x, line, TEXT_WARN);
                line += 10;
            }
        }

        gui.fill(this.left, y + BANNER_HEIGHT - 1, this.left + PANEL_WIDTH, y + BANNER_HEIGHT, COLOR_DIVIDER);
    }

    private static String glyphFor(int accent) {
        if (accent == TEXT_GOOD) {
            return "✓";
        }
        if (accent == TEXT_BAD) {
            return "✕";
        }
        return accent == TEXT_WARN ? "!" : "?";
    }

    private void drawBody(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
        int top = bodyTop();
        int visible = visibleRows();
        int hovered = hoveredIndex(mouseX, mouseY);

        gui.enableScissor(this.left, top, this.left + PANEL_WIDTH, bodyBottom());
        for (int i = 0; i < visible && this.scroll + i < this.rows.size(); i++) {
            drawRow(gui, this.rows.get(this.scroll + i), top + i * ReportRow.HEIGHT, this.scroll + i == hovered);
        }
        gui.disableScissor();

        drawScrollbar(gui, top, visible);
    }

    private void drawRow(GuiGraphicsExtractor gui, ReportRow row, int y, boolean hovered) {
        int x = this.left + PADDING;
        int right = this.left + PANEL_WIDTH - PADDING - 4;

        switch (row) {
            case ReportRow.Section section -> {
                gui.fill(this.left + 2, y, right + PADDING, y + ReportRow.HEIGHT - 1, COLOR_SECTION_BG);
                if (hovered) {
                    gui.fill(this.left + 2, y, right + PADDING, y + ReportRow.HEIGHT - 1, COLOR_ROW_HOVER);
                }
                // Drawn rather than typed: Minecraft's font has no dependable
                // disclosure glyph, and the text ones rendered as specks.
                drawTriangle(gui, x, y + 3, section.expanded(), TEXT_MUTED);
                drawPair(gui, x + 10, y + 2, section.title(), section.summary(), right,
                    TEXT_PRIMARY, section.ok() ? TEXT_GOOD : TEXT_BAD);
            }
            case ReportRow.Suggestion suggestion -> {
                if (hovered) {
                    gui.fill(this.left + 2, y, right + PADDING, y + ReportRow.HEIGHT - 1, COLOR_ROW_HOVER);
                }
                boolean current = suggestion.type() == MobSelection.selected();
                gui.text(this.font, Component.literal(current ? "✓" : "+"), x, y + 2,
                    current ? TEXT_GOOD : TEXT_ACCENT);
                drawPair(gui, x + 10, y + 2, suggestion.name(), suggestion.id(), right,
                    current ? TEXT_GOOD : TEXT_PRIMARY, TEXT_FAINT);
            }
            case ReportRow.Rule rule -> {
                if (hovered) {
                    gui.fill(this.left + 2, y, right + PADDING, y + ReportRow.HEIGHT - 1, COLOR_ROW_HOVER);
                }
                RuleResult result = rule.result();
                int indent = x + 12 * rule.indent();
                gui.text(this.font, Component.literal(glyph(result.verdict())), indent, y + 2,
                    verdictColor(result.verdict()));
                drawPair(gui, indent + 10, y + 2,
                    Component.literal(result.rule().title()),
                    Component.literal(result.summary()),
                    right, TEXT_PRIMARY, TEXT_MUTED);
            }
            case ReportRow.Mob mob -> {
                if (hovered) {
                    gui.fill(this.left + 2, y, right + PADDING, y + ReportRow.HEIGHT - 1, COLOR_ROW_HOVER);
                }
                drawPair(gui, x + 12, y + 2, mob.name(), mob.status(), right,
                    TEXT_PRIMARY, mob.ok() ? TEXT_GOOD : TEXT_BAD);
            }
            case ReportRow.Spacer ignored -> {
                // Intentionally blank: vertical rhythm between sections.
            }
        }
    }

    /**
     * Draw a left-aligned label and a right-aligned value on one line, trimming the
     * value to whatever space the label leaves.
     *
     * <p>Right-aligning without reserving the label's width is how an earlier build
     * rendered "A player is withi<b>playersrathgi</b>n 128 blocks" - two strings
     * painted over each other. The value yields, because the label names the rule
     * and is useless when mangled.
     */
    private void drawPair(
        GuiGraphicsExtractor gui, int labelX, int y, Component label, Component value,
        int right, int labelColor, int valueColor
    ) {
        gui.text(this.font, label, labelX, y, labelColor);

        int available = right - (labelX + this.font.width(label) + COLUMN_GAP);
        if (available <= this.font.width("...")) {
            return; // No honest room for the value; the label alone beats a smear.
        }
        String text = trim(value.getString(), available);
        gui.text(this.font, Component.literal(text), right - this.font.width(text), y, valueColor);
    }

    private static void drawTriangle(GuiGraphicsExtractor gui, int x, int y, boolean expanded, int color) {
        if (expanded) {
            for (int i = 0; i < 3; i++) {
                gui.fill(x + i, y + i, x + 7 - i, y + i + 1, color);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                gui.fill(x + i, y + i, x + i + 1, y + 7 - i, color);
            }
        }
    }

    private void drawScrollbar(GuiGraphicsExtractor gui, int top, int visible) {
        if (this.rows.size() <= visible) {
            return;
        }
        int trackHeight = bodyBottom() - top;
        int thumbHeight = Math.max(12, trackHeight * visible / this.rows.size());
        int travel = trackHeight - thumbHeight;
        int offset = travel * this.scroll / Math.max(1, this.rows.size() - visible);
        int x = this.left + PANEL_WIDTH - 4;

        gui.fill(x, top, x + 2, bodyBottom(), COLOR_HEADER);
        gui.fill(x, top + offset, x + 2, top + offset + thumbHeight, TEXT_FAINT);
    }

    // ------------------------------------------------------------------- input

    private int hoveredIndex(int mouseX, int mouseY) {
        if (mouseX < this.left || mouseX > this.left + PANEL_WIDTH
            || mouseY < bodyTop() || mouseY >= bodyBottom()) {
            return -1;
        }
        int index = this.scroll + (mouseY - bodyTop()) / ReportRow.HEIGHT;
        return index < this.rows.size() ? index : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int index = hoveredIndex((int) event.x(), (int) event.y());
        if (index >= 0) {
            if (this.rows.get(index) instanceof ReportRow.Suggestion suggestion) {
                // Choosing from the list is also how you clear a search: the field
                // emptying is the signal that the choice has been made.
                if (this.search != null) {
                    this.search.setValue("");
                }
                ask(suggestion.type());
                return true;
            }
            String key = this.rows.get(index).toggles();
            if (key != null) {
                if (!EXPANDED.remove(key)) {
                    EXPANDED.add(key);
                }
                rebuildRows();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        this.scroll -= (int) Math.signum(deltaY);
        clampScroll();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        // Never pause: the answer describes a live world, and freezing it while the
        // reader thinks would make caps and distances stale on a listen server.
        return false;
    }

    // ----------------------------------------------------------------- helpers

    private static String name(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
    }

    private String shortDimension() {
        String dimension = this.position.dimension();
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }

    /** Split text across at most {@code maxLines} lines of {@code maxWidth}, ellipsing the last. */
    private List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty() && lines.size() < maxLines) {
            if (this.font.width(rest) <= maxWidth) {
                lines.add(rest);
                return lines;
            }
            if (lines.size() == maxLines - 1) {
                lines.add(trim(rest, maxWidth));
                return lines;
            }
            String head = this.font.plainSubstrByWidth(rest, maxWidth);
            int breakAt = head.lastIndexOf(' ');
            if (breakAt <= 0) {
                breakAt = head.length();
            }
            lines.add(rest.substring(0, breakAt).trim());
            rest = rest.substring(breakAt).trim();
        }
        return lines;
    }

    private String trim(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, maxWidth - this.font.width("...")) + "...";
    }

    private static String glyph(Verdict verdict) {
        return switch (verdict) {
            case PASS -> "✓";
            case MARGINAL -> "~";
            case FAIL -> "✕";
            case SKIPPED -> "·";
            case UNKNOWN -> "?";
        };
    }

    private static int verdictColor(Verdict verdict) {
        return switch (verdict) {
            case PASS -> TEXT_GOOD;
            case MARGINAL -> TEXT_WARN;
            case FAIL -> TEXT_BAD;
            case SKIPPED -> TEXT_FAINT;
            case UNKNOWN -> TEXT_MUTED;
        };
    }
}
