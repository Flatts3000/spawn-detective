package com.flatts.spawndoctor.client.screen;

import com.flatts.spawndoctor.audit.AuditReport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

/**
 * The mob the reader is currently asking about, remembered between probes.
 *
 * <p>Stickiness is the whole point. Someone lighting up a base asks the same
 * question of twenty blocks in a row - "would a zombie spawn here?" - and being
 * made to re-pick the mob at every block would turn a two-click check into a
 * twenty-click chore. Pick once, then probe freely.
 *
 * <p>The answer is kept alongside the selection and cleared as soon as the
 * position changes, so the screen can never show a verdict from one block against
 * the coordinates of another while the new answer is in flight.
 */
public final class MobSelection {

    private static @Nullable EntityType<?> selected;
    private static AuditReport.@Nullable Candidate answer;
    private static @Nullable BlockPos answerPos;

    private MobSelection() {
    }

    public static @Nullable EntityType<?> selected() {
        return selected;
    }

    public static void select(EntityType<?> type) {
        if (type != selected) {
            selected = type;
            clearAnswer();
        }
    }

    public static void clear() {
        selected = null;
        clearAnswer();
    }

    /** The verdict for {@code pos}, or null if we have none for that exact position yet. */
    public static AuditReport.@Nullable Candidate answerFor(BlockPos pos) {
        return pos.equals(answerPos) ? answer : null;
    }

    public static void acceptAnswer(BlockPos pos, AuditReport.Candidate candidate) {
        answerPos = pos;
        answer = candidate;
        selected = candidate.type();
    }

    private static void clearAnswer() {
        answer = null;
        answerPos = null;
    }
}
