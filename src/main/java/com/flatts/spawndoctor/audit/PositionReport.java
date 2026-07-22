package com.flatts.spawndoctor.audit;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.EntityType;

/**
 * Everything true of a position regardless of which mob is being asked about.
 *
 * <p>The report is built in two halves because the question is asked in two
 * halves. "What is this place like" is cheap, always the same, and answered the
 * moment you click. "Would <i>this</i> mob spawn here" costs a few hundred
 * predicate rolls and a throwaway entity, and is only worth paying for once
 * someone has named a mob.
 *
 * <p>Splitting them also fixed the report's voice. Auditing every mob at once
 * produced aggregate verdicts like "slime +6 more - needs sky", which is several
 * different findings averaged into one sentence that is true of nothing in
 * particular. One mob has one answer.
 *
 * @param suggestions the mobs this biome actually offers here, heaviest first, so
 *                    the empty state can propose something instead of demanding a
 *                    search term from someone who does not yet know what to type
 */
public record PositionReport(
    String dimension,
    BlockPos pos,
    String biome,
    List<RuleResult> world,
    List<EntityType<?>> suggestions
) {

    public static final StreamCodec<RegistryFriendlyByteBuf, PositionReport> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8, PositionReport::dimension,
        BlockPos.STREAM_CODEC, PositionReport::pos,
        ByteBufCodecs.STRING_UTF8, PositionReport::biome,
        RuleResult.STREAM_CODEC.apply(ByteBufCodecs.list()), PositionReport::world,
        ByteBufCodecs.registry(Registries.ENTITY_TYPE).apply(ByteBufCodecs.list()), PositionReport::suggestions,
        PositionReport::new);

    /** No world or chunk gate rejects a spawn here, whatever the mob. */
    public boolean gatesOpen() {
        return this.world.stream().noneMatch(r -> r.verdict().blocks());
    }

    /**
     * A world-level blocker that no mob can get past, if there is one.
     *
     * <p>Restricted to standing rules: the situational ones are dominated by the
     * observer's own presence, which the audit already discounts.
     */
    public Optional<RuleResult> blocker() {
        return this.world.stream()
            .filter(r -> r.verdict().blocks() && r.rule().standing())
            .findFirst();
    }

    /** Passing world gates, for the section summary. */
    public long passing() {
        return this.world.stream().filter(r -> r.verdict().permits()).count();
    }
}
