package com.flatts.spawndoctor.audit;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.Nullable;

/**
 * The complete answer for one block position.
 *
 * <p>Structure mirrors the pipeline: world and chunk rules are evaluated once,
 * then each {@link MobCategory} that the biome offers gets its own section, and
 * inside it every mob type on the biome's spawn list is walked individually.
 * A position "can spawn something" iff at least one {@link Candidate} is viable.
 *
 * <p>The report is built server-side and sent whole to the client for display, so
 * every part of it carries a {@link StreamCodec}.
 */
public record AuditReport(
    String dimension,
    BlockPos pos,
    String biome,
    List<RuleResult> world,
    List<Category> categories
) {

    public static final StreamCodec<RegistryFriendlyByteBuf, AuditReport> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, AuditReport::dimension,
        BlockPos.STREAM_CODEC, AuditReport::pos,
        ByteBufCodecs.STRING_UTF8, AuditReport::biome,
        RuleResult.STREAM_CODEC.apply(ByteBufCodecs.list()), AuditReport::world,
        Category.STREAM_CODEC.apply(ByteBufCodecs.list()), AuditReport::categories,
        AuditReport::new);

    /** One mob category's caps plus the mob types the biome offers for it here. */
    public record Category(MobCategory category, List<RuleResult> rules, List<Candidate> candidates) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Category> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> MobCategory.values()[i], MobCategory::ordinal), Category::category,
            RuleResult.STREAM_CODEC.apply(ByteBufCodecs.list()), Category::rules,
            Candidate.STREAM_CODEC.apply(ByteBufCodecs.list()), Category::candidates,
            Category::new);

        /** The category's own gates (caps) all permit a spawn. */
        public boolean gatesOpen() {
            return this.rules.stream().noneMatch(r -> r.verdict().blocks());
        }

        public boolean anyViable() {
            return this.gatesOpen() && this.candidates.stream().anyMatch(Candidate::viable);
        }

        public long viableCount() {
            return this.gatesOpen() ? this.candidates.stream().filter(Candidate::viable).count() : 0L;
        }

        /** The first cap or list rule that rejected this whole category, if any. */
        public Optional<RuleResult> blocker() {
            return this.rules.stream().filter(r -> r.verdict().blocks()).findFirst();
        }

        /** As {@link #blocker()}, restricted to rules of the given persistence. */
        public Optional<RuleResult> blocker(boolean standing) {
            return this.rules.stream()
                .filter(r -> r.verdict().blocks() && r.rule().standing() == standing)
                .findFirst();
        }

        /** No permanent rule shuts this category - only caps and the like might. */
        public boolean standingGatesOpen() {
            return this.rules.stream().noneMatch(r -> r.verdict().blocks() && r.rule().standing());
        }

        /** Mob types held back only by something temporary. */
        public long viableStandingCount() {
            return this.standingGatesOpen()
                ? this.candidates.stream().filter(Candidate::viableStanding).count()
                : 0L;
        }

        /**
         * True when this category is worth a line in the report at all.
         *
         * <p>A category the biome offers nothing for is not an answer to anybody's
         * question - nobody troubleshooting a forest needs to be told that axolotls
         * do not spawn there. Those are dropped entirely rather than shown as
         * failures.
         */
        public boolean relevant() {
            return !this.candidates.isEmpty();
        }
    }

    /**
     * One mob type from the biome's spawn list, walked through the per-type gates.
     *
     * @param weight      this entry's weight within the category's list
     * @param totalWeight the sum of all weights, so the report can show a real roll chance
     */
    public record Candidate(EntityType<?> type, int weight, int totalWeight, List<RuleResult> rules) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Candidate> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ENTITY_TYPE), Candidate::type,
            ByteBufCodecs.VAR_INT, Candidate::weight,
            ByteBufCodecs.VAR_INT, Candidate::totalWeight,
            RuleResult.STREAM_CODEC.apply(ByteBufCodecs.list()), Candidate::rules,
            Candidate::new);

        /** Nothing definitively rejects this mob here. */
        public boolean viable() {
            return this.rules.stream().noneMatch(r -> r.verdict().blocks());
        }

        /**
         * Nothing <i>permanent</i> rejects this mob - it would spawn once the
         * situational blockers (your presence, a full cap) cleared.
         */
        public boolean viableStanding() {
            return this.rules.stream().noneMatch(r -> r.verdict().blocks() && r.rule().standing());
        }

        /** The first blocker of the given persistence, if any. */
        public Optional<RuleResult> blocker(boolean standing) {
            return this.rules.stream()
                .filter(r -> r.verdict().blocks() && r.rule().standing() == standing)
                .findFirst();
        }

        /** The first rule that rejected this mob - the headline answer for it. */
        public Optional<RuleResult> blocker() {
            return this.rules.stream().filter(r -> r.verdict().blocks()).findFirst();
        }

        /** Chance of this entry being rolled when the category is picked, as a percentage. */
        public float rollChance() {
            return this.totalWeight <= 0 ? 0.0F : 100.0F * this.weight / this.totalWeight;
        }
    }

    /**
     * The single sentence a player came for.
     *
     * @param tone   which of the three answers this is
     * @param detail the reason, with the measured numbers
     * @param remedy what to do about it, or null when there is nothing to fix
     * @param note   a secondary line - usually what is *also* true but less important
     */
    public record Headline(Tone tone, String detail, @Nullable String remedy, @Nullable String note) {

        public String verdict() {
            return this.tone.verdict();
        }

        public boolean canSpawn() {
            return this.tone == Tone.CAN_SPAWN;
        }
    }

    /**
     * The three answers, and the reason there are three rather than two.
     *
     * <p>A player probing a block is always standing next to it, so the 24-block
     * player rule always fails and a plain yes/no would answer "no" every single
     * time. {@link #BLOCKED_NOW} is the honest middle: the spot is fine, you are
     * the problem. It is the same distinction the world overlay draws in yellow.
     */
    public enum Tone {
        CAN_SPAWN("MOBS CAN SPAWN HERE"),
        BLOCKED_NOW("NOTHING SPAWNS RIGHT NOW"),
        BLOCKED_ALWAYS("NOTHING CAN SPAWN HERE");

        private final String verdict;

        Tone(String verdict) {
            this.verdict = verdict;
        }

        public String verdict() {
            return this.verdict;
        }
    }

    /** World/chunk rules all permit a spawn - i.e. the failure is not global. */
    public boolean worldGatesOpen() {
        return this.world.stream().noneMatch(r -> r.verdict().blocks());
    }

    /** The single headline answer: the first world-level blocker, if any. */
    public Optional<RuleResult> worldBlocker() {
        return this.world.stream().filter(r -> r.verdict().blocks()).findFirst();
    }

    /** True when at least one mob of any category can spawn at this position. */
    public boolean anythingCanSpawn() {
        return this.worldGatesOpen() && this.categories.stream().anyMatch(Category::anyViable);
    }

    /** Categories worth displaying, most relevant first. */
    public List<Category> relevantCategories() {
        return this.categories.stream()
            .filter(Category::relevant)
            .sorted(Comparator.comparingInt(AuditReport::categoryRank))
            .toList();
    }

    /**
     * Display order. Monsters lead because they are what almost every question is
     * actually about; after that, whatever offers more mob types here is more
     * interesting than whatever offers fewer.
     */
    private static int categoryRank(Category category) {
        if (category.category() == MobCategory.MONSTER) {
            return -1_000_000;
        }
        if (category.category() == MobCategory.CREATURE) {
            return -999_999;
        }
        return -category.candidates().size();
    }

    /**
     * Reduce the whole report to one answer.
     *
     * <p>Order is deliberately not "first failure in pipeline order". A permanent
     * blocker outranks a temporary one, because "the floor is wrong" is a different
     * kind of news from "you are standing here". Within each kind, the winner is
     * the blocker that stops the <i>most</i> mob types - the one change that would
     * make the biggest difference, which is what the player is really asking.
     */
    public Headline headline() {
        // A permanent world gate stops everything, everywhere, forever. Nothing outranks it.
        Optional<RuleResult> standingWorld = this.world.stream()
            .filter(r -> r.verdict().blocks() && r.rule().standing())
            .findFirst();
        if (standingWorld.isPresent()) {
            RuleResult blocker = standingWorld.get();
            return new Headline(Tone.BLOCKED_ALWAYS,
                blocker.rule().title() + " - " + blocker.summary(), blocker.effectiveRemedy(), null);
        }

        if (this.anythingCanSpawn()) {
            long viable = this.categories.stream().mapToLong(Category::viableCount).sum();
            return new Headline(Tone.CAN_SPAWN,
                viable + " mob type" + (viable == 1 ? "" : "s") + " pass every gate here", null, null);
        }

        // Permanent per-mob blocker: the spot itself is wrong, and moving away will
        // not change that.
        Optional<Blame> standing = dominantBlocker(true);
        if (standing.isPresent()) {
            Blame blame = standing.get();
            return new Headline(Tone.BLOCKED_ALWAYS,
                blame.describe(), blame.result().effectiveRemedy(), situationalNote());
        }

        // Everything permanent passes, so whatever is stopping the spawn will pass
        // on its own. This is the common case when probing a block you are next to.
        long would = this.categories.stream().mapToLong(Category::viableStandingCount).sum();
        Optional<Blame> situational = dominantBlocker(false);
        if (situational.isPresent()) {
            Blame blame = situational.get();
            return new Headline(Tone.BLOCKED_NOW,
                blame.describe(), blame.result().effectiveRemedy(),
                would + " mob type" + (would == 1 ? "" : "s") + " would spawn here once that clears");
        }

        return new Headline(Tone.BLOCKED_ALWAYS,
            "this biome offers no mobs at this position", null, null);
    }

    /**
     * A blocker together with the mobs it stops.
     *
     * <p>The reason this exists: "the mob's own spawn rules - needs sky" says
     * nothing useful when the reader has no idea which mob is meant. A cause needs
     * a subject.
     *
     * @param example one mob the blocker applies to, for naming
     * @param affected how many mobs share it
     */
    private record Blame(RuleResult result, @Nullable EntityType<?> example, int affected) {

        /** "zombie", or "zombie +7 more" when the same cause stops a crowd. */
        String subject() {
            if (this.example == null) {
                return this.affected + " mob types";
            }
            String name = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(this.example).getPath();
            return this.affected > 1 ? name + " +" + (this.affected - 1) + " more" : name;
        }

        /** The full headline sentence: who, what rule, and the measurement. */
        String describe() {
            return this.subject() + " - " + this.result.rule().title().toLowerCase(Locale.ROOT)
                + ": " + this.result.summary();
        }
    }

    /** A one-line aside naming the temporary blocker, when there also is one. */
    private @Nullable String situationalNote() {
        return dominantBlocker(false)
            .map(blame -> "also blocked right now: "
                + blame.result().rule().title().toLowerCase(Locale.ROOT)
                + " (" + blame.result().summary() + ")")
            .orElse(null);
    }

    /**
     * The rule of the given persistence that blocks the most mob types, with one
     * representative result for it.
     */
    private Optional<Blame> dominantBlocker(boolean standing) {
        Map<SpawnRule, Integer> counts = new HashMap<>();
        Map<SpawnRule, RuleResult> examples = new HashMap<>();
        Map<SpawnRule, EntityType<?>> subjects = new HashMap<>();

        for (RuleResult result : this.world) {
            if (result.verdict().blocks() && result.rule().standing() == standing) {
                // A world gate holds back everything, so it outweighs any per-mob rule.
                tally(counts, examples, result, Integer.MAX_VALUE / 4);
            }
        }

        for (Category category : this.categories) {
            if (!category.relevant()) {
                continue;
            }
            // A shut cap blocks every mob in the category at once, so it counts once
            // per mob it holds back rather than once overall.
            Optional<RuleResult> categoryBlocker = category.blocker(standing);
            if (categoryBlocker.isPresent()) {
                tally(counts, examples, categoryBlocker.get(), category.candidates().size());
                category.candidates().stream().findFirst().ifPresent(candidate ->
                    subjects.putIfAbsent(categoryBlocker.get().rule(), candidate.type()));
                continue;
            }
            for (Candidate candidate : category.candidates()) {
                candidate.blocker(standing).ifPresent(blocker -> {
                    tally(counts, examples, blocker, 1);
                    subjects.putIfAbsent(blocker.rule(), candidate.type());
                });
            }
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(entry -> new Blame(
                examples.get(entry.getKey()),
                subjects.get(entry.getKey()),
                // World gates carry a sentinel weight; report them as affecting everything.
                Math.min(entry.getValue(), totalCandidates())));
    }

    /** How many mob types the biome offers here in total, across every category. */
    private int totalCandidates() {
        return this.categories.stream().mapToInt(c -> c.candidates().size()).sum();
    }

    private static void tally(
        Map<SpawnRule, Integer> counts, Map<SpawnRule, RuleResult> examples, RuleResult result, int weight
    ) {
        counts.merge(result.rule(), weight, Integer::sum);
        examples.putIfAbsent(result.rule(), result);
    }
}
