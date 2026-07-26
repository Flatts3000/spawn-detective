package com.flatts.spawndetective.audit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

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
            return this.rules.stream().noneMatch(this::blocks);
        }

        /** A rejection this category is actually subject to. See {@link SpawnRule.Scope}. */
        private boolean blocks(RuleResult result) {
            return result.verdict().blocks() && result.rule().appliesTo(this.category);
        }

        public boolean anyViable() {
            return this.gatesOpen() && this.candidates.stream().anyMatch(Candidate::viable);
        }

        public long viableCount() {
            return this.gatesOpen() ? this.candidates.stream().filter(Candidate::viable).count() : 0L;
        }

        /** The first cap or list rule that rejected this whole category, if any. */
        public Optional<RuleResult> blocker() {
            return this.rules.stream().filter(this::blocks).findFirst();
        }

        /** As {@link #blocker()}, restricted to rules of the given persistence. */
        public Optional<RuleResult> blocker(boolean standing) {
            return this.rules.stream()
                .filter(r -> this.blocks(r) && r.rule().standing() == standing)
                .findFirst();
        }

        /** No permanent rule shuts this category - only caps and the like might. */
        public boolean standingGatesOpen() {
            return this.rules.stream().noneMatch(r -> this.blocks(r) && r.rule().standing());
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
            return this.rules.stream().noneMatch(this::blocks);
        }

        /**
         * Nothing <i>permanent</i> rejects this mob - it would spawn once the
         * situational blockers (your presence, a full cap) cleared.
         */
        public boolean viableStanding() {
            return this.rules.stream().noneMatch(r -> this.blocks(r) && r.rule().standing());
        }

        /** The first blocker of the given persistence, if any. */
        public Optional<RuleResult> blocker(boolean standing) {
            return this.rules.stream()
                .filter(r -> this.blocks(r) && r.rule().standing() == standing)
                .findFirst();
        }

        /** The first rule that rejected this mob - the headline answer for it. */
        public Optional<RuleResult> blocker() {
            return this.rules.stream().filter(this::blocks).findFirst();
        }

        /** A rejection this mob is actually subject to. See {@link SpawnRule.Scope}. */
        private boolean blocks(RuleResult result) {
            return result.verdict().blocks() && result.rule().appliesTo(this.type.getCategory());
        }

        /** Chance of this entry being rolled when the category is picked, as a percentage. */
        public float rollChance() {
            return this.totalWeight <= 0 ? 0.0F : 100.0F * this.weight / this.totalWeight;
        }
    }

    /**
     * World/chunk rules all permit a spawn - i.e. the failure is not global.
     *
     * <p>Only rules that bind every mob count, the same as
     * {@link PositionReport#gatesOpen()}: Peaceful is a world fact but it is not a
     * closed world, and the categories it does shut report it for themselves.
     */
    public boolean worldGatesOpen() {
        return this.world.stream().noneMatch(r -> r.verdict().blocks() && r.rule().appliesToEveryMob());
    }

    /** The single headline answer: the first world-level blocker, if any. */
    public Optional<RuleResult> worldBlocker() {
        return this.world.stream()
            .filter(r -> r.verdict().blocks() && r.rule().appliesToEveryMob())
            .findFirst();
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

}
