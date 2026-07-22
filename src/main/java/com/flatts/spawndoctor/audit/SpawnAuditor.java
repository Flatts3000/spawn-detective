package com.flatts.spawndoctor.audit;

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
     * Ceiling on mob types walked per category. Every candidate costs a few hundred
     * predicate calls and one throwaway entity, and a heavily modded biome can list
     * far more monsters than a player will ever read. The report says explicitly when
     * this truncates - a silently shortened list reads as "I checked everything".
     */
    private static final int MAX_CANDIDATES_PER_CATEGORY = 48;

    private SpawnAuditor() {
    }

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
            level.dimension().identifier().toString(),
            pos,
            biome.getRegisteredName(),
            world,
            categories
        );
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
            "doMobSpawning is true",
            "doMobSpawning is false - nothing spawns naturally anywhere in this world"));

        // Monster.checkMonsterSpawnRules: peaceful rejects every monster. Animals
        // still spawn, so this is reported rather than treated as a global stop.
        Difficulty difficulty = level.getDifficulty();
        results.add(difficulty == Difficulty.PEACEFUL
            ? RuleResult.fail(SpawnRule.DIFFICULTY, "Peaceful - monsters cannot spawn (animals still can)")
            : RuleResult.pass(SpawnRule.DIFFICULTY, difficulty.getSerializedName()));

        // ServerLevel.canSpawnEntitiesInChunk, split into its two halves so the
        // report names which one failed.
        boolean withinBorder = level.getWorldBorder().isWithinBounds(chunkPos);
        results.add(RuleResult.of(SpawnRule.WORLD_BORDER, withinBorder,
            "inside the world border",
            "outside the world border"));

        boolean canTick = level.canSpawnEntitiesInChunk(chunkPos);
        results.add(RuleResult.of(SpawnRule.CHUNK_ENTITY_TICKING, canTick || !withinBorder,
            "chunk " + chunkPos + " ticks entities",
            "chunk " + chunkPos + " is not entity-ticking (outside simulation distance, or not loaded)"));

        // ChunkMap.anyPlayerCloseEnoughForSpawning - the 128-block spawn sphere.
        boolean playerNear = level.anyPlayerCloseEnoughForSpawning(pos);
        results.add(RuleResult.of(SpawnRule.PLAYER_IN_SPAWN_RANGE, playerNear,
            "a player is within 128 blocks of this chunk",
            "no player within 128 blocks of this chunk - it is never picked for a spawn attempt"));

        // NaturalSpawner.spawnCategoryForPosition: the attempt is discarded outright
        // if the anchor block conducts redstone. Advisory, because the real anchor is
        // a random position in the chunk that shares this Y level.
        BlockState state = level.getBlockState(pos);
        boolean conductor = state.isRedstoneConductor(level, pos);
        results.add(RuleResult.of(SpawnRule.ANCHOR_NOT_CONDUCTOR, !conductor,
            describeBlock(state) + " does not conduct redstone",
            describeBlock(state) + " is a redstone conductor - a spawn attempt anchored here is discarded"));

        // NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint - 24 blocks from any player.
        Player nearest = level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, -1.0, false);
        if (nearest == null) {
            results.add(RuleResult.fail(SpawnRule.PLAYER_DISTANCE,
                "no player in this dimension - a spawn attempt needs one"));
        } else {
            double distSqr = nearest.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            String measured = String.format("%.1f blocks from %s", Math.sqrt(distSqr), nearest.getName().getString());
            results.add(RuleResult.of(SpawnRule.PLAYER_DISTANCE, distSqr > MIN_PLAYER_DISTANCE_SQR,
                measured + " (needs > 24)",
                measured + " - inside the 24-block no-spawn bubble"));
        }

        // The same method's world-spawn check.
        LevelData.RespawnData respawn = level.getRespawnData();
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        boolean nearWorldSpawn = respawn.dimension() == level.dimension()
            && respawn.pos().closerToCenterThan(center, 24.0);
        results.add(RuleResult.of(SpawnRule.WORLD_SPAWN_DISTANCE, !nearWorldSpawn,
            "outside the world spawn bubble",
            "within 24 blocks of the world spawn point " + respawn.pos()));

        return results;
    }

    // --------------------------------------------------------------- category

    private static AuditReport.Category auditCategory(
        ServerLevel level, BlockPos pos, Holder<Biome> biome, MobCategory category
    ) {
        List<RuleResult> rules = new ArrayList<>();

        // NaturalSpawner.SpawnState.canSpawnForCategoryGlobal. The live counts come
        // from the spawn state the server built on its last spawn tick; if the server
        // has not run one yet there is nothing honest to report.
        NaturalSpawner.SpawnState state = level.getChunkSource().getLastSpawnState();
        if (state == null) {
            rules.add(RuleResult.unknown(SpawnRule.CATEGORY_GLOBAL_CAP,
                "no spawn state yet - the server has not run a spawn tick"));
            rules.add(RuleResult.unknown(SpawnRule.CATEGORY_LOCAL_CAP,
                "no spawn state yet - the server has not run a spawn tick"));
        } else if (category.getMaxInstancesPerChunk() <= 0) {
            // MobCategory is an extensible enum: a mod can add a category with a
            // non-positive per-chunk max (vanilla MISC uses -1). The cap formula would
            // report "0 / 0 used" as a permanent failure, which would be a lie.
            String note = "this category declares no per-chunk maximum ("
                + category.getMaxInstancesPerChunk() + ") - caps do not apply";
            rules.add(RuleResult.skipped(SpawnRule.CATEGORY_GLOBAL_CAP, note));
            rules.add(RuleResult.skipped(SpawnRule.CATEGORY_LOCAL_CAP, note));
        } else {
            int count = state.getMobCategoryCounts().getInt(category);
            int cap = category.getMaxInstancesPerChunk() * state.getSpawnableChunkCount() / GLOBAL_CAP_DIVISOR;
            rules.add(RuleResult.of(SpawnRule.CATEGORY_GLOBAL_CAP, count < cap,
                count + " / " + cap + " used",
                "cap full: " + count + " / " + cap + " (" + state.getSpawnableChunkCount() + " spawnable chunks)"));

            // LocalMobCapCalculator.canSpawn - the per-player slice of the cap.
            rules.add(auditLocalCap(state, category, ChunkPos.containing(pos)));
        }

        // The biome/structure spawn list for this category, after NeoForge's
        // PotentialSpawns event - so any mod that adds or removes entries here is
        // reflected exactly as the real spawner would see it.
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
            rules.add(RuleResult.fail(SpawnRule.BIOME_SPAWN_LIST,
                "no " + category.getName() + " entries for this biome/structure"));
            return new AuditReport.Category(category, rules, List.of());
        }
        int totalWeight = entries.stream().mapToInt(Weighted::weight).sum();

        // Heaviest entries first: if the list gets truncated, what survives is what a
        // spawn attempt would most likely have rolled.
        List<Weighted<MobSpawnSettings.SpawnerData>> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingInt(Weighted<MobSpawnSettings.SpawnerData>::weight).reversed());

        boolean truncated = ordered.size() > MAX_CANDIDATES_PER_CATEGORY;
        rules.add(RuleResult.pass(SpawnRule.BIOME_SPAWN_LIST, truncated
            ? entries.size() + " entries - walking the " + MAX_CANDIDATES_PER_CATEGORY + " heaviest"
            : entries.size() + " entries"));
        if (truncated) {
            ordered = ordered.subList(0, MAX_CANDIDATES_PER_CATEGORY);
        }

        List<AuditReport.Candidate> candidates = new ArrayList<>(ordered.size());
        for (Weighted<MobSpawnSettings.SpawnerData> entry : ordered) {
            candidates.add(auditCandidate(
                level, pos, biome, category, entry.value().type(), entry.weight(), totalWeight));
        }

        return new AuditReport.Category(category, rules, candidates);
    }

    /**
     * {@code LocalMobCapCalculator.canSpawn}, reached through the spawn state's
     * calculator (access-transformed - the field is private and there is no getter).
     */
    private static RuleResult auditLocalCap(NaturalSpawner.SpawnState state, MobCategory category, ChunkPos chunkPos) {
        try {
            boolean ok = state.localMobCapCalculator.canSpawn(category, chunkPos);
            return RuleResult.of(SpawnRule.CATEGORY_LOCAL_CAP, ok,
                "at least one nearby player is under their " + category.getName() + " cap of "
                    + category.getMaxInstancesPerChunk(),
                "every player near this chunk is at their " + category.getName() + " cap of "
                    + category.getMaxInstancesPerChunk());
        } catch (Throwable t) {
            return RuleResult.unknown(SpawnRule.CATEGORY_LOCAL_CAP, "could not read the local cap: " + t);
        }
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
     */
    public static AuditReport.Candidate auditType(ServerLevel level, BlockPos pos, EntityType<?> type) {
        return auditCandidate(level, pos, level.getBiome(pos), type.getCategory(), type, 0, 0);
    }

    /**
     * The per-type gates from {@code NaturalSpawner.isValidSpawnPostitionForType}
     * and {@code isValidPositionForMob}, in that order.
     */
    private static AuditReport.Candidate auditCandidate(
        ServerLevel level,
        BlockPos pos,
        Holder<Biome> biome,
        MobCategory category,
        EntityType<?> type,
        int weight,
        int totalWeight
    ) {
        List<RuleResult> rules = new ArrayList<>();

        rules.add(RuleResult.of(SpawnRule.TYPE_SUMMONABLE, type.canSummon(),
            "summonable",
            "this entity type is flagged not-summonable and can never spawn naturally"));

        // isValidSpawnPostitionForType: mobs that cannot spawn far from a player are
        // rejected beyond their category's despawn distance.
        Player nearest = level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, -1.0, false);
        int despawn = category.getDespawnDistance();
        if (nearest == null) {
            rules.add(RuleResult.skipped(SpawnRule.DESPAWN_DISTANCE, "no player to measure from"));
        } else if (type.canSpawnFarFromPlayer()) {
            rules.add(RuleResult.pass(SpawnRule.DESPAWN_DISTANCE, "this mob may spawn at any distance"));
        } else {
            double distSqr = nearest.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            boolean ok = distSqr <= (double) despawn * despawn;
            rules.add(RuleResult.of(SpawnRule.DESPAWN_DISTANCE, ok,
                String.format("%.1f blocks from a player (limit %d)", Math.sqrt(distSqr), despawn),
                String.format("%.1f blocks from a player, past this mob's %d-block limit",
                    Math.sqrt(distSqr), despawn)));
        }

        // SpawnPlacements.isSpawnPositionOk, decomposed into the specific sub-check
        // that failed instead of a bare boolean.
        rules.add(auditPlacement(level, pos, type));

        // SpawnPlacements.checkSpawnRules - sampled, then attributed.
        rules.add(auditSpawnRules(level, pos, type));

        // NaturalSpawner: level.noCollision(type.getSpawnAABB(...)).
        AABB aabb = type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        rules.add(RuleResult.of(SpawnRule.NO_COLLISION, level.noCollision(aabb),
            String.format("%.1f x %.1f hitbox fits", type.getWidth(), type.getHeight()),
            String.format("%.1f x %.1f hitbox collides with a block here", type.getWidth(), type.getHeight())));

        // NaturalSpawner.SpawnState.canSpawn - the biome's spawn-cost charge budget.
        rules.add(auditSpawnCharge(level, pos, biome, type));

        // EventHooks.checkSpawnPosition - the last gate, and where other mods veto.
        rules.add(auditPositionCheck(level, pos, type));

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
                "no placement restrictions for this mob", "rejected by a custom placement type");
        }

        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return RuleResult.fail(SpawnRule.PLACEMENT, "outside the world border");
        }

        if (placement == SpawnPlacementTypes.IN_WATER) {
            FluidState fluid = level.getFluidState(pos);
            if (!fluid.is(net.minecraft.tags.FluidTags.WATER)) {
                return RuleResult.fail(SpawnRule.PLACEMENT,
                    "needs water at this position, found " + describeBlock(level.getBlockState(pos)));
            }
            BlockPos above = pos.above();
            if (level.getBlockState(above).isRedstoneConductor(level, above)) {
                return RuleResult.fail(SpawnRule.PLACEMENT,
                    "the block above (" + describeBlock(level.getBlockState(above)) + ") is a redstone conductor");
            }
            return RuleResult.pass(SpawnRule.PLACEMENT, "water with clear headroom");
        }

        if (placement == SpawnPlacementTypes.IN_LAVA) {
            boolean lava = level.getFluidState(pos).is(net.minecraft.tags.FluidTags.LAVA);
            return RuleResult.of(SpawnRule.PLACEMENT, lava,
                "lava",
                "needs lava at this position, found " + describeBlock(level.getBlockState(pos)));
        }

        if (placement == SpawnPlacementTypes.ON_GROUND) {
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isValidSpawn(level, below, type)) {
                return RuleResult.fail(SpawnRule.PLACEMENT,
                    "the floor (" + describeBlock(belowState) + ") does not allow this mob to spawn on it");
            }
            String here = emptySpawnBlockProblem(level, pos, type);
            if (here != null) {
                return RuleResult.fail(SpawnRule.PLACEMENT, "the spawn block itself: " + here);
            }
            String above = emptySpawnBlockProblem(level, pos.above(), type);
            if (above != null) {
                return RuleResult.fail(SpawnRule.PLACEMENT, "no headroom: " + above);
            }
            return RuleResult.pass(SpawnRule.PLACEMENT,
                "standing on " + describeBlock(belowState) + " with clear headroom");
        }

        // A mod's own SpawnPlacementType - opaque, so report the boolean honestly.
        return RuleResult.of(SpawnRule.PLACEMENT, vanilla,
            "accepted by " + placement.getClass().getName(),
            "rejected by " + placement.getClass().getName() + " (a custom placement type from another mod)");
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
            return RuleResult.pass(SpawnRule.SPAWN_RULES, "no spawn rules registered for this mob");
        }

        int natural = sample(level, pos, type, EntitySpawnReason.NATURAL);
        if (natural == SAMPLE_THREW) {
            return RuleResult.unknown(SpawnRule.SPAWN_RULES,
                "this mob's spawn predicate raised an exception - report it to that mod, not this one");
        }
        if (natural == SAMPLES) {
            return RuleResult.sampled(SpawnRule.SPAWN_RULES, natural, SAMPLES, null);
        }

        String cause = attributeSpawnRuleFailure(level, pos, type);
        return RuleResult.sampled(SpawnRule.SPAWN_RULES, natural, SAMPLES, cause);
    }

    private static String attributeSpawnRuleFailure(ServerLevel level, BlockPos pos, EntityType<?> type) {
        int asSpawner = sample(level, pos, type, EntitySpawnReason.SPAWNER);
        int asTrial = sample(level, pos, type, EntitySpawnReason.TRIAL_SPAWNER);

        if (asSpawner == SAMPLE_THREW || asTrial == SAMPLE_THREW) {
            // The predicate is not exemption-safe; attributing from it would be a guess.
            return "cause: not attributable - this mob's predicate raised under a "
                + "different spawn reason. " + describeLight(level, pos);
        }

        if (asSpawner > 0) {
            // Exempting the floor/sky checks fixed it. Which of the two was it?
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.isValidSpawn(level, below, type)) {
                return "cause: the floor. " + describeBlock(belowState) + " is not a valid spawn surface";
            }
            if (!level.canSeeSky(pos)) {
                return "cause: no sky access. This mob only spawns where it can see the sky";
            }
            return "cause: the spawner-exempt group (floor or sky access)";
        }

        if (asTrial > 0) {
            return "cause: light. " + describeLight(level, pos);
        }

        // Neither exemption helped: it is not light and not the floor.
        return "cause: a rule other than light or floor - biome, height, weather, difficulty, "
            + "or this mob's own condition. " + describeLight(level, pos);
    }

    /**
     * Roll the predicate {@link #SAMPLES} times, or return {@link #SAMPLE_THREW} if a
     * mod's predicate raised. Reporting a partial count as if it were a measurement
     * would turn a broken mod into a confident wrong answer.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int sample(ServerLevel level, BlockPos pos, EntityType<?> type, EntitySpawnReason reason) {
        RandomSource random = RandomSource.create();
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
            return RuleResult.pass(SpawnRule.SPAWN_CHARGE, "this biome sets no spawn cost for this mob");
        }

        NaturalSpawner.SpawnState state = level.getChunkSource().getLastSpawnState();
        if (state == null) {
            return RuleResult.unknown(SpawnRule.SPAWN_CHARGE,
                String.format("charge %.2f, budget %.2f - live potential unavailable (no spawn tick yet)",
                    cost.charge(), cost.energyBudget()));
        }

        try {
            double change = state.spawnPotential.getPotentialEnergyChange(pos, cost.charge());
            return RuleResult.of(SpawnRule.SPAWN_CHARGE, change <= cost.energyBudget(),
                String.format("potential %.3f of budget %.3f", change, cost.energyBudget()),
                String.format("potential %.3f exceeds budget %.3f - too many of this mob nearby",
                    change, cost.energyBudget()));
        } catch (Throwable t) {
            return RuleResult.unknown(SpawnRule.SPAWN_CHARGE, "could not read the spawn potential: " + t);
        }
    }

    /**
     * {@code EventHooks.checkSpawnPosition} - the final gate, which fires NeoForge's
     * PositionCheck event. This is where "In Control", difficulty mods and pack
     * scripts veto a spawn that vanilla would have allowed.
     *
     * <p>Running it needs a real mob instance, so one is constructed and discarded.
     * It is never added to the world.
     */
    private static RuleResult auditPositionCheck(ServerLevel level, BlockPos pos, EntityType<?> type) {
        Mob mob;
        try {
            Entity entity = type.create(level, EntitySpawnReason.NATURAL);
            if (!(entity instanceof Mob created)) {
                return RuleResult.skipped(SpawnRule.POSITION_CHECK, "not a Mob - no position check applies");
            }
            mob = created;
        } catch (Throwable t) {
            return RuleResult.unknown(SpawnRule.POSITION_CHECK, "this mob could not be created: " + t);
        }

        try {
            mob.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
            boolean ok = EventHooks.checkSpawnPosition(mob, level, EntitySpawnReason.NATURAL);
            return RuleResult.of(SpawnRule.POSITION_CHECK, ok,
                "accepted",
                "vetoed here - the mob's own obstruction check, or another mod's PositionCheck handler");
        } catch (Throwable t) {
            return RuleResult.unknown(SpawnRule.POSITION_CHECK, "the position check threw: " + t);
        } finally {
            mob.discard();
        }
    }

    // ---------------------------------------------------------------- helpers

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
