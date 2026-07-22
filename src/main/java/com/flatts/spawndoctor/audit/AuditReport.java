package com.flatts.spawndoctor.audit;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * The complete answer for one block position.
 *
 * <p>Structure mirrors the pipeline: world and chunk rules are evaluated once,
 * then each {@link MobCategory} that the biome offers gets its own section, and
 * inside it every mob type on the biome's spawn list is walked individually.
 * A position "can spawn something" iff at least one {@link Candidate} is viable.
 */
public record AuditReport(
    String dimension,
    BlockPos pos,
    String biome,
    List<RuleResult> world,
    List<Category> categories
) {

    /** One mob category's caps plus the mob types the biome offers for it here. */
    public record Category(MobCategory category, List<RuleResult> rules, List<Candidate> candidates) {

        /** The category's own gates (caps) all permit a spawn. */
        public boolean gatesOpen() {
            return this.rules.stream().noneMatch(r -> r.verdict().blocks());
        }

        public boolean anyViable() {
            return this.gatesOpen() && this.candidates.stream().anyMatch(Candidate::viable);
        }
    }

    /**
     * One mob type from the biome's spawn list, walked through the per-type gates.
     *
     * @param weight      this entry's weight within the category's list
     * @param totalWeight the sum of all weights, so the report can show a real roll chance
     */
    public record Candidate(EntityType<?> type, int weight, int totalWeight, List<RuleResult> rules) {

        /** Nothing definitively rejects this mob here. */
        public boolean viable() {
            return this.rules.stream().noneMatch(r -> r.verdict().blocks());
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
}
