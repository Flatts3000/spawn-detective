package com.flatts.spawndetective.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.flatts.spawndetective.SpawnDetective;
import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.SpawnAuditor;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;

/**
 * Budgets for the two calls that run on the server thread while someone plays.
 *
 * <p>These are not micro-benchmarks and the numbers are noisy - a CI runner is not
 * a gaming machine. The ceilings are set well above the measured cost, because the
 * job here is catching a change that makes something an order of magnitude worse,
 * not policing a few percent. A tighter bound would just fail on a busy runner and
 * get ignored, which is worse than no bound.
 *
 * <p>Measured costs are logged on every run so a regression is visible in the trend
 * even when it stays under the ceiling.
 */
@GameTestHolder(SpawnDetective.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PerformanceTests {

    /**
     * Per-mob audit budget. Jade calls this on every look-at tick while a probe is
     * held, so a player sweeping a room pays it several times a second.
     */
    private static final long MOB_AUDIT_BUDGET_MICROS = 8_000;

    /** Position audit budget: cheap by design, since a probe click runs it. */
    private static final long POSITION_BUDGET_MICROS = 20_000;

    /** Iterations to average over. Enough to swamp one unlucky GC pause. */
    private static final int RUNS = 40;

    private PerformanceTests() {
    }


    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void perf_mob_audit_within_budget(GameTestHelper helper) {
        BlockPos pos = chamber(helper);
        helper.runAfterDelay(10L, () -> {
            BlockPos absolute = helper.absolutePos(pos);
            // Warm up: the first call pays class loading and JIT, which is not what
            // a player experiences on the hundredth look-at.
            for (int i = 0; i < 5; i++) {
                SpawnAuditor.auditType(helper.getLevel(), absolute, EntityType.ZOMBIE);
            }

            long start = System.nanoTime();
            for (int i = 0; i < RUNS; i++) {
                SpawnAuditor.auditType(helper.getLevel(), absolute, EntityType.ZOMBIE);
            }
            long micros = (System.nanoTime() - start) / 1000 / RUNS;

            report(helper, "auditType(zombie)", micros, MOB_AUDIT_BUDGET_MICROS);
            helper.succeed();
        });
    }

    /**
     * The expensive path, measured on purpose.
     *
     * <p>A mob that passes its spawn rules never triggers attribution, so timing a
     * spawnable spot measures the cheap half. Attribution re-runs the predicate
     * under two more spawn reasons across three seeds, so a blocked mob costs
     * roughly three times a clear one - and blocked is the case people actually
     * point the probe at.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void perf_attribution_within_budget(GameTestHelper helper) {
        BlockPos pos = chamber(helper);
        helper.setBlock(pos.offset(1, 0, 0), Blocks.GLOWSTONE);

        helper.runAfterDelay(10L, () -> {
            BlockPos absolute = helper.absolutePos(pos);
            for (int i = 0; i < 5; i++) {
                SpawnAuditor.auditType(helper.getLevel(), absolute, EntityType.ZOMBIE);
            }

            long start = System.nanoTime();
            for (int i = 0; i < RUNS; i++) {
                SpawnAuditor.auditType(helper.getLevel(), absolute, EntityType.ZOMBIE);
            }
            long micros = (System.nanoTime() - start) / 1000 / RUNS;

            report(helper, "auditType(zombie, blocked - runs attribution)", micros, MOB_AUDIT_BUDGET_MICROS);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 40)
    public static void perf_position_audit_within_budget(GameTestHelper helper) {
        BlockPos pos = chamber(helper);
        helper.runAfterDelay(10L, () -> {
            BlockPos absolute = helper.absolutePos(pos);
            for (int i = 0; i < 5; i++) {
                SpawnAuditor.auditPosition(helper.getLevel(), absolute);
            }

            long start = System.nanoTime();
            for (int i = 0; i < RUNS; i++) {
                SpawnAuditor.auditPosition(helper.getLevel(), absolute);
            }
            long micros = (System.nanoTime() - start) / 1000 / RUNS;

            report(helper, "auditPosition", micros, POSITION_BUDGET_MICROS);
            helper.succeed();
        });
    }

    /**
     * The whole-position sweep, which walks every mob the biome offers. Only a
     * command runs it, so it may cost more - but it still blocks the server thread,
     * and "a command that freezes the server" is a bug report either way.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 100)
    public static void perf_full_sweep_within_budget(GameTestHelper helper) {
        BlockPos pos = chamber(helper);
        helper.runAfterDelay(10L, () -> {
            BlockPos absolute = helper.absolutePos(pos);
            SpawnAuditor.audit(helper.getLevel(), absolute);

            long start = System.nanoTime();
            int sweeps = 5;
            int mobs = 0;
            for (int i = 0; i < sweeps; i++) {
                AuditReport report = SpawnAuditor.audit(helper.getLevel(), absolute);
                mobs = report.categories().stream().mapToInt(c -> c.candidates().size()).sum();
            }
            long micros = (System.nanoTime() - start) / 1000 / sweeps;

            SpawnDetective.LOGGER.info(String.format(Locale.ROOT,
                "[perf] full sweep: %d us over %d mobs (%d us/mob)",
                micros, mobs, mobs == 0 ? 0 : micros / mobs));

            // Deliberately generous: this is the expensive path by design, and the
            // ceiling exists to catch a runaway, not to tune it.
            if (micros > 400_000) {
                throw fail(helper, "full sweep took " + micros + " us over " + mobs + " mobs");
            }
            helper.succeed();
        });
    }

    private static void report(GameTestHelper helper, String what, long micros, long budget) {
        SpawnDetective.LOGGER.info(String.format(Locale.ROOT, "[perf] %s: %d us (budget %d)",
            what, micros, budget));
        if (micros > budget) {
            throw fail(helper, what + " took " + micros + " us, over the " + budget + " us budget");
        }
    }

    /** A sealed dark chamber: a valid spawn spot, so the audit runs its full length. */
    private static BlockPos chamber(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        BlockPos spawn = new BlockPos(2, 2, 2);
        helper.setBlock(spawn, Blocks.AIR);
        helper.setBlock(spawn.above(), Blocks.AIR);
        return spawn;
    }

    private static GameTestAssertException fail(GameTestHelper helper, String message) {
        return new GameTestAssertException(message);
    }
}
