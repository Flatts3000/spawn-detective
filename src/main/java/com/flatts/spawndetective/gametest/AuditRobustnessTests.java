package com.flatts.spawndetective.gametest;

import com.flatts.spawndetective.SpawnDetective;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.flatts.spawndetective.audit.AuditReport;
import com.flatts.spawndetective.audit.RuleResult;
import com.flatts.spawndetective.audit.PositionReport;
import com.flatts.spawndetective.audit.SpawnVerdict;
import com.flatts.spawndetective.audit.SpawnAuditor;
import com.flatts.spawndetective.audit.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
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
@GameTestHolder(SpawnDetective.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AuditRobustnessTests {

    private AuditRobustnessTests() {
    }

    /**
     * A qualification on a yes must be as accountable as a no.
     *
     * <p>Companion to {@link #verdictNeverContradictsEvidence}, and for the same
     * reason: the caveat is shown <i>in place of</i> "every gate passes", so an
     * invented one is a fabricated sentence in the most prominent position on the
     * screen. Three things have to hold - it is a rule that was actually walked, its
     * verdict really is MARGINAL, and it never appears beside a blocker, because two
     * competing explanations is how the old aggregate banner read.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void caveat_never_contradicts_evidence(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        PositionReport position = SpawnAuditor.auditPosition(helper.getLevel(), pos);

        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue;
            }
            String name = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
            AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), pos, type);
            SpawnVerdict verdict = SpawnVerdict.of(position, candidate);
            RuleResult caveat = verdict.caveat();
            if (caveat == null) {
                continue;
            }

            if (verdict.blocker() != null) {
                throw fail(helper, name + ": carries both a blocker and a caveat, so the report offers "
                    + "two answers to one question");
            }
            if (caveat.verdict() != Verdict.MARGINAL) {
                throw fail(helper, name + ": caveat on " + caveat.rule() + " is "
                    + caveat.verdict() + ", not a partial pass");
            }
            boolean walked = position.world().contains(caveat) || candidate.rules().contains(caveat);
            if (!walked) {
                throw fail(helper, name + ": caveat on " + caveat.rule()
                    + " is not one of the rules actually evaluated");
            }
            if (caveat.detail().isBlank()) {
                throw fail(helper, name + ": caveat on " + caveat.rule() + " qualifies the answer "
                    + "without saying how");
            }
        }
        helper.succeed();
    }

    /**
     * A candidate's rules must stay in pipeline order, for every type in the registry.
     *
     * <p>Order is not cosmetic here: {@code Candidate.blocker()} returns the first
     * blocking rule, so it names the first cause only while the list matches the order
     * vanilla decides in. That was implicit until the category caps started being
     * prepended to the per-type walk, at which point one misplaced insertion would
     * quietly start reporting the second reason a spawn failed as though it were the
     * first - a wrong answer that still looks like a complete report.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void candidate_rules_stay_in_pipeline_order(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));

        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue;
            }
            AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), pos, type);

            int furthest = -1;
            for (RuleResult result : candidate.rules()) {
                if (result.rule().ordinal() < furthest) {
                    throw fail(helper, BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath()
                        + ": " + result.rule() + " is walked after a later rule, so blocker() would "
                        + "name the wrong first cause");
                }
                furthest = result.rule().ordinal();
            }
        }
        helper.succeed();
    }

    /**
     * A rule that measures a rate must never be able to answer "no".
     *
     * <p>{@link com.flatts.spawndetective.audit.SpawnRule#ATTEMPT_REACH} reports how
     * often the spawner reaches a height. Slow is not shut - a neighbouring chunk's
     * pack can still walk in - and the rule sits in the world list, so a single FAIL
     * would become the headline for every mob at the position and have the mod say
     * "cannot spawn here" about a block that can. That is precisely the class of
     * confident wrongness this feature was added to remove, so it must not be
     * reintroduced by the fix for it.
     *
     * <p>Swept across the full build range, because the degenerate heights (the world
     * floor, above every column, outside the build range entirely) are where a rate
     * collapses to zero and a boolean would be tempting.
     */
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void informational_rules_never_block(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(1, 2, 1));

        int[] heights = {
            level.getMinBuildHeight() - 8, level.getMinBuildHeight(), level.getMinBuildHeight() + 1,
            here.getY(), here.getY() + 64, level.getMaxBuildHeight(), level.getMaxBuildHeight() + 8
        };

        for (int y : heights) {
            BlockPos pos = new BlockPos(here.getX(), y, here.getZ());
            PositionReport report = SpawnAuditor.auditPosition(level, pos);

            RuleResult reach = report.world().stream()
                .filter(r -> r.rule() == com.flatts.spawndetective.audit.SpawnRule.ATTEMPT_REACH)
                .findFirst()
                .orElseThrow(() -> fail(helper, "no attempt-reach row at y=" + y));

            if (reach.verdict().blocks()) {
                throw fail(helper, "attempt reach blocked a spawn at y=" + y
                    + ", which it must never do: " + reach.detail());
            }
            if (reach.value().isBlank() || reach.detail().isBlank()) {
                throw fail(helper, "attempt reach reported nothing measurable at y=" + y);
            }
        }
        helper.succeed();
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void no_blame_without_a_culprit(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));

        List<String> accused = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue;
            }
            AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), pos, type);
            candidate.rules().stream()
                .filter(r -> r.rule() == com.flatts.spawndetective.audit.SpawnRule.POSITION_CHECK)
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void verdict_never_contradicts_evidence(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        PositionReport position = SpawnAuditor.auditPosition(helper.getLevel(), pos);

        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (type.getCategory() == MobCategory.MISC) {
                continue;
            }
            AuditReport.Candidate candidate = SpawnAuditor.auditType(helper.getLevel(), pos, type);
            SpawnVerdict verdict = SpawnVerdict.of(position, candidate);

            // "Shown beneath it" means shown as this mob's evidence. A world row the
            // mob's category is not subject to renders as n/a, not as a red failure,
            // so counting it here would demand the verdict contradict what is on
            // screen: on peaceful every chicken would have to read as blocked.
            MobCategory category = type.getCategory();
            boolean anythingBlocks = Stream.concat(position.world().stream(), candidate.rules().stream())
                .anyMatch(r -> r.verdict().blocks() && r.rule().appliesTo(category));

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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void empty_categories_are_not_reported(GameTestHelper helper) {
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
                if (result.rule() != com.flatts.spawndetective.audit.SpawnRule.BIOME_SPAWN_LIST) {
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void every_candidate_resolves(GameTestHelper helper) {
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void audit_survives_every_entity_type(GameTestHelper helper) {
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void audit_survives_extreme_heights(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(1, 2, 1));

        int[] heights = {
            level.getMinBuildHeight() - 64,
            level.getMinBuildHeight(),
            level.getMinBuildHeight() + 1,
            level.getMaxBuildHeight(),
            level.getMaxBuildHeight() + 64
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void audit_survives_distant_unloaded_chunk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1)).offset(2_000_000, 0, 2_000_000);

        AuditReport report;
        try {
            report = SpawnAuditor.audit(level, pos);
        } catch (Throwable t) {
            throw fail(helper, "the auditor threw on a distant unloaded chunk: " + t);
        }

        boolean reportsChunkState = report.world().stream()
            .anyMatch(r -> r.rule() == com.flatts.spawndetective.audit.SpawnRule.CHUNK_ENTITY_TICKING);
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
    @GameTest(templateNamespace = SpawnDetective.MOD_ID, template = "empty_5x5x5", timeoutTicks = 1)
    public static void audit_covers_every_spawning_category(GameTestHelper helper) {
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
        return new GameTestAssertException(message);
    }
}
