package com.flatts.spawndoctor.audit;

/**
 * Every gate a natural spawn must clear, in the order
 * {@link net.minecraft.world.level.NaturalSpawner} applies them.
 *
 * <p>The enum order IS the pipeline order - the report walks it top to bottom and
 * the first {@link Verdict#FAIL} is the headline answer. Each constant names the
 * exact vanilla call site it mirrors so the mapping stays auditable when the game
 * updates; if a rule here stops matching its call site, the mod is lying and the
 * whole point is lost.
 *
 * @param layer      which scope the rule is decided at - drives report grouping
 * @param title      short human-readable name shown in the report
 * @param remedy     what a player would actually do about a failure, or null when
 *                   the rule is informational
 */
public enum SpawnRule {

    // ---------------------------------------------------------------- world
    /** {@code ServerChunkCache.tickChunks}: gamerule doMobSpawning. */
    GAMERULE_MOB_SPAWNING(Layer.WORLD, "Gamerule doMobSpawning",
        "Run /gamerule doMobSpawning true."),
    /** {@code Monster.checkMonsterSpawnRules}: peaceful kills every monster spawn. */
    DIFFICULTY(Layer.WORLD, "Difficulty",
        "Monsters never spawn on Peaceful. Raise the difficulty."),
    /** {@code ServerChunkCache.spawnEnemies} - the server's own enemy-spawn toggle. */
    SERVER_SPAWN_ENEMIES(Layer.WORLD, "Server enemy spawning",
        "The server has enemy spawning switched off (spawn-monsters=false or Peaceful)."),

    // ---------------------------------------------------------------- chunk
    /** {@code ServerLevel.canSpawnEntitiesInChunk} - entity-ticking, i.e. simulation distance. */
    CHUNK_ENTITY_TICKING(Layer.CHUNK, "Chunk is entity-ticking",
        "This chunk is loaded but not ticking entities. Get closer, or raise simulation distance."),
    /** {@code ServerLevel.canSpawnEntitiesInChunk} - world border half. */
    WORLD_BORDER(Layer.CHUNK, "Inside world border",
        "Nothing spawns outside the world border."),
    /** {@code ChunkMap.anyPlayerCloseEnoughForSpawning} - the 128-block spawn sphere. */
    PLAYER_IN_SPAWN_RANGE(Layer.CHUNK, "A player is within spawn range",
        "No player is within 128 blocks of this chunk, so it is never picked for a spawn attempt."),
    /** {@code NaturalSpawner.SpawnState.canSpawnForCategoryGlobal} - the per-category world cap. */
    CATEGORY_GLOBAL_CAP(Layer.CHUNK, "Category is under the global cap",
        "The mob cap for this category is full. Kill or despawn mobs elsewhere in the dimension."),
    /** {@code LocalMobCapCalculator.canSpawn} - the per-player, per-category cap. */
    CATEGORY_LOCAL_CAP(Layer.CHUNK, "Category is under the per-player cap",
        "Every player near this chunk is already at their cap for this category."),

    // ------------------------------------------------------------- position
    /** {@code NaturalSpawner.spawnCategoryForPosition}: the anchor block must not conduct redstone. */
    ANCHOR_NOT_CONDUCTOR(Layer.POSITION, "Anchor block is not a redstone conductor",
        "A spawn attempt anchored on this block is discarded before any mob is picked."),
    /** {@code NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint}: 24 blocks from any player. */
    PLAYER_DISTANCE(Layer.POSITION, "At least 24 blocks from the nearest player",
        "Mobs never spawn within 24 blocks of a player. Move your AFK spot further away."),
    /** {@code NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint}: 24 blocks from world spawn. */
    WORLD_SPAWN_DISTANCE(Layer.POSITION, "At least 24 blocks from the world spawn point",
        "This position is inside the 24-block no-spawn bubble around the world spawn point."),
    /** {@code NaturalSpawner.getRandomSpawnMobAt}: the biome/structure spawn list for this category. */
    BIOME_SPAWN_LIST(Layer.POSITION, "Biome offers this category",
        "This biome has no spawn entries for this category, so nothing of this kind can ever spawn here."),

    // ----------------------------------------------------------- per mob type
    /** {@code EntityType.canSummon}. */
    TYPE_SUMMONABLE(Layer.MOB, "Entity type is summonable", null),
    /** {@code NaturalSpawner.isValidSpawnPostitionForType}: despawn-distance gate. */
    DESPAWN_DISTANCE(Layer.MOB, "Within the type's spawn distance",
        "This mob cannot spawn this far from a player."),
    /** {@code SpawnPlacements.isSpawnPositionOk} - the placement type (ON_GROUND / IN_WATER / IN_LAVA). */
    PLACEMENT(Layer.MOB, "Placement (floor, headroom, fluid)",
        "The physical shape of this spot is wrong for this mob."),
    /** {@code SpawnPlacements.checkSpawnRules} - the per-type predicate, plus NeoForge's SpawnPlacementCheck. */
    SPAWN_RULES(Layer.MOB, "The mob's own spawn rules",
        "Light level, block below, sky access, biome or height rejected this mob."),
    /** {@code ServerLevel.noCollision} against the mob's spawn AABB. */
    NO_COLLISION(Layer.MOB, "Mob's body fits without collision",
        "The mob's hitbox does not fit here. Clear space, or expect a smaller mob."),
    /** {@code NaturalSpawner.SpawnState.canSpawn} - the biome's spawn-cost charge budget. */
    SPAWN_CHARGE(Layer.MOB, "Under the biome's spawn-cost budget",
        "Too many of this mob are already nearby for the biome's spawn-cost budget. Kill or move them."),
    /** {@code EventHooks.checkSpawnPosition} - NeoForge PositionCheck, i.e. other mods' veto. */
    POSITION_CHECK(Layer.MOB, "Final position check (mods can veto here)",
        "Something rejected the mob after it was created - usually another mod's spawn rules.");

    /** Scope a rule is decided at. Drives report grouping and the order of the sections. */
    public enum Layer {
        WORLD("World"),
        CHUNK("Chunk"),
        POSITION("Position"),
        MOB("Mob");

        private final String title;

        Layer(String title) {
            this.title = title;
        }

        public String title() {
            return this.title;
        }
    }

    private final Layer layer;
    private final String title;
    private final String remedy;

    SpawnRule(Layer layer, String title, String remedy) {
        this.layer = layer;
        this.title = title;
        this.remedy = remedy;
    }

    public Layer layer() {
        return this.layer;
    }

    public String title() {
        return this.title;
    }

    /** What to actually do about a failure, or null for informational rules. */
    public String remedy() {
        return this.remedy;
    }
}
