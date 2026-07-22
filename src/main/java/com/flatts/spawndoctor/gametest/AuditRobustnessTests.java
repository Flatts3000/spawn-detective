package com.flatts.spawndoctor.gametest;

import com.flatts.spawndoctor.audit.AuditReport;
import com.flatts.spawndoctor.audit.RuleResult;
import com.flatts.spawndoctor.audit.PositionReport;
import com.flatts.spawndoctor.audit.SpawnVerdict;
import com.flatts.spawndoctor.audit.SpawnAuditor;
import com.flatts.spawndoctor.audit.Verdict;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * The acceptance criterion, as tests: the auditor must produce an answer on any
 * world and any settings, vanilla or modded, and must never take the game down
 * with it.
 *
 * <p>A diagnostic tool that crashes on the pack it is meant to diagnose is worse
 * than useless, and the inputs it sees are entirely outside its control - any
 * mod's entity type, any mod's {@link MobCategory}, any dimension height, any
 * chunk state. So these tests do not check answers; they check that an answer
 * always comes back. They run against every entity type in the registry, which
 * means in a modded environment they automatically cover that pack's mobs too.
 */
final class AuditRobustnessTests {

    private AuditRobustnessTests() {
    }

    static void register() {
        SDGameTests.test("audit_survives_every_entity_type", 1, AuditRobustnessTests::everyEntityType);
        SDGameTests.test("audit_survives_extreme_heights", 1, AuditRobustnessTests::extremeHeights);
        SDGameTests.test("audit_survives_distant_unloaded_chunk", 1, AuditRobustnessTests::distantChunk);
        SDGameTests.test("audit_covers_every_spawning_category", 1, AuditRobustnessTests::everyCategory);
        SDGameTests.test("empty_categories_are_not_reported", 1, AuditRobustnessTests::emptyCategoriesHidden);
        SDGameTests.test("every_candidate_resolves", 1, AuditRobustnessTests::everyCandidateResolves);
        SDGameTests.test("verdict_never_contradicts_evidence", 1,
            AuditRobustnessTests::verdictNeverContradictsEvidence);
        SDGameTests.test("no_blame_without_a_culprit", 1, AuditRobustnessTests::noBlameWithoutACulprit);
    }

    /**
     * Never accuse a mod of something no mod did.
     *
     * <p>Third instance of one mistake, so it gets a test naming the pattern rather
     * than the symptom. The report told a player a mod was blocking spiders in an
     * instance whose only other mods were Jade and JEI. The reasoning was: our
     * obstruction check passed, our rules check passed, vanilla still refused, so a
     * mod must have done it. The real gap was that our obstruction check and
     * vanilla's used different entity predicates.
     *
     * <p>Same shape as blaming sky access for a cave: rule out the causes you can
     * measure, then pin the remainder on a suspect you never observed. A cause has to
     * be measured, and mod interference is measurable - vanilla's own halves are
     * callable, so a mod is only implicated when the actual result disagrees with
     * what vanilla alone would have decided.
     *
     * <p>This suite runs with no spawn-affecting mods loaded, so nothing may ever be
     * attributed to one.
     */
    private static void noBlameWithoutACulprit(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));

        List<String> accused = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue;
            }
            AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), pos, type);
            candidate.rules().stream()
                .filter(r -> r.rule() == com.flatts.spawndoctor.audit.SpawnRule.POSITION_CHECK)
                .filter(r -> r.value().toLowerCase().contains("mod"))
                .filter(r -> r.verdict().blocks())
                .findFirst()
                .ifPresent(r -> accused.add(
                    BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath() + " -> " + r.value()));
        }

        if (!accused.isEmpty()) {
            throw fail(helper, "blamed a mod with none loaded, for "
                + accused.size() + " type(s): "
                + String.join(", ", accused.subList(0, Math.min(3, accused.size()))));
        }
        helper.succeed();
    }

    /**
     * The verdict must agree with every rule shown beneath it.
     *
     * <p>Regression test with a precise origin. The banner once read "SKELETON CAN
     * SPAWN HERE - every gate passes" directly above a World &amp; chunk section
     * reading "blocked", because the reader stood ten blocks from the anchor and the
     * 24-block player rule had failed. The banner was consulting only the mob's own
     * gates.
     *
     * <p>It went unnoticed because the logic lived in the renderer, and this suite
     * runs server-side where no client class can load. Moving it into
     * {@link SpawnVerdict} is what makes this assertion possible at all - which is
     * the actual lesson: verdict logic is not presentation.
     */
    private static void verdictNeverContradictsEvidence(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        PositionReport position = SpawnAuditor.auditPosition(helper.getLevel(), pos);

        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue;
            }
            AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), pos, type);
            SpawnVerdict verdict = SpawnVerdict.of(position, candidate);

            boolean anythingBlocks = position.world().stream().anyMatch(r -> r.verdict().blocks())
                || candidate.rules().stream().anyMatch(r -> r.verdict().blocks());

            if (verdict.canSpawn() && anythingBlocks) {
                throw fail(helper, BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()
                    + ": verdict says it can spawn while a rule below says otherwise");
            }
            if (!verdict.canSpawn() && !anythingBlocks) {
                throw fail(helper, BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()
                    + ": verdict says blocked but no rule blocks");
            }
            if (!verdict.canSpawn() && verdict.blocker() == null) {
                throw fail(helper, BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()
                    + ": blocked with no blocker named");
            }
        }
        helper.succeed();
    }

    /**
     * A category the biome offers no mobs for must be dropped from the report and
     * must never have cap rules evaluated against it.
     *
     * <p>This is a regression test with a specific origin: the first build reported
     * "the water_creature cap is full: 5/5" *and* "no water_creature entries for
     * this biome" in a forest, on the same screen. Both lines were individually
     * true and together they were noise that buried the actual answer. Caps for a
     * category with nothing to spawn are not an answer to anyone's question.
     */
    private static void emptyCategoriesHidden(GameTestHelper helper) {
        AuditReport report = SpawnAuditor.audit(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)));

        for (AuditReport.Category category : report.categories()) {
            if (!category.candidates().isEmpty()) {
                continue;
            }
            if (category.relevant()) {
                throw fail(helper, "category " + category.category().getName()
                    + " has no mobs here but is marked relevant");
            }
            for (RuleResult result : category.rules()) {
                if (result.rule() != com.flatts.spawndoctor.audit.SpawnRule.BIOME_SPAWN_LIST) {
                    throw fail(helper, "category " + category.category().getName()
                        + " has no mobs here but still evaluated " + result.rule());
                }
            }
        }

        if (report.relevantCategories().stream().anyMatch(c -> c.candidates().isEmpty())) {
            throw fail(helper, "relevantCategories() returned a category with no mobs");
        }
        helper.succeed();
    }

    /**
     * Every mob in a whole-position sweep resolves to a verdict, and a blocked one
     * always names its blocker.
     *
     * <p>Replaces a test of the old aggregate headline, which averaged every mob the
     * biome offered into one sentence and so produced claims true of none of them.
     * The sweep survives because "what can spawn on this block" is a real question;
     * the averaging did not.
     */
    private static void everyCandidateResolves(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        AuditReport report = SpawnAuditor.audit(helper.getLevel(), pos);

        for (AuditReport.Category category : report.relevantCategories()) {
            for (AuditReport.Candidate candidate : category.candidates()) {
                SpawnVerdict verdict = SpawnVerdict.of(report.world(), candidate);
                if (!verdict.canSpawn() && verdict.blocker() == null) {
                    throw fail(helper, BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).getPath()
                        + " is blocked but names no blocker");
                }
                if (!verdict.canSpawn() && verdict.blocker().summary().isBlank()) {
                    throw fail(helper, BuiltInRegistries.ENTITY_TYPE.getKey(candidate.type()).getPath()
                        + " is blocked on " + verdict.blocker().rule() + " with no measurement");
                }
            }
        }
        helper.succeed();
    }

    /**
     * Walk every registered entity type through the per-type gates. In a modded
     * instance this covers that pack's mobs; in a bare dev environment it still
     * covers the awkward vanilla cases - non-mob types like arrows and item frames,
     * types with no spawn placement, and bosses.
     */
    private static void everyEntityType(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));

        List<String> broken = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            String name = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            try {
                AuditReport.Candidate candidate = SpawnAuditor.auditType(level, pos, type);
                if (candidate.rules().isEmpty()) {
                    broken.add(name + " (no rules evaluated)");
                }
            } catch (Throwable t) {
                broken.add(name + " (" + t + ")");
            }
        }

        if (!broken.isEmpty()) {
            throw fail(helper, "the auditor failed on " + broken.size() + " entity type(s): "
                + String.join(", ", broken.subList(0, Math.min(5, broken.size()))));
        }
        helper.succeed();
    }

    /**
     * Positions outside the build range are reachable by command and by a player
     * standing on bedrock, and the level will happily return air for them. The
     * auditor must answer rather than throw.
     */
    private static void extremeHeights(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(1, 2, 1));

        int[] heights = {
            level.getMinY() - 64,
            level.getMinY(),
            level.getMinY() + 1,
            level.getMaxY(),
            level.getMaxY() + 64
        };

        for (int y : heights) {
            BlockPos pos = new BlockPos(here.getX(), y, here.getZ());
            try {
                AuditReport report = SpawnAuditor.audit(level, pos);
                if (report.world().isEmpty()) {
                    throw fail(helper, "no world rules evaluated at y=" + y);
                }
            } catch (GameTestAssertException e) {
                throw e;
            } catch (Throwable t) {
                throw fail(helper, "the auditor threw at y=" + y + ": " + t);
            }
        }
        helper.succeed();
    }

    /**
     * Auditing an unloaded chunk must report "not entity-ticking" rather than
     * blocking the server thread on chunk generation. Players do this constantly -
     * they run the command with coordinates from a map, not from where they stand.
     */
    private static void distantChunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1)).offset(2_000_000, 0, 2_000_000);

        AuditReport report;
        try {
            report = SpawnAuditor.audit(level, pos);
        } catch (Throwable t) {
            throw fail(helper, "the auditor threw on a distant unloaded chunk: " + t);
        }

        boolean reportsChunkState = report.world().stream()
            .anyMatch(r -> r.rule() == com.flatts.spawndoctor.audit.SpawnRule.CHUNK_ENTITY_TICKING);
        if (!reportsChunkState) {
            throw fail(helper, "no chunk-ticking verdict for an unloaded chunk");
        }
        helper.succeed();
    }

    /**
     * Every spawning category must get a section. {@link MobCategory} is an
     * extensible enum, so a mod can add one; the walk must pick it up from
     * {@code values()} rather than from a hardcoded list, and must not report a
     * bogus cap failure for a category that declares no per-chunk maximum.
     */
    private static void everyCategory(GameTestHelper helper) {
        AuditReport report = SpawnAuditor.audit(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)));

        long expected = java.util.Arrays.stream(MobCategory.values())
            .filter(c -> c != MobCategory.MISC)
            .count();
        if (report.categories().size() != expected) {
            throw fail(helper, "expected " + expected + " category sections, got " + report.categories().size());
        }

        for (AuditReport.Category category : report.categories()) {
            if (category.rules().isEmpty()) {
                throw fail(helper, "category " + category.category().getName() + " evaluated no rules");
            }
            for (RuleResult result : category.rules()) {
                if (result.verdict() == Verdict.FAIL && result.detail().isBlank()) {
                    throw fail(helper, "category " + category.category().getName()
                        + " failed " + result.rule() + " with no reason given");
                }
            }
        }
        helper.succeed();
    }

    private static GameTestAssertException fail(GameTestHelper helper, String message) {
        return new GameTestAssertException(Component.literal(message), (int) helper.getTick());
    }
}
