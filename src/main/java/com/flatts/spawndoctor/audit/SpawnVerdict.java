package com.flatts.spawndoctor.audit;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
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
 */
public record SpawnVerdict(Tone tone, @Nullable RuleResult blocker) {

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
            return new SpawnVerdict(Tone.BLOCKED_ALWAYS, standing.get());
        }
        Optional<RuleResult> situational = firstBlocker(world, candidate, false);
        return situational
            .map(blocker -> new SpawnVerdict(Tone.BLOCKED_NOW, blocker))
            .orElseGet(() -> new SpawnVerdict(Tone.CAN_SPAWN, null));
    }

    private static Optional<RuleResult> firstBlocker(
        List<RuleResult> world, AuditReport.Candidate candidate, boolean standing
    ) {
        return Stream.concat(world.stream(), candidate.rules().stream())
            .filter(r -> r.verdict().blocks() && r.rule().standing() == standing)
            .findFirst();
    }

    public boolean canSpawn() {
        return this.tone == Tone.CAN_SPAWN;
    }
}
