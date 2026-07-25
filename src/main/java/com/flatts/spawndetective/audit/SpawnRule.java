package com.flatts.spawndetective.audit;

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
    GAMERULE_MOB_SPAWNING(Layer.WORLD, Persistence.SITUATIONAL, "Gamerule doMobSpawning",
        "Run /gamerule doMobSpawning true."),
    /** {@code Monster.checkMonsterSpawnRules}: peaceful kills every monster spawn. */
    DIFFICULTY(Layer.WORLD, Persistence.SITUATIONAL, "Difficulty",
        "Monsters never spawn on Peaceful. Raise the difficulty."),
    /** {@code ServerChunkCache.spawnEnemies} - the server's own enemy-spawn toggle. */
    SERVER_SPAWN_ENEMIES(Layer.WORLD, Persistence.SITUATIONAL, "Server enemy spawning",
        "The server has enemy spawning switched off (spawn-monsters=false or Peaceful)."),

    // ---------------------------------------------------------------- chunk
    /** {@code ServerLevel.canSpawnEntitiesInChunk} - entity-ticking, i.e. simulation distance. */
    CHUNK_ENTITY_TICKING(Layer.CHUNK, Persistence.SITUATIONAL, "Chunk ticking",
        "This chunk is loaded but not ticking entities. Get closer, or raise simulation distance."),
    /** {@code ServerLevel.canSpawnEntitiesInChunk} - world border half. */
    WORLD_BORDER(Layer.CHUNK, Persistence.STANDING, "World border",
        "Nothing spawns outside the world border."),
    /** {@code ChunkMap.anyPlayerCloseEnoughForSpawning} - the 128-block spawn sphere. */
    PLAYER_IN_SPAWN_RANGE(Layer.CHUNK, Persistence.SITUATIONAL, "Player within 128 blocks",
        "No player is within 128 blocks of this chunk, so it is never picked for a spawn attempt."),
    /** {@code NaturalSpawner.SpawnState.canSpawnForCategoryGlobal} - the per-category world cap. */
    CATEGORY_GLOBAL_CAP(Layer.CHUNK, Persistence.SITUATIONAL, "Global mob cap",
        "The mob cap for this category is full. Kill or despawn mobs elsewhere in the dimension."),
    /** {@code LocalMobCapCalculator.canSpawn} - the per-player, per-category cap. */
    CATEGORY_LOCAL_CAP(Layer.CHUNK, Persistence.SITUATIONAL, "Per-player mob cap",
        "Every player near this chunk is already at their cap for this category."),

    // ------------------------------------------------------------- position
    /**
     * {@code NaturalSpawner.spawnCategoryForChunk} -> {@code getRandomPosWithin}:
     * how often a spawn attempt in this chunk anchors at this Y at all.
     *
     * <p>Informational, and never a {@link Verdict#FAIL}. A spot the spawner rarely
     * reaches is slow, not shut, and letting this become the headline would have the
     * mod say "cannot spawn" about a block that can.
     */
    ATTEMPT_REACH(Layer.POSITION, Persistence.STANDING, "Spawn attempt reach",
        "Enlarge the platform: the rate scales with how much of the chunk has surface at this height."),
    /** {@code NaturalSpawner.spawnCategoryForPosition}: the anchor block must not conduct redstone. */
    ANCHOR_NOT_CONDUCTOR(Layer.POSITION, Persistence.STANDING, "Anchor block",
        "A spawn attempt anchored on this block is discarded before any mob is picked."),
    /** {@code NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint}: 24 blocks from any player. */
    PLAYER_DISTANCE(Layer.POSITION, Persistence.SITUATIONAL, "Distance from nearest player",
        "Mobs never spawn within 24 blocks of a player. Move your AFK spot further away."),
    /** {@code NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint}: 24 blocks from world spawn. */
    WORLD_SPAWN_DISTANCE(Layer.POSITION, Persistence.STANDING, "Distance from world spawn",
        "This position is inside the 24-block no-spawn bubble around the world spawn point."),
    /** {@code NaturalSpawner.getRandomSpawnMobAt}: the biome/structure spawn list for this category. */
    BIOME_SPAWN_LIST(Layer.POSITION, Persistence.STANDING, "Biome spawn list",
        "This biome has no spawn entries for this category, so nothing of this kind can ever spawn here."),

    // ----------------------------------------------------------- per mob type
    /** {@code EntityType.canSummon}. */
    TYPE_SUMMONABLE(Layer.MOB, Persistence.STANDING, "Summonable", null),
    /** {@code NaturalSpawner.isValidSpawnPostitionForType}: despawn-distance gate. */
    DESPAWN_DISTANCE(Layer.MOB, Persistence.SITUATIONAL, "Spawn distance",
        "This mob cannot spawn this far from a player."),
    /** {@code SpawnPlacements.isSpawnPositionOk} - the placement type (ON_GROUND / IN_WATER / IN_LAVA). */
    PLACEMENT(Layer.MOB, Persistence.STANDING, "Placement",
        "The physical shape of this spot is wrong for this mob."),
    /** {@code SpawnPlacements.checkSpawnRules} - the per-type predicate, plus NeoForge's SpawnPlacementCheck. */
    SPAWN_RULES(Layer.MOB, Persistence.STANDING, "The mob's own spawn rules",
        "Light level, block below, sky access, biome or height rejected this mob."),
    /** {@code ServerLevel.noCollision} against the mob's spawn AABB. */
    NO_COLLISION(Layer.MOB, Persistence.STANDING, "Hitbox fits",
        "The mob's hitbox does not fit here. Clear space, or expect a smaller mob."),
    /** {@code NaturalSpawner.SpawnState.canSpawn} - the biome's spawn-cost charge budget. */
    SPAWN_CHARGE(Layer.MOB, Persistence.SITUATIONAL, "Spawn-cost budget",
        "Too many of this mob are already nearby for the biome's spawn-cost budget. Kill or move them."),
    /**
     * {@code Mob.checkSpawnObstruction} - liquid in the body, or another entity
     * already standing in the space.
     *
     * <p>Split out of {@link #POSITION_CHECK} because it is the one part of that
     * check with a concrete, nameable cause. Folding it in produced the useless
     * "something rejected the mob" verdict for the very common case of a player
     * standing in the spot they are probing.
     */
    SPAWN_OBSTRUCTED(Layer.MOB, Persistence.SITUATIONAL, "Space is clear",
        "Something is already standing in this space, or it is flooded."),
    /** {@code EventHooks.checkSpawnPosition} - NeoForge PositionCheck, i.e. other mods' veto. */
    POSITION_CHECK(Layer.MOB, Persistence.STANDING, "Other mods",
        "Another mod's spawn rules rejected this mob at this position.");

    /**
     * Whether a rule's verdict survives the player walking away.
     *
     * <p>This distinction is the difference between a useful answer and a useless
     * one. You are always within 24 blocks of the block you are pointing at, so
     * {@link #PLAYER_DISTANCE} always fails for a probed position - reporting that
     * as "nothing can spawn here" would make the headline worthless in the exact
     * case the tool exists for. Situational rules are reported, but they never
     * become the headline while a standing reason exists.
     */
    public enum Persistence {
        /** True regardless of who is nearby, what the caps are, or the current settings. */
        STANDING,
        /** Depends on players, caps or settings right now, and reverts when they change. */
        SITUATIONAL
    }

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
    private final Persistence persistence;
    private final String title;
    private final String remedy;

    SpawnRule(Layer layer, Persistence persistence, String title, String remedy) {
        this.layer = layer;
        this.persistence = persistence;
        this.title = title;
        this.remedy = remedy;
    }

    public Persistence persistence() {
        return this.persistence;
    }

    /** True when this rule's verdict would still hold once every player walked away. */
    public boolean standing() {
        return this.persistence == Persistence.STANDING;
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
