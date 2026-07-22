package com.flatts.spawndoctor.audit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The overlay's data source: a {@link SpawnGrade} for every block in a box.
 *
 * <p>This is a deliberately cheaper path than {@link SpawnAuditor}. A full audit
 * costs hundreds of predicate rolls and a throwaway entity <i>per mob type</i>;
 * running that over ten thousand positions would stall the server. So the scan
 * makes two trades, both of which are stated here rather than hidden:
 *
 * <ul>
 *   <li>It grades against a <b>representative set</b> of monster types - the
 *       heaviest entries on this biome's monster list - not every type. A position
 *       is spawnable if any of them can spawn.</li>
 *   <li>It rolls the spawn predicate {@link #SCAN_SAMPLES} times, not 64. That is
 *       enough to separate "never" from "sometimes"; the exact rate is what the
 *       probe is for.</li>
 * </ul>
 *
 * <p>Everything it <i>does</i> check is the real call, not an approximation. When a
 * player wants certainty on one block they point the probe at it and get the full
 * walk; the overlay's job is to show them which block to point at.
 */
public final class AreaScanner {

    /** Predicate rolls per position. Low, because this runs thousands of times. */
    private static final int SCAN_SAMPLES = 8;

    /** How many of the biome's monster types the scan grades against. */
    private static final int REPRESENTATIVE_TYPES = 4;

    /** Hard ceiling on scanned volume, so a bad radius cannot stall the server thread. */
    public static final int MAX_VOLUME = 96_000;

    private AreaScanner() {
    }

    /**
     * Grade every block in the box centred on {@code center}.
     *
     * @return a dense row-major grid, indexed {@code ((dy * span) + dx) * span + dz},
     *         matching {@link #index(int, int, int, int, int)}
     */
    public static byte[] scan(ServerLevel level, BlockPos center, int radiusXZ, int radiusY) {
        int spanXZ = radiusXZ * 2 + 1;
        int spanY = radiusY * 2 + 1;
        byte[] grid = new byte[spanXZ * spanXZ * spanY];

        List<EntityType<?>> types = representativeTypes(level, center);
        if (types.isEmpty()) {
            return grid; // No monsters spawn in this biome at all; nothing to paint.
        }

        // These do not vary across the box, so they are hoisted out of the loop.
        boolean spawningEnabled = level.getGameRules().get(GameRules.SPAWN_MOBS)
            && level.getDifficulty() != Difficulty.PEACEFUL;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        RandomSource random = RandomSource.create();

        for (int dy = 0; dy < spanY; dy++) {
            int y = center.getY() - radiusY + dy;
            if (y < level.getMinY() || y > level.getMaxY()) {
                continue;
            }
            for (int dx = 0; dx < spanXZ; dx++) {
                for (int dz = 0; dz < spanXZ; dz++) {
                    cursor.set(center.getX() - radiusXZ + dx, y, center.getZ() - radiusXZ + dz);
                    SpawnGrade grade = grade(level, cursor, types, spawningEnabled, random);
                    grid[index(dx, dy, dz, spanXZ, spanY)] = grade.id();
                }
            }
        }

        return grid;
    }

    /** Row-major index into the grid returned by {@link #scan}. */
    public static int index(int dx, int dy, int dz, int spanXZ, int spanY) {
        return (dy * spanXZ + dx) * spanXZ + dz;
    }

    /**
     * Grade one position.
     *
     * <p>Order matters: the permanent, cheap checks run first, both because they
     * are the common case and because a position that fails them is {@link
     * SpawnGrade#SAFE} regardless of anything situational.
     */
    private static SpawnGrade grade(
        ServerLevel level,
        BlockPos pos,
        List<EntityType<?>> types,
        boolean spawningEnabled,
        RandomSource random
    ) {
        boolean anyPlacementFits = false;
        boolean anySpawnable = false;

        for (EntityType<?> type : types) {
            if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)) {
                continue;
            }
            if (!level.noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
                continue;
            }
            anyPlacementFits = true;

            if (rollsPass(level, pos, type, random)) {
                anySpawnable = true;
                break;
            }
        }

        if (!anyPlacementFits) {
            // The shape of the world rejects every mob here. This is the answer that
            // survives the player walking away, so it is the one worth showing.
            return SpawnGrade.SAFE;
        }
        if (!anySpawnable) {
            return SpawnGrade.SAFE; // Light or another standing rule blocks it.
        }
        if (!spawningEnabled) {
            return SpawnGrade.BLOCKED_NOW; // Peaceful or doMobSpawning off - both revert.
        }
        return withinPlayerBubble(level, pos) ? SpawnGrade.BLOCKED_NOW : SpawnGrade.SPAWNABLE;
    }

    private static boolean rollsPass(ServerLevel level, BlockPos pos, EntityType<?> type, RandomSource random) {
        for (int i = 0; i < SCAN_SAMPLES; i++) {
            if (checkRules(level, pos, type, random)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean checkRules(ServerLevel level, BlockPos pos, EntityType<?> type, RandomSource random) {
        try {
            return SpawnPlacements.checkSpawnRules(
                (EntityType) type, level, EntitySpawnReason.NATURAL, pos, random);
        } catch (Throwable t) {
            // A mod's predicate threw. Treat it as "cannot judge" rather than letting
            // one broken mob type abort the whole scan.
            return false;
        }
    }

    /** {@code NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint} - the 24-block bubble. */
    private static boolean withinPlayerBubble(ServerLevel level, BlockPos pos) {
        Player nearest = level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, -1.0, false);
        if (nearest == null) {
            return true; // No player means no spawn attempt, and that changes the moment one arrives.
        }
        return nearest.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) <= 576.0;
    }

    /**
     * The heaviest monster types this biome offers, which are also the ones a spawn
     * attempt is most likely to roll. Taking the heaviest rather than an arbitrary
     * slice means the overlay reflects what would actually spawn.
     */
    private static List<EntityType<?>> representativeTypes(ServerLevel level, BlockPos center) {
        Holder<Biome> biome = level.getBiome(center);
        List<Weighted<MobSpawnSettings.SpawnerData>> entries;
        try {
            entries = new ArrayList<>(
                biome.value().getMobSettings().getMobs(MobCategory.MONSTER).unwrap());
        } catch (Throwable t) {
            return List.of();
        }

        entries.sort(Comparator.comparingInt(Weighted<MobSpawnSettings.SpawnerData>::weight).reversed());

        List<EntityType<?>> types = new ArrayList<>(REPRESENTATIVE_TYPES);
        for (Weighted<MobSpawnSettings.SpawnerData> entry : entries) {
            EntityType<?> type = entry.value().type();
            if (type.canSummon() && !types.contains(type)) {
                types.add(type);
            }
            if (types.size() >= REPRESENTATIVE_TYPES) {
                break;
            }
        }
        return types;
    }
}
