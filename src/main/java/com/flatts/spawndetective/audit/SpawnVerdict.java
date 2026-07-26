package com.flatts.spawndetective.audit;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.Nullable;

/**
 * The single answer to "would this mob spawn at this block, right now".
 *
 * <p>This lives in the audit package rather than in the screen for a specific
 * reason. It used to be inline in the renderer, where it consulted only the mob's
 * own gates - so the banner once read "SKELETON CAN SPAWN HERE" directly above a
 * World &amp; chunk section reading "blocked", because the reader was ten blocks
 * away and the 24-block player rule had failed. No test could catch it: the
 * in-world suite runs server-side and cannot load a client class.
 *
 * <p>Verdict logic is not presentation. Kept here it is testable, reusable by the
 * command, and impossible to contradict by rendering a different subset of the
 * evidence somewhere else.
 *
 * @param caveat a rule that permits a spawn but qualifies how often - the reason a
 *               green verdict is not always the whole answer. See {@link #caveat()}.
 */
public record SpawnVerdict(Tone tone, @Nullable RuleResult blocker, @Nullable RuleResult caveat) {

    /** What kind of answer this is. */
    public enum Tone {
        /** Nothing anywhere in the pipeline rejects this mob here. */
        CAN_SPAWN,
        /** Blocked only by something that will pass on its own: caps, difficulty, a nearby player. */
        BLOCKED_NOW,
        /** Blocked by the shape of the world: floor, light, biome, hitbox. */
        BLOCKED_ALWAYS
    }

    /**
     * Resolve over the whole pipeline: the position's world and chunk gates first,
     * then the mob's own.
     *
     * <p>World gates are searched first because vanilla applies them first. A chunk
     * that is not ticking stops every mob before any per-mob rule is consulted, so
     * leading with the mob's placement would name the second reason rather than the
     * first.
     *
     * <p>A permanent blocker outranks a temporary one regardless of order, because
     * "the floor is wrong" and "you are standing here" call for opposite actions and
     * the permanent one is the news.
     *
     * <p>Rules the mob's category is not subject to are not consulted at all. The
     * world list is shared by every mob at the position, and one row on it is not
     * true of all of them: Peaceful stops the hostile categories and nothing else,
     * so resolving a chicken against it produced "CHICKEN IS BLOCKED RIGHT NOW -
     * Difficulty: peaceful" in a swamp that was still spawning chickens. See
     * {@link SpawnRule.Scope}.
     */
    public static SpawnVerdict of(PositionReport position, AuditReport.Candidate candidate) {
        return of(position.world(), candidate);
    }

    /**
     * As above, from the world rules directly.
     *
     * <p>Both report shapes carry the same world-rule list, and both must reach the
     * same verdict from it. Letting the command compute its own answer is how the
     * aggregate report ended up printing a different, worse verdict than the screen
     * for the same block.
     */
    public static SpawnVerdict of(List<RuleResult> world, AuditReport.Candidate candidate) {
        Optional<RuleResult> standing = firstBlocker(world, candidate, true);
        if (standing.isPresent()) {
            return new SpawnVerdict(Tone.BLOCKED_ALWAYS, standing.get(), null);
        }
        Optional<RuleResult> situational = firstBlocker(world, candidate, false);
        return situational
            .map(blocker -> new SpawnVerdict(Tone.BLOCKED_NOW, blocker, null))
            .orElseGet(() -> new SpawnVerdict(Tone.CAN_SPAWN, null, firstCaveat(world, candidate)));
    }

    private static Optional<RuleResult> firstBlocker(
        List<RuleResult> world, AuditReport.Candidate candidate, boolean standing
    ) {
        return applicable(world, candidate)
            .filter(r -> r.verdict().blocks() && r.rule().standing() == standing)
            .findFirst();
    }

    /**
     * The whole pipeline in vanilla's order, minus the gates this mob is not subject
     * to.
     *
     * <p>The single place the scope filter is applied, so a rule cannot be scoped out
     * of the blocker search and left in the caveat search - which would qualify a yes
     * with a rule that was never able to say no.
     */
    private static Stream<RuleResult> applicable(List<RuleResult> world, AuditReport.Candidate candidate) {
        MobCategory category = candidate.type().getCategory();
        return Stream.concat(world.stream(), candidate.rules().stream())
            .filter(r -> r.rule().appliesTo(category));
    }

    /**
     * The first rule that permits a spawn without promising one.
     *
     * <p>A {@link Verdict#MARGINAL} rule does not block, so before this existed the
     * banner read a flat "CAN SPAWN HERE - every gate passes" over a mob that clears
     * its light roll three times in a hundred, and over a block in a void world that
     * the spawner's sampler reaches once an hour. Both are technically correct and
     * both send a player away believing their farm will produce.
     *
     * <p>Only populated for {@link Tone#CAN_SPAWN}. Beside a blocker it would be
     * noise: the blocker is the answer, and a second qualification competes with it.
     *
     * <p>Permanent qualifications outrank temporary ones, for the same reason
     * {@link #firstBlocker} prefers them: "the spawner reaches this height once an
     * hour" is a property of the place and will still be true tomorrow, while "the
     * mob cap is full" reverts on its own within seconds. Naming the transient one
     * over the standing one would send someone off to kill mobs when their platform
     * is the problem. Within a persistence, world rules come first, matching
     * vanilla's own order.
     */
    private static @Nullable RuleResult firstCaveat(List<RuleResult> world, AuditReport.Candidate candidate) {
        return caveatOf(world, candidate, true).or(() -> caveatOf(world, candidate, false)).orElse(null);
    }

    private static Optional<RuleResult> caveatOf(
        List<RuleResult> world, AuditReport.Candidate candidate, boolean standing
    ) {
        return applicable(world, candidate)
            .filter(r -> r.verdict() == Verdict.MARGINAL && r.rule().standing() == standing)
            .findFirst();
    }

    public boolean canSpawn() {
        return this.tone == Tone.CAN_SPAWN;
    }
}
