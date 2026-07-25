package com.flatts.spawndetective.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.EventHooks;
import org.jspecify.annotations.Nullable;

/**
 * Replays {@link NaturalSpawner}'s decision chain against one block position and
 * records why each gate passed or failed.
 *
 * <p>The contract this class lives or dies by: <b>every rule here mirrors a real
 * call site</b>, in the real order, with the real thresholds read from the live
 * world - never a hardcoded copy of what vanilla "usually" does. Each method
 * names the vanilla method it stands in for. When Mojang changes the pipeline,
 * these are the places that must change with it.
 *
 * <p>Two rules are deliberately not booleans:
 * <ul>
 *   <li>{@link SpawnRule#SPAWN_RULES} samples, because
 *       {@code Monster.isDarkEnoughToSpawn} rolls the RNG - a single call would
 *       report a coin flip as a fact.</li>
 *   <li>{@link SpawnRule#BIOME_SPAWN_LIST} reports weights, because "possible"
 *       and "likely" are different answers to the player's question.</li>
 * </ul>
 */
public final class SpawnAuditor {

    /**
     * Rolls per sampled rule. The light test samples a uniform int and compares
     * against the dimension's light-test provider, so ~64 rolls resolves a
     * borderline position to within a couple of percent - enough to tell
     * "never" from "rarely", which is the distinction players care about.
     */
    private static final int SAMPLES = 64;

    /** {@code NaturalSpawner.MIN_SPAWN_DISTANCE} - 24 blocks, as a squared distance. */
    private static final double MIN_PLAYER_DISTANCE_SQR = 576.0;

    /** {@code NaturalSpawner.MAGIC_NUMBER} - 17^2, the global-cap divisor. */
    private static final int GLOBAL_CAP_DIVISOR = 289;

    /** Sentinel from {@link #sample}: a mod's spawn predicate threw, so there is no measurement. */
    private static final int SAMPLE_THREW = -1;

    /**
     * Independent seeds the attribution must agree across before it makes a claim.
     *
     * <p>Pairing spawn reasons on one seed makes the comparison a controlled
     * experiment only while the predicate draws the same number of random values
     * each way. Some do not - {@code Slime.checkSlimeSpawnRules} short-circuits
     * through a chain of {@code nextFloat} calls - so a single seed can desynchronise
     * the runs and produce a difference that has nothing to do with the exemption.
     * That is how a cave got blamed on sky access. Requiring several seeds to agree
     * costs a few hundred cheap predicate calls and removes the whole class of
     * false attribution.
     */
    private static final int ATTRIBUTION_SEEDS = 3;

    /**
     * Ceiling on mob types walked per category. Every candidate costs a few hundred
     * predicate calls and one throwaway entity, and a heavily modded biome can list
     * far more monsters than a player will ever read. The report says explicitly when
     * this truncates - a silently shortened list reads as "I checked everything".
     */
    private static final int MAX_CANDIDATES_PER_CATEGORY = 48;

    private SpawnAuditor() {
    }

    /**
     * The cheap half: what is true of this position for any mob at all.
     *
     * <p>This is what a probe click runs. It walks the world and chunk gates and
     * collects the mobs the biome offers here, but deliberately does not touch the
     * per-mob pipeline - that costs hundreds of predicate rolls per mob and is only
     * worth paying once someone has named the mob they care about.
     */
    public static PositionReport auditPosition(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        return new PositionReport(
            level.dimension().identifier().toString(),
            pos,
            biome.getRegisteredName(),
            auditWorldAndChunk(level, pos),
            suggestedTypes(level, pos, biome));
    }

    /**
     * The mobs this biome actually offers here, heaviest first.
     *
     * <p>Feeds the picker's empty state. Someone who has just opened the screen may
     * not know what to type, and "here are the things that could spawn here" is a
     * far better prompt than a blank field.
     */
    private static List<EntityType<?>> suggestedTypes(ServerLevel level, BlockPos pos, Holder<Biome> biome) {
        List<Weighted<MobSpawnSettings.SpawnerData>> entries = new ArrayList<>();
        for (MobCategory category : new MobCategory[] {MobCategory.MONSTER, MobCategory.CREATURE}) {
            try {
                entries.addAll(mobsAt(level, category, pos, biome).unwrap());
            } catch (Throwable t) {
                // A custom generator threw; the picker simply offers fewer leads.
            }
        }
        entries.sort(Comparator.comparingInt(Weighted<MobSpawnSettings.SpawnerData>::weight).reversed());

        List<EntityType<?>> types = new ArrayList<>();
        for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
            EntityType<?> type = entry.value().type();
            if (!types.contains(type)) {
                types.add(type);
            }
            if (types.size() >= MAX_SUGGESTIONS) {
                break;
            }
        }
        return types;
    }

    /** Quick picks offered when no mob has been chosen yet. */
    private static final int MAX_SUGGESTIONS = 10;

    /**
     * The full sweep: every mob the biome offers here, each walked end to end.
     *
     * <p>Used by the command and the tests. The screen deliberately does not use
     * this - it asks about one named mob, because averaging many mobs' answers into
     * one banner produces a sentence true of none of them.
     */
    public static AuditReport audit(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        List<RuleResult> world = auditWorldAndChunk(level, pos);

        List<AuditReport.Category> categories = new ArrayList<>();
        for (MobCategory category : MobCategory.values()) {
            if (category == MobCategory.MISC) {
                continue; // NaturalSpawner.SPAWNING_CATEGORIES filters MISC out.
            }
            categories.add(auditCategory(level, pos, biome, category));
        }

        return new AuditReport(
            level.dimension().identifier().toString(), pos, biome.getRegisteredName(), world, categories);
    }

    // ------------------------------------------------------------------ world

    /**
     * The gates that do not depend on which mob is being spawned.
     *
     * <p>Mirrors, in order: {@code ServerChunkCache.tickChunks} (gamerule),
     * {@code ServerChunkCache.tickSpawningChunk} (chunk eligibility),
     * {@code ChunkMap.anyPlayerCloseEnoughForSpawning}, and the two distance
     * checks in {@code NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint}.
     */
    private static List<RuleResult> auditWorldAndChunk(ServerLevel level, BlockPos pos) {
        List<RuleResult> results = new ArrayList<>();
        ChunkPos chunkPos = ChunkPos.containing(pos);

        // ServerChunkCache.tickChunks: doMobSpawning false empties the category list.
        boolean doMobSpawning = level.getGameRules().get(GameRules.SPAWN_MOBS);
        results.add(RuleResult.of(SpawnRule.GAMERULE_MOB_SPAWNING, doMobSpawning,
            "true", "doMobSpawning is true",
            "false", "doMobSpawning is false - nothing spawns naturally anywhere in this world"));

        // Monster.checkMonsterSpawnRules: peaceful rejects every monster. Animals
        // still spawn, so this is reported rather than treated as a global stop.
        Difficulty difficulty = level.getDifficulty();
        results.add(difficulty == Difficulty.PEACEFUL
            ? RuleResult.fail(SpawnRule.DIFFICULTY, "peaceful",
                "Peaceful - monsters cannot spawn (animals still can)")
            : RuleResult.pass(SpawnRule.DIFFICULTY, difficulty.getSerializedName(),
                "difficulty is " + difficulty.getSerializedName()));

        // ServerLevel.canSpawnEntitiesInChunk, split into its two halves so the
        // report names which one failed.
        boolean withinBorder = level.getWorldBorder().isWithinBounds(chunkPos);
        results.add(RuleResult.of(SpawnRule.WORLD_BORDER, withinBorder,
            "inside", "inside the world border",
            "outside", "outside the world border - nothing spawns beyond it"));

        boolean canTick = level.canSpawnEntitiesInChunk(chunkPos);
        results.add(RuleResult.of(SpawnRule.CHUNK_ENTITY_TICKING, canTick || !withinBorder,
            chunkPos.toString(), "chunk " + chunkPos + " ticks entities",
            chunkPos + " idle",
            "chunk " + chunkPos + " is not entity-ticking (outside simulation distance, or not loaded)"));

        // ChunkMap.anyPlayerCloseEnoughForSpawning - the 128-block spawn sphere.
        boolean playerNear = level.anyPlayerCloseEnoughForSpawning(pos);
        results.add(RuleResult.of(SpawnRule.PLAYER_IN_SPAWN_RANGE, playerNear,
            "yes", "a player is within 128 blocks of this chunk",
            "none within 128", "no player within 128 blocks of this chunk - it is never picked for a spawn attempt"));

        // NaturalSpawner.spawnCategoryForChunk -> getRandomPosWithin: how often an
        // attempt in this chunk anchors at this Y at all. Informational and never a
        // FAIL - it is the odds beside the verdict, not another way to say no.
        results.add(SpawnAttemptReach.audit(level, pos));

        // NaturalSpawner.spawnCategoryForPosition: the attempt is discarded outright
        // if the anchor block conducts redstone. Advisory, because the real anchor is
        // a random position in the chunk that shares this Y level.
        BlockState state = level.getBlockState(pos);
        boolean conductor = state.isRedstoneConductor(level, pos);
        results.add(RuleResult.of(SpawnRule.ANCHOR_NOT_CONDUCTOR, !conductor,
            describeBlock(state), describeBlock(state) + " does not conduct redstone",
            describeBlock(state) + " conducts",
            describeBlock(state) + " is a redstone conductor - a spawn attempt anchored here is discarded"));

        // NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint - 24 blocks from any player.
        Player nearest = level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, -1.0, false);
        if (nearest == null) {
            results.add(RuleResult.fail(SpawnRule.PLAYER_DISTANCE, "no players",
                "no player in this dimension - a spawn attempt needs one"));
        } else {
            double distSqr = nearest.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            double distance = Math.sqrt(distSqr);
            String measured = String.format("%.1f blocks", distance);
            results.add(RuleResult.of(SpawnRule.PLAYER_DISTANCE, distSqr > MIN_PLAYER_DISTANCE_SQR,
                measured, String.format("%.1f blocks from %s, past the 24-block bubble",
                    distance, nearest.getName().getString()),
                measured + " / 24", String.format("%.1f blocks from %s - inside the 24-block no-spawn bubble",
                    distance, nearest.getName().getString())));
        }

        // The same method's world-spawn check.
        LevelData.RespawnData respawn = level.getRespawnData();
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        boolean nearWorldSpawn = respawn.dimension() == level.dimension()
            && respawn.pos().closerToCenterThan(center, 24.0);
        results.add(RuleResult.of(SpawnRule.WORLD_SPAWN_DISTANCE, !nearWorldSpawn,
            "outside", "outside the world spawn bubble",
            "inside", "within 24 blocks of the world spawn point " + respawn.pos()));

        return results;
    }

    // --------------------------------------------------------------- category

    private static AuditReport.Category auditCategory(
        ServerLevel level, BlockPos pos, Holder<Biome> biome, MobCategory category
    ) {
        List<RuleResult> rules = new ArrayList<>();

        // The biome spawn list is resolved FIRST, ahead of the caps that vanilla
        // checks earlier. Not to mirror the pipeline, but because a category the
        // biome offers nothing for has exactly one honest thing to say - and
        // reporting "the water_creature cap is full" in a forest that has no water
        // creatures is noise that buries the real answer.
        List<Weighted<MobSpawnSettings.SpawnerData>> entries;
        try {
            entries = mobsAt(level, category, pos, biome).unwrap();
        } catch (Throwable t) {
            // A custom chunk generator or a PotentialSpawns handler blew up. Say so
            // rather than reporting an empty list as "this biome offers nothing".
            rules.add(RuleResult.unknown(SpawnRule.BIOME_SPAWN_LIST,
                "could not read the spawn list for this category: " + t));
            return new AuditReport.Category(category, rules, List.of());
        }
        if (entries.isEmpty()) {
            rules.add(RuleResult.fail(SpawnRule.BIOME_SPAWN_LIST, "none",
                "no " + category.getName() + " entries for this biome/structure"));
            return new AuditReport.Category(category, rules, List.of());
        }

        rules.addAll(categoryCapRules(level, pos, category));

        int totalWeight = entries.stream().mapToInt(Weighted::weight).sum();

        // Heaviest entries first: if the list gets truncated, what survives is what a
        // spawn attempt would most likely have rolled.
        List<Weighted<MobSpawnSettings.SpawnerData>> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingInt(Weighted<MobSpawnSettings.SpawnerData>::weight).reversed());

        boolean truncated = ordered.size() > MAX_CANDIDATES_PER_CATEGORY;
        rules.add(RuleResult.pass(SpawnRule.BIOME_SPAWN_LIST,
            entries.size() + " entries",
            truncated
                ? entries.size() + " entries - walking the " + MAX_CANDIDATES_PER_CATEGORY + " heaviest"
                : entries.size() + " entries offered here"));
        if (truncated) {
            ordered = ordered.subList(0, MAX_CANDIDATES_PER_CATEGORY);
        }

        List<AuditReport.Candidate> candidates = new ArrayList<>(ordered.size());
        for (Weighted<MobSpawnSettings.SpawnerData> entry : ordered) {
            // No cap rules here: the sweep already printed them once above, and a
            // shut cap repeated on each of forty mobs buries its own reason.
            candidates.add(auditCandidate(
                level, pos, biome, category, entry.value().type(), entry.weight(), totalWeight, List.of()));
        }

        return new AuditReport.Category(category, rules, candidates);
    }

    /**
     * The two per-category caps, as their own unit because two different callers
     * need them.
     *
     * <p>The sweep prints them once at the head of a category. {@link #auditType}
     * prepends them to the one mob it was asked about, because that is the path the
     * screen, Jade and {@code /spawndetective for} all take - and until this was
     * split out, none of those three surfaces showed a cap row at all. A report that
     * silently omits the gate a player is actually sitting against is the same
     * failure as reporting it wrongly.
     *
     * <p>Mirrors {@code NaturalSpawner.SpawnState.canSpawnForCategoryGlobal} and
     * {@code LocalMobCapCalculator.canSpawn}. The live counts come from the spawn
     * state the server built on its last spawn tick; if the server has not run one
     * yet there is nothing honest to report.
     */
    private static List<RuleResult> categoryCapRules(ServerLevel level, BlockPos pos, MobCategory category) {
        NaturalSpawner.SpawnState state = level.getChunkSource().getLastSpawnState();
        if (state == null) {
            return List.of(
                RuleResult.unknown(SpawnRule.CATEGORY_GLOBAL_CAP,
                    "no spawn state yet - the server has not run a spawn tick"),
                RuleResult.unknown(SpawnRule.CATEGORY_LOCAL_CAP,
                    "no spawn state yet - the server has not run a spawn tick"));
        }
        if (category.getMaxInstancesPerChunk() <= 0) {
            // MobCategory is an extensible enum: a mod can add a category with a
            // non-positive per-chunk max (vanilla MISC uses -1). The cap formula would
            // report "0 / 0 used" as a permanent failure, which would be a lie.
            String note = "this category declares no per-chunk maximum ("
                + category.getMaxInstancesPerChunk() + ") - caps do not apply";
            return List.of(
                RuleResult.skipped(SpawnRule.CATEGORY_GLOBAL_CAP, note),
                RuleResult.skipped(SpawnRule.CATEGORY_LOCAL_CAP, note));
        }

        int count = state.getMobCategoryCounts().getInt(category);
        int cap = category.getMaxInstancesPerChunk() * state.getSpawnableChunkCount() / GLOBAL_CAP_DIVISOR;
        if (cap <= 0) {
            // Same degeneracy as the branch above, from the other input. With nobody
            // close enough to make chunks spawnable the formula collapses to zero, and
            // vanilla's own "count < cap" then refuses - so the row would read "cap
            // full: 0 of 0" and send a player off to kill mobs that do not exist. The
            // real finding is that no player is near, which PLAYER_IN_SPAWN_RANGE
            // already reports honestly; repeating it here as a cap failure would be
            // the mod blaming the wrong rule, and on the single-mob path it would be
            // the headline.
            String note = "no spawnable chunks in this dimension ("
                + state.getSpawnableChunkCount() + "), so the cap formula yields zero - "
                + "nobody is close enough for a cap to mean anything";
            return List.of(
                RuleResult.skipped(SpawnRule.CATEGORY_GLOBAL_CAP, note),
                RuleResult.skipped(SpawnRule.CATEGORY_LOCAL_CAP, note));
        }

        return List.of(
            globalCapResult(count, cap),
            auditLocalCap(state, category, ChunkPos.containing(pos)));
    }

    /**
     * The global cap as a rule row: PASS under it, MARGINAL at it, never FAIL.
     *
     * <p>A full cap is not a defect, it is the steady state. One player makes the
     * spawn-eligible area 17x17 chunks, which is exactly {@code MAGIC_NUMBER}, so the
     * cap is exactly 70 monsters - and in any overworld with caves under it the count
     * sits pinned at that ceiling more or less permanently. Reporting that as a
     * definitive no made "the mob cap is full" the headline for most probes in a
     * normal world, which buries the per-block answer the probe was pointed at. The
     * mod already learned this shape once with {@code PLAYER_DISTANCE}: a condition
     * that is nearly always true is nearly always the headline, and then the headline
     * stops being an answer.
     *
     * <p>It is also not true block-to-block. {@code getFilteredSpawningCategories}
     * rebuilds this every tick and mobs die and despawn continuously, so the cap
     * oscillates at its ceiling many times a second and spawns keep happening
     * throughout. That is why lighting nearby caves helps a farm and why farms work
     * at all. The honest reading is competition, not refusal - which is what
     * {@link Verdict#MARGINAL} means: it permits a spawn, but only some of the time.
     *
     * <p>Pure and package-visible so the decision can be unit-tested. Constructing a
     * full cap in a GameTest is not possible - a headless test server has no players,
     * so it has no spawnable chunks and no cap to fill.
     */
    static RuleResult globalCapResult(int count, int cap) {
        String measured = count + " / " + cap;
        if (count < cap) {
            // Rendered on the passing branch too. "Under the cap" and "62 of 70 used"
            // are different answers: the second says how much room is left before the
            // verdict changes, which is what someone watching a farm fill up is asking.
            return RuleResult.pass(SpawnRule.CATEGORY_GLOBAL_CAP, measured,
                count + " of " + cap + " used across the dimension");
        }
        return RuleResult.marginal(SpawnRule.CATEGORY_GLOBAL_CAP, measured + " FULL",
            "all " + cap + " slots are taken across the dimension, so this mob competes for one as "
                + "others die or despawn",
            "Light up caves nearby, or clear mobs elsewhere, so fewer spawns go to them instead.");
    }

    /**
     * {@code LocalMobCapCalculator.canSpawn}, reached through the spawn state's
     * calculator (access-transformed - the field is private and there is no getter).
     *
     * <p>Reported as a boolean rather than a count. The per-player numbers live in a
     * private map behind a private nested class, and the three further access
     * transformers that would reach them bind this mod to an inner class name for a
     * figure the global row already carries in actionable form.
     */
    private static RuleResult auditLocalCap(NaturalSpawner.SpawnState state, MobCategory category, ChunkPos chunkPos) {
        try {
            return localCapResult(
                state.localMobCapCalculator.canSpawn(category, chunkPos), category.getMaxInstancesPerChunk());
        } catch (Throwable t) {
            return RuleResult.unknown(SpawnRule.CATEGORY_LOCAL_CAP, "could not read the local cap: " + t);
        }
    }

    /** The per-player cap, MARGINAL when full for the same reason as {@link #globalCapResult}. */
    static RuleResult localCapResult(boolean ok, int max) {
        if (ok) {
            return RuleResult.pass(SpawnRule.CATEGORY_LOCAL_CAP, "under " + max,
                "at least one nearby player is under their cap of " + max);
        }
        return RuleResult.marginal(SpawnRule.CATEGORY_LOCAL_CAP, "at " + max + " FULL",
            "every player near this chunk is at their cap of " + max + ", so this mob competes for a slot "
                + "as others die or despawn",
            "Clear mobs near the players around this chunk, or move your AFK spot.");
    }

    /**
     * {@code NaturalSpawner.mobsAt} - the biome list, with the nether-fortress
     * override and NeoForge's PotentialSpawns event applied exactly as vanilla does.
     */
    private static WeightedList<MobSpawnSettings.SpawnerData> mobsAt(
        ServerLevel level, MobCategory category, BlockPos pos, Holder<Biome> biome
    ) {
        StructureManager structureManager = level.structureManager();
        ChunkGenerator generator = level.getChunkSource().getGenerator();

        if (NaturalSpawner.isInNetherFortressBounds(pos, level, category, structureManager)) {
            Structure fortress = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .getValue(BuiltinStructures.FORTRESS);
            if (fortress != null) {
                StructureSpawnOverride override = fortress.spawnOverrides().get(MobCategory.MONSTER);
                if (override != null) {
                    return EventHooks.getPotentialSpawns(level, category, pos, override.spawns());
                }
            }
        }

        return EventHooks.getPotentialSpawns(
            level, category, pos, generator.getMobsAt(biome, structureManager, category, pos));
    }

    // -------------------------------------------------------------- candidate

    /**
     * Walk one specific mob type through the per-type gates, ignoring whether the
     * biome would ever offer it here.
     *
     * <p>"Why won't <i>this</i> mob spawn?" is a different question from "why won't
     * anything spawn?", and answering it needs a way in that skips the biome list -
     * otherwise a mob missing from the list has no diagnosis at all beyond its
     * absence. Weights are reported as 0 of 0 because there is no list entry.
     *
     * <p>The category's caps ride along, because this is the path every interactive
     * surface takes - the screen, the Jade tooltip and {@code /spawndetective for}
     * all resolve through here, and none of them showed a cap row until they did.
     */
    public static AuditReport.Candidate auditType(ServerLevel level, BlockPos pos, EntityType<?> type) {
        MobCategory category = type.getCategory();
        return auditCandidate(level, pos, level.getBiome(pos), category, type, 0, 0,
            categoryCapRules(level, pos, category));
    }

    /**
     * The per-type gates from {@code NaturalSpawner.isValidSpawnPostitionForType}
     * and {@code isValidPositionForMob}, in that order.
     *
     * @param leading rules decided before the per-type walk, prepended so the list
     *                stays in pipeline order and {@code blocker()} keeps naming the
     *                first real cause rather than the first per-mob one
     */
    private static AuditReport.Candidate auditCandidate(
        ServerLevel level,
        BlockPos pos,
        Holder<Biome> biome,
        MobCategory category,
        EntityType<?> type,
        int weight,
        int totalWeight,
        List<RuleResult> leading
    ) {
        List<RuleResult> rules = new ArrayList<>(leading);

        rules.add(RuleResult.of(SpawnRule.TYPE_SUMMONABLE, type.canSummon(),
            "yes", "summonable",
            "no", "this entity type is flagged not-summonable and can never spawn naturally"));

        // isValidSpawnPostitionForType: mobs that cannot spawn far from a player are
        // rejected beyond their category's despawn distance.
        Player nearest = level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, -1.0, false);
        int despawn = category.getDespawnDistance();
        if (nearest == null) {
            rules.add(RuleResult.skipped(SpawnRule.DESPAWN_DISTANCE, "no player to measure from"));
        } else if (type.canSpawnFarFromPlayer()) {
            rules.add(RuleResult.pass(SpawnRule.DESPAWN_DISTANCE, "any", "this mob may spawn at any distance"));
        } else {
            double distSqr = nearest.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            boolean ok = distSqr <= (double) despawn * despawn;
            String measured = String.format("%.0f / %d", Math.sqrt(distSqr), despawn);
            rules.add(RuleResult.of(SpawnRule.DESPAWN_DISTANCE, ok,
                measured, String.format("%.1f blocks from a player, within this mob's %d-block limit",
                    Math.sqrt(distSqr), despawn),
                measured, String.format("%.1f blocks from a player, past this mob's %d-block limit",
                    Math.sqrt(distSqr), despawn)));
        }

        // SpawnPlacements.isSpawnPositionOk, decomposed into the specific sub-check
        // that failed instead of a bare boolean.
        rules.add(auditPlacement(level, pos, type));

        // SpawnPlacements.checkSpawnRules - sampled, then attributed.
        rules.add(auditSpawnRules(level, pos, type));

        // NaturalSpawner: level.noCollision(type.getSpawnAABB(...)).
        AABB aabb = type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        String hitbox = String.format("%.1f x %.1f", type.getWidth(), type.getHeight());
        rules.add(RuleResult.of(SpawnRule.NO_COLLISION, level.noCollision(aabb),
            hitbox + " fits", hitbox + " hitbox fits here",
            hitbox + " blocked", hitbox + " hitbox collides with a block here"));

        // NaturalSpawner.SpawnState.canSpawn - the biome's spawn-cost charge budget.
        rules.add(auditSpawnCharge(level, pos, biome, type));

        // EventHooks.checkSpawnPosition - the last gate, and where other mods veto.
        rules.addAll(auditObstructionAndVeto(level, pos, type));

        return new AuditReport.Candidate(type, weight, totalWeight, rules);
    }

    /**
     * {@code SpawnPlacements.isSpawnPositionOk}, decomposed per placement type.
     *
     * <p>Vanilla returns one boolean for "is the physical spot right". That is
     * never a useful answer, so this walks the same checks by hand and names the
     * one that failed: no floor, no headroom, fluid in the way, and so on.
     */
    private static RuleResult auditPlacement(ServerLevel level, BlockPos pos, EntityType<?> type) {
        SpawnPlacementType placement = SpawnPlacements.getPlacementType(type);
        boolean vanilla = SpawnPlacements.isSpawnPositionOk(type, level, pos);

        if (placement == SpawnPlacementTypes.NO_RESTRICTIONS) {
            return RuleResult.of(SpawnRule.PLACEMENT, vanilla,
                "unrestricted", "no placement restrictions for this mob",
                "rejected", "rejected by a custom placement type");
        }

        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return RuleResult.fail(SpawnRule.PLACEMENT, "outside border", "outside the world border");
        }

        if (placement == SpawnPlacementTypes.IN_WATER) {
            FluidState fluid = level.getFluidState(pos);
            if (!fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                String found = describeBlock(level.getBlockState(pos));
                return RuleResult.fail(SpawnRule.PLACEMENT, "no water, " + found,
                    "needs water at this position, found " + found,
                    "This mob only spawns in water. Flood the space, or expect a land mob.");
            }
            BlockPos above = pos.above();
            if (level.getBlockState(above).isRedstoneConductor(level, above)) {
                return RuleResult.fail(SpawnRule.PLACEMENT, "capped above",
                    "the block above (" + describeBlock(level.getBlockState(above)) + ") is a redstone conductor",
                    "Clear the solid block directly above this water.");
            }
            return RuleResult.pass(SpawnRule.PLACEMENT, "water ok", "water with clear headroom");
        }

        if (placement == SpawnPlacementTypes.IN_LAVA) {
            boolean lava = level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA);
            return RuleResult.of(SpawnRule.PLACEMENT, lava,
                "lava", "lava",
                "no lava", "needs lava at this position, found " + describeBlock(level.getBlockState(pos)));
        }

        if (placement == SpawnPlacementTypes.ON_GROUND) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isValidSpawn(level, below, type)) {
                return RuleResult.fail(SpawnRule.PLACEMENT, "floor: " + describeBlock(belowState),
                    "the floor (" + describeBlock(belowState) + ") does not allow this mob to spawn on it",
                    "Replace the floor with a full solid block, or leave it as a spawn-proof surface.");
            }
            String here = emptySpawnBlockProblem(level, pos, type);
            if (here != null) {
                return RuleResult.fail(SpawnRule.PLACEMENT, "blocked", "the spawn block itself: " + here,
                    "Clear this block so a mob has somewhere to stand.");
            }
            String above = emptySpawnBlockProblem(level, pos.above(), type);
            if (above != null) {
                return RuleResult.fail(SpawnRule.PLACEMENT, "no headroom", "no headroom: " + above,
                    "Clear the block above, or leave it to keep this spot spawn-proof.");
            }
            return RuleResult.pass(SpawnRule.PLACEMENT, "on " + describeBlock(belowState),
                "standing on " + describeBlock(belowState) + " with clear headroom");
        }

        // A mod's own SpawnPlacementType - opaque, so report the boolean honestly.
        String owner = placement.getClass().getName();
        return RuleResult.of(SpawnRule.PLACEMENT, vanilla,
            "custom ok", "accepted by " + owner,
            "custom reject", "rejected by " + owner + " (a custom placement type from another mod)");
    }

    /**
     * {@code NaturalSpawner.isValidEmptySpawnBlock}, returning the reason it failed
     * rather than a boolean. Null means the block is fine.
     */
    private static @Nullable String emptySpawnBlockProblem(ServerLevel level, BlockPos pos, EntityType<?> type) {
        BlockState state = level.getBlockState(pos);
        FluidState fluid = state.getFluidState();
        String name = describeBlock(state);

        if (state.isCollisionShapeFullBlock(level, pos)) {
            return name + " is a full solid block";
        }
        if (state.isSignalSource()) {
            return name + " emits a redstone signal";
        }
        if (!fluid.isEmpty()) {
            return name + " contains fluid";
        }
        if (state.is(net.minecraft.tags.BlockTags.PREVENT_MOB_SPAWNING_INSIDE)) {
            return name + " is tagged prevent_mob_spawning_inside";
        }
        if (type.isBlockDangerous(state)) {
            return name + " is dangerous to this mob";
        }
        return null;
    }

    /**
     * {@code SpawnPlacements.checkSpawnRules} - sampled, then attributed to a cause.
     *
     * <p>The predicate is a black box: mods register their own, and vanilla's own
     * are opaque compositions of light, floor, sky, biome and height checks. Rather
     * than reimplement them (which would rot, and would be wrong for modded mobs),
     * this re-runs the same predicate under two spawn reasons that vanilla itself
     * defines as exemptions:
     *
     * <ul>
     *   <li>{@code SPAWNER} skips {@code Mob.checkMobSpawnRules} (the block-below
     *       check) and the surface-monster sky check.</li>
     *   <li>{@code TRIAL_SPAWNER} additionally skips the light requirement.</li>
     * </ul>
     *
     * If NATURAL fails but SPAWNER passes, the cause is the floor or sky access; if
     * SPAWNER fails but TRIAL_SPAWNER passes, the cause is light. This works for any
     * mod's predicate that is built out of the vanilla helpers, and degrades to an
     * honest "some other rule" when it is not.
     */
    private static RuleResult auditSpawnRules(ServerLevel level, BlockPos pos, EntityType<?> type) {
        if (!SpawnPlacements.hasPlacement(type)) {
            return RuleResult.pass(SpawnRule.SPAWN_RULES, "none", "no spawn rules registered for this mob");
        }

        // One seed for every sample set below. The predicate rolls the RNG, so
        // drawing fresh randomness per set would let luck alone create a difference
        // between spawn reasons - and the whole attribution reads that difference as
        // causal. Same seed makes it a controlled experiment.
        long[] seeds = new long[ATTRIBUTION_SEEDS];
        for (int i = 0; i < seeds.length; i++) {
            seeds[i] = level.getRandom().nextLong();
        }
        long seed = seeds[0];

        int natural = sampleAcross(level, pos, type, EntitySpawnReason.NATURAL, seeds);
        if (natural == SAMPLE_THREW) {
            return RuleResult.unknown(SpawnRule.SPAWN_RULES,
                "this mob's spawn predicate raised an exception - report it to that mod, not this one");
        }
        int total = SAMPLES * ATTRIBUTION_SEEDS;
        if (natural == total) {
            return RuleResult.pass(SpawnRule.SPAWN_RULES, "pass", "passes every roll");
        }

        Cause cause = attributeSpawnRuleFailure(level, pos, type, seeds, natural);
        // Terse: this rides in a narrow table column beside the rule's name.
        String rate = natural == 0 ? "" : " (" + Math.round(100.0F * natural / total) + "% of rolls)";
        String detail = natural == 0
            ? "fails every roll - " + cause.detail()
            : natural + " of " + total + " rolls pass - " + cause.detail();

        return natural == 0
            ? RuleResult.fail(SpawnRule.SPAWN_RULES, cause.value(), detail, cause.remedy())
            : RuleResult.marginal(SpawnRule.SPAWN_RULES, cause.value() + rate, detail, cause.remedy());
    }

    /** What the auditor concluded, in the three lengths the report needs. */
    private record Cause(String value, String detail, @Nullable String remedy) {
    }

    /**
     * Work out which input the predicate actually objected to.
     *
     * <p>Runs the same predicate under two spawn reasons vanilla defines as
     * exemptions - {@code SPAWNER} skips the block-below check and the
     * surface-monster sky check, {@code TRIAL_SPAWNER} additionally skips the light
     * requirement - and reads which exemption flips the result.
     *
     * <p>The claim is only ever as strong as the evidence. If exempting the floor
     * and sky group fixes it, the floor is checked directly; sky is named only when
     * the floor is fine <i>and</i> this position genuinely has no sky, because most
     * monsters have no sky requirement and every cave fails a naive sky test.
     */
    private static Cause attributeSpawnRuleFailure(
        ServerLevel level, BlockPos pos, EntityType<?> type, long[] seeds, int natural
    ) {
        int asSpawner = sampleAcross(level, pos, type, EntitySpawnReason.SPAWNER, seeds);
        int asTrial = sampleAcross(level, pos, type, EntitySpawnReason.TRIAL_SPAWNER, seeds);

        if (asSpawner == SAMPLE_THREW || asTrial == SAMPLE_THREW) {
            return new Cause("not attributable",
                "this mob's predicate raised under a different spawn reason, so the cause cannot be isolated. "
                    + describeLight(level, pos), null);
        }

        // An exemption identifies the cause by IMPROVING the rate, not by passing.
        // Testing "does SPAWNER pass at all" blamed the floor group for a lit spider
        // whose floor was fine: exempting the floor changed nothing, both sat at the
        // same rate, and the branch fired anyway.
        boolean floorGroupHelps = asSpawner > natural;
        boolean lightHelps = asTrial > Math.max(natural, asSpawner);

        if (floorGroupHelps) {
            // The floor is the one member of that group that can be checked directly,
            // so it is the one member that may be named.
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isValidSpawn(level, below, type)) {
                return new Cause("floor: " + describeBlock(belowState),
                    "the floor. " + describeBlock(belowState) + " is not a valid spawn surface for this mob",
                    "Give this spot a full solid floor, or leave it as-is to keep it spawn-proof.");
            }
            // Everything else in that group is opaque - naming a member by elimination
            // is how "drowned need sky" happened - so the group is reported as a group.
            String leads = level.canSeeSky(pos)
                ? "water, or being inside a particular structure"
                : "open sky, water, or being inside a particular structure";
            return new Cause("needs something else",
                "this mob asks for something a spawner would provide but this spot does not. The floor and "
                    + "the light are both fine, so it is a condition of its own - commonly " + leads,
                "A monster spawner would work here; natural spawning will not.");
        }

        if (lightHelps) {
            return new Cause(lightValue(level, pos),
                "light. " + describeLight(level, pos),
                lightRemedy(level, pos));
        }

        return new Cause("another rule",
            "neither light nor the floor - biome, height, weather, difficulty, or this mob's own "
                + "condition. " + describeLight(level, pos), null);
    }

    /** Sum {@link #sample} over every seed, propagating a throw as unmeasurable. */
    private static int sampleAcross(
        ServerLevel level, BlockPos pos, EntityType<?> type, EntitySpawnReason reason, long[] seeds
    ) {
        int total = 0;
        for (long seed : seeds) {
            int passes = sample(level, pos, type, reason, seed);
            if (passes == SAMPLE_THREW) {
                return SAMPLE_THREW;
            }
            total += passes;
        }
        return total;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int sample(
        ServerLevel level, BlockPos pos, EntityType<?> type, EntitySpawnReason reason, long seed
    ) {
        RandomSource random = RandomSource.create(seed);
        int passes = 0;
        for (int i = 0; i < SAMPLES; i++) {
            try {
                if (SpawnPlacements.checkSpawnRules((EntityType) type, level, reason, pos, random)) {
                    passes++;
                }
            } catch (Throwable t) {
                return SAMPLE_THREW;
            }
        }
        return passes;
    }

    /**
     * {@code NaturalSpawner.SpawnState.canSpawn} - the biome's spawn-cost budget.
     *
     * <p>Only a handful of biomes define spawn costs (soul sand valley's ghasts and
     * skeletons, the deep dark), but when one does it is invisible in-game and
     * routinely misdiagnosed as a light problem. The potential-energy figure is read
     * from the live spawn state via an access transformer.
     */
    private static RuleResult auditSpawnCharge(
        ServerLevel level, BlockPos pos, Holder<Biome> biome, EntityType<?> type
    ) {
        MobSpawnSettings.MobSpawnCost cost = biome.value().getMobSettings().getMobSpawnCost(type);
        if (cost == null) {
            return RuleResult.pass(SpawnRule.SPAWN_CHARGE, "none", "this biome sets no spawn cost for this mob");
        }

        NaturalSpawner.SpawnState state = level.getChunkSource().getLastSpawnState();
        if (state == null) {
            return RuleResult.unknown(SpawnRule.SPAWN_CHARGE,
                String.format("charge %.2f, budget %.2f - live potential unavailable (no spawn tick yet)",
                    cost.charge(), cost.energyBudget()));
        }

        try {
            double change = state.spawnPotential.getPotentialEnergyChange(pos, cost.charge());
            String measured = String.format("%.2f / %.2f", change, cost.energyBudget());
            return RuleResult.of(SpawnRule.SPAWN_CHARGE, change <= cost.energyBudget(),
                measured, String.format("potential %.3f of a %.3f budget", change, cost.energyBudget()),
                measured + " over",
                String.format("potential %.3f exceeds the %.3f budget - too many of this mob nearby",
                    change, cost.energyBudget()));
        } catch (Throwable t) {
            return RuleResult.unknown(SpawnRule.SPAWN_CHARGE, "could not read the spawn potential: " + t);
        }
    }

    /**
     * The last gate, split into the two very different things it conflates.
     *
     * <p>{@code EventHooks.checkSpawnPosition} returns one boolean for
     * {@code mob.checkSpawnRules && mob.checkSpawnObstruction}, plus whatever any
     * mod's PositionCheck handler decides. Reporting that as a single verdict
     * produced "something rejected the mob after it was created" - which is the mod
     * shrugging, and was wrong besides: the usual cause is the player's own hitbox
     * sitting in the spawn space, because you have to stand next to a block to
     * probe it.
     *
     * <p>So obstruction is measured directly and separately - flooded, or occupied,
     * and by what - and only a rejection that survives with the space demonstrably
     * clear is reported as a genuine veto from elsewhere.
     *
     * <p>Running this needs a real mob instance, so one is constructed and
     * discarded. It is never added to the world.
     */
    private static List<RuleResult> auditObstructionAndVeto(
        ServerLevel level, BlockPos pos, EntityType<?> type
    ) {
        Mob mob;
        try {
            Entity entity = type.create(level, EntitySpawnReason.NATURAL);
            if (!(entity instanceof Mob created)) {
                return List.of(
                    RuleResult.skipped(SpawnRule.SPAWN_OBSTRUCTED, "not a Mob"),
                    RuleResult.skipped(SpawnRule.POSITION_CHECK, "not a Mob - no position check applies"));
            }
            mob = created;
        } catch (Throwable t) {
            return List.of(
                RuleResult.skipped(SpawnRule.SPAWN_OBSTRUCTED, "mob could not be created"),
                RuleResult.unknown(SpawnRule.POSITION_CHECK, "this mob could not be created: " + t));
        }

        try {
            mob.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);

            // Vanilla's own halves, called directly. EventHooks.checkSpawnPosition
            // falls back to exactly this pair when no mod overrides the result, so
            // comparing the two is how a mod's involvement becomes measurable rather
            // than assumed.
            boolean mobRules;
            boolean unobstructed;
            try {
                mobRules = mob.checkSpawnRules(level, EntitySpawnReason.NATURAL);
                unobstructed = mob.checkSpawnObstruction(level);
            } catch (Throwable t) {
                return List.of(
                    RuleResult.unknown(SpawnRule.SPAWN_OBSTRUCTED, "this mob's own checks threw: " + t),
                    RuleResult.unknown(SpawnRule.POSITION_CHECK, "not reached"));
            }

            boolean vanillaWould = mobRules && unobstructed;
            boolean actual;
            try {
                actual = EventHooks.checkSpawnPosition(mob, level, EntitySpawnReason.NATURAL);
            } catch (Throwable t) {
                return List.of(
                    describeObstruction(level, mob, unobstructed),
                    RuleResult.unknown(SpawnRule.POSITION_CHECK, "the position check threw: " + t));
            }

            RuleResult obstruction = describeObstruction(level, mob, unobstructed);

            if (actual != vanillaWould) {
                // Measured, not deduced: something changed vanilla's answer, and the
                // only thing that can is a PositionCheck handler.
                return List.of(obstruction, actual
                    ? RuleResult.pass(SpawnRule.POSITION_CHECK, "allowed by a mod",
                        "vanilla would have refused here; a mod's spawn rules allow it")
                    : RuleResult.fail(SpawnRule.POSITION_CHECK, "vetoed by a mod",
                        "vanilla would have allowed this spawn; a mod's spawn rules refuse it",
                        "A mod is blocking spawns here. Check spawn-control mods such as In Control, "
                            + "or a pack's region rules."));
            }

            // No mod involved. Whichever vanilla half failed is the whole story, and
            // the obstruction row already carries its half.
            if (!mobRules) {
                return List.of(obstruction,
                    RuleResult.fail(SpawnRule.POSITION_CHECK, "mob refused",
                        "this mob's own extra spawn condition rejected the position",
                        "This mob asks for something beyond the standard rules - slimes want the right "
                            + "chunk or depth, for example."));
            }
            return List.of(obstruction,
                RuleResult.pass(SpawnRule.POSITION_CHECK, "no mod objects",
                    "no mod changed vanilla's answer here"));
        } finally {
            mob.discard();
        }
    }

    /**
     * Explain {@code Mob.checkSpawnObstruction}, taking vanilla's verdict as the
     * authority and only using our own queries to name what is in the way.
     *
     * <p>The verdict has to come from vanilla. Deciding it here with a slightly
     * different entity predicate is what produced "space is clear" beside a spawn
     * vanilla had refused, and then a mod took the blame for the gap.
     */
    private static RuleResult describeObstruction(ServerLevel level, Mob mob, boolean unobstructed) {
        if (unobstructed) {
            return RuleResult.pass(SpawnRule.SPAWN_OBSTRUCTED, "clear", "nothing is in this space");
        }

        AABB box = mob.getBoundingBox();
        if (level.containsAnyLiquid(box)) {
            return RuleResult.fail(SpawnRule.SPAWN_OBSTRUCTED, "flooded",
                "the mob's body would be inside a liquid here",
                "Drain or cover this space to make it spawnable.");
        }

        List<Entity> present = level.getEntities(mob, box, e -> true);
        Player player = present.stream()
            .filter(Player.class::isInstance)
            .map(Player.class::cast)
            .findFirst()
            .orElse(null);
        if (player != null) {
            // Overwhelmingly the common case, and the one the anchor gesture exists
            // to solve: you cannot stand in a spawn space and measure it at once.
            return RuleResult.fail(SpawnRule.SPAWN_OBSTRUCTED, "you are standing here",
                player.getName().getString() + " is standing in the space the mob needs",
                "Anchor this block with sneak-right-click, walk 25 blocks away, then read it again.");
        }
        if (!present.isEmpty()) {
            String who = present.getFirst().getName().getString();
            return RuleResult.fail(SpawnRule.SPAWN_OBSTRUCTED, "occupied by " + who,
                present.size() == 1
                    ? who + " is standing in this space"
                    : present.size() + " entities are in this space, including " + who,
                "Move whatever is standing here - a spawn needs the space to itself.");
        }

        return RuleResult.fail(SpawnRule.SPAWN_OBSTRUCTED, "obstructed",
            "vanilla reports this space as obstructed, though nothing is visibly in it",
            null);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The terse light measurement for a table column.
     *
     * <p>Just the level, with no "needs N" attached. The monster light test is a
     * roll, not a threshold - the overworld samples uniformly over 0-7 and spawns
     * when the light is at or below the roll - so any single number presented as the
     * requirement would be wrong. The sampled pass rate beside it carries the odds,
     * and the full breakdown is one hover away.
     */
    public static String lightValue(ServerLevel level, BlockPos pos) {
        return "light " + level.getMaxLocalRawBrightness(pos);
    }

    /**
     * Advice that names the light source actually responsible.
     *
     * <p>Sky light and block light are fixed by opposite actions - a roof versus
     * removing a torch - so telling someone to "reduce the light" when the sun is
     * the problem sends them hunting for a torch that does not exist.
     */
    private static String lightRemedy(ServerLevel level, BlockPos pos) {
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        if (sky > 0 && block > 0) {
            return "Both sky and block light reach here. Roof it over and remove nearby light sources.";
        }
        if (sky > 0) {
            return "Daylight reaches this block (sky light " + sky + "). Roof it over, or wait for night.";
        }
        if (block > 0) {
            return "A light source gives this block light level " + block + ". Remove it to allow spawns.";
        }
        return "This spot is already dark; the light test still rejected it.";
    }

    /** The measured light at a position, in the terms the spawn rules use. */
    public static String describeLight(ServerLevel level, BlockPos pos) {
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        int sky = level.getBrightness(LightLayer.SKY, pos);
        int max = level.getMaxLocalRawBrightness(pos);
        int blockLimit = level.dimensionType().monsterSpawnBlockLightLimit();
        return String.format("block light %d (monster limit %d), sky light %d, effective light %d",
            block, blockLimit, sky, max);
    }

    private static String describeBlock(BlockState state) {
        return state.getBlock().getName().getString();
    }
}
