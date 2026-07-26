# The spawn pipeline, and where each rule comes from

This is the document that keeps the mod honest. Spawn Detective's entire claim is
that its answers are the *real* rules, not a plausible reimplementation of them.
That claim survives exactly as long as this table stays accurate.

**When Minecraft updates, this is the first file to re-verify.** Read the current
`NaturalSpawner`, `SpawnPlacements`, `SpawnPlacementTypes`, `Monster`, `Mob` and
`ServerChunkCache` sources, walk this table top to bottom, and fix anything that
drifted. A rule that quietly stops matching its call site turns the mod from a
diagnostic into a confident liar, which is worse than shipping nothing.

Verified against **Minecraft 1.21.1 / NeoForge 21.1.230** on this branch.
`main` verifies the same table against 26.1.2. Both lines must report the same
rules; where a call site moved between versions, the row notes it.

## Order of evaluation

Vanilla decides in this order, and `SpawnAuditor` walks it in the same order. The
first `FAIL` is the headline answer because it is the first thing vanilla would
have rejected on.

| # | `SpawnRule` | Vanilla call site | Notes |
|---|---|---|---|
| 1 | `GAMERULE_MOB_SPAWNING` | `ServerChunkCache.tickChunks` -> `GameRules.RULE_DOMOBSPAWNING` | False empties the whole category list. On 26.1 the same rule is `GameRules.SPAWN_MOBS`. |
| 2 | `DIFFICULTY` | `ServerChunkCache.tickChunks` -> `NaturalSpawner.getFilteredSpawningCategories`, and again per mob in `Monster.checkMonsterSpawnRules` | Peaceful drops the hostile categories and nothing else. **Scoped, so it can only answer for the mobs it binds** - see below. |
| 3 | `WORLD_BORDER` | `ServerLevel.canSpawnEntitiesInChunk` (border half) | Split out from #4 so the report names which half failed. |
| 4 | `CHUNK_ENTITY_TICKING` | `ServerLevel.canSpawnEntitiesInChunk` -> `entityManager.canPositionTick` | This is simulation distance, not render distance. |
| 5 | `PLAYER_IN_SPAWN_RANGE` | `ChunkMap.anyPlayerCloseEnoughForSpawning` | The 128-block / 8-chunk spawn sphere. |
| 6 | `CATEGORY_GLOBAL_CAP` | `NaturalSpawner.SpawnState.canSpawnForCategoryGlobal` | `max * spawnableChunks / 289`. Counts read from the live spawn state. **`MARGINAL` when full, never `FAIL`** - see below. |
| 7 | `CATEGORY_LOCAL_CAP` | `LocalMobCapCalculator.canSpawn` | Per-player slice. Needs the AT on `SpawnState.localMobCapCalculator`. `MARGINAL` when full, as above. |
| 8 | `ATTEMPT_REACH` | `NaturalSpawner.spawnCategoryForChunk` -> `getRandomPosWithin` | Informational, never a `FAIL`. How often an attempt in this chunk anchors at this Y - see below. |
| 9 | `ANCHOR_NOT_CONDUCTOR` | `NaturalSpawner.spawnCategoryForPosition` (first branch) | Advisory: the real anchor is a random position sharing this Y. |
| 10 | `PLAYER_DISTANCE` | `NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint` | 24 blocks, i.e. `576.0` squared. |
| 11 | `WORLD_SPAWN_DISTANCE` | same method, respawn-data branch | 24 blocks from the world spawn point, same dimension only. |
| 12 | `BIOME_SPAWN_LIST` | `NaturalSpawner.mobsAt` | Includes the nether-fortress override **and** NeoForge's `PotentialSpawns` event. |
| 13 | `TYPE_SUMMONABLE` | `NaturalSpawner.isValidSpawnPostitionForType` -> `EntityType.canSummon` | |
| 14 | `DESPAWN_DISTANCE` | same method, `canSpawnFarFromPlayer` branch | Category despawn distance squared. |
| 15 | `PLACEMENT` | `SpawnPlacements.isSpawnPositionOk` | Decomposed by hand per placement type - see below. |
| 16 | `SPAWN_RULES` | `SpawnPlacements.checkSpawnRules` | Sampled, then attributed - see below. Fires NeoForge's `SpawnPlacementCheck`. |
| 17 | `NO_COLLISION` | `ServerLevel.noCollision(type.getSpawnAABB(...))` | |
| 18 | `SPAWN_CHARGE` | `NaturalSpawner.SpawnState.canSpawn` | Biome `MobSpawnCost`. Needs the AT on `SpawnState.spawnPotential`. |
| 19 | `SPAWN_OBSTRUCTED` | `Mob.checkSpawnObstruction` | Liquid in the body, or an entity already in the space. Split out so it can be named. |
| 20 | `POSITION_CHECK` | `EventHooks.checkSpawnPosition` | NeoForge `PositionCheck`, reported only once obstruction is ruled out. |

### Which rules reach which surface

Rules 1-5 and 8-11 are walked by `SpawnAuditor.auditPosition`, which is what a probe
click runs. Rules 6, 7 and 13-20 are walked by `auditType`, which is what the screen,
the Jade tooltip and `/spawndetective for <entity>` all resolve through. Rule 12 is
reached only by the whole-position sweep, which is the one caller that starts from the
biome list rather than from a named mob.

The two caps are deliberately in both: the sweep prints them once at the head of a
category, and `auditType` prepends them to the single mob it was asked about. They
were in neither of the interactive paths until 0.1.0-alpha.3, so a player sitting
against a full cap read a report with every visible line green and no cap row on it
at all. **A gate omitted from the report is as wrong as a gate reported wrongly, and
much harder to notice.**

### Scope: a world-list row that is not true of every mob

Rules 1-11 are evaluated once per position and shared by every mob asked about
there. Almost all of them earn that: a chunk that is not ticking, a world border, a
player 12 blocks away are facts about the place. `DIFFICULTY` is not. Vanilla applies
it in `getFilteredSpawningCategories`, which keeps a category when

```java
spawnEnemies || category.isFriendly()
```

so Peaceful removes the hostile categories from the tick and leaves every other one
spawning exactly as before. A swamp on Peaceful is still full of chickens.

Shared row, partial truth: the screen answered **"CHICKEN IS BLOCKED RIGHT NOW -
Difficulty: peaceful"** about a block that was spawning chickens. This is the same
shape as `ATTEMPT_REACH` below - anything in the world list becomes the headline for
every mob at the position - and it has the same resolution: the row may not be
allowed to answer for mobs it has no jurisdiction over.

So `SpawnRule` carries a `Scope`, `ANY` or `HOSTILE`, read off the live
`MobCategory.isFriendly()` rather than a list of category names, because
`MobCategory` is extensible and a mod's own category answers for itself. Consequences,
each pinned by a test:

- `SpawnVerdict` filters both its blocker search and its caveat search by the
  candidate's category. A rule that could never say no must not get to attach a
  "yes, but" either.
- `PositionReport.gatesOpen()` and `AuditReport.worldGatesOpen()` count only `ANY`
  rules. Their claim is "whatever the mob", and Peaceful is not that.
- Surfaces that print the shared world list beside one mob's verdict - the screen and
  `/spawndetective for` - render each row through `RuleResult.asAppliedTo`, which
  keeps the measurement and drops the verdict to `n/a`. Peaceful is still worth
  reading on the world list; in red under a green "chicken can spawn here" it is the
  report arguing with itself.

`SERVER_SPAWN_ENEMIES` carries the same scope for the same reason. It is declared but
not yet emitted: `spawn-monsters=false` on a dedicated server is a real gate this mod
does not currently report.

**The other half of Peaceful.** `Monster.checkMonsterSpawnRules` tests the difficulty
before it looks at the position, so on Peaceful the sampled predicate at #16 refuses
under every spawn reason and the attribution has no difference left to read. It
answered a zombie with a permanent "cannot spawn here - the mob's own spawn rules",
offering light, floor and biome as leads, none of which had been measured. `#16`
reports `UNKNOWN` with the reason in that case - but only after sampling, so a modded
hostile mob whose predicate ignores the difficulty keeps its real reading - and the
headline falls through to `DIFFICULTY`, which is situational and says to raise the
difficulty.

## The three rules that are not booleans

### The mob caps - competition, not refusal

`canSpawnForCategoryGlobal` is `getMaxInstancesPerChunk() * spawnableChunkCount / 289`,
where 289 is `MAGIC_NUMBER`, i.e. 17 squared. `SPAWN_DISTANCE_CHUNK` is 8, so **a
single player's spawn-eligible area is a 17x17 chunk square - 289 chunks, exactly the
divisor** - and that constant exists precisely to make one player yield the base
figure. For monsters that is a cap of 70, vanilla's familiar single-player hostile
limit, and in any overworld with caves under it the count sits pinned at that ceiling
more or less permanently.

The count is read live rather than assumed, because it is not always the full square:
`getNaturalSpawnChunkCount` counts the chunks actually eligible, which a world border
or unloaded chunks can trim. The row prints the numbers it measured.

So a full cap is the **steady state, not a defect**. It is the mechanism that stops
mobs accumulating without bound, and it is working when it is full.

Both cap rules therefore report `MARGINAL` when full rather than `FAIL`, for two
reasons:

1. **It is nearly always true, so it would nearly always be the headline.** This mod
   has already learned that shape once - see `Persistence` on `SpawnRule`, where
   `PLAYER_DISTANCE` always fails for a probed position because you are always within
   24 blocks of the block you are pointing at. A headline that is always the same has
   stopped being an answer, and the per-block question is the one the probe was
   pointed at.
2. **It is not true tick to tick.** `getFilteredSpawningCategories` rebuilds this every
   tick and mobs die and despawn continuously, so the cap oscillates at its ceiling
   many times a second and spawns keep happening throughout. That is why lighting the
   caves near a farm helps it, and why farms work at all. `MARGINAL` is defined as
   "permits a spawn, but only some of the time", which is exactly the situation.

The row still shows `70 / 70 FULL`, and the verdict carries it as its caveat, so the
reader sees the competition without being told their block is dead. The counter-case
is real and is served by the same row: a grinder holding 70 mobs alive really is why a
new farm is producing nothing, and the measurement says so.

### `ATTEMPT_REACH` - a measured rate, not a gate

Every other rule answers "may a mob spawn here". This one answers "how often is the
question even asked", and it exists because a single-block, single-instant verdict
cannot express it. In a void or skyblock world every gate can pass on a farm that
produces nothing, and the report has no way to say why - which is the exact case the
mod exists for, answered with a confident green.

The mechanism is `NaturalSpawner.spawnCategoryForChunk`:

```java
BlockPos start = getRandomPosWithin(level, chunk);
if (start.getY() >= level.getMinBuildHeight() + 1) { spawnCategoryForPosition(...); }

private static BlockPos getRandomPosWithin(Level level, LevelChunk chunk) {
    int x = pos.getMinBlockX() + level.random.nextInt(16);
    int z = pos.getMinBlockZ() + level.random.nextInt(16);
    int topEmptyY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
    int y = Mth.randomBetweenInclusive(level.random, level.getMinBuildHeight(), topEmptyY);
    return new BlockPos(x, y, z);
}
```

Inside `spawnCategoryForPosition` the three pack groups walk **x and z only** -
`yStart` never moves. So a block is reachable by an attempt anchored in its own chunk
exactly when that uniform roll lands on its Y. That is arithmetic over the chunk's
live `WORLD_SURFACE` heightmap, not a sample and not an estimate:

```
per column:  N = topEmptyY - minY + 1
             P = 1/N   when minY + 1 <= targetY <= topEmptyY, else 0
overall:     the mean of P over the chunk's 256 equally likely columns
```

**An empty column produces no spawn attempt at all.** `getHeight` returns `minY - 1`
for it, so `topEmptyY` is `minY`, so the only roll available is `minY` - which the
`>= minY + 1` gate discards. That single fact is the whole void-world mechanic, and
it falls out of vanilla's own arithmetic rather than out of a model of it.

`ServerChunkCache.tickSpawningChunk` runs `spawnForChunk` once per eligible chunk per
tick, one anchor roll per category, so the report quotes 20 rolls a second and says
"for monsters" when it does. Persistent categories are the exception:
`getFilteredSpawningCategories` only admits them when `gameTime % 400 == 0`, so
CREATURE rolls once every 400 ticks.

**What it does not claim.** It is not the farm's spawn rate, and it is not a
rejection:

- The pack walk dilutes it further in x and z, and that walk is a bounded random walk
  rather than a closed form, so it is not folded in.
- An anchor in a *neighbouring* chunk can walk into this block, so even a reading of
  zero is not proof that nothing spawns here. The row says so in as many words.
- Every later gate still applies. This is the odds beside the verdict, not a verdict.

For the same reason it is **never a `FAIL`** - `PASS` or `MARGINAL` only, plus
`UNKNOWN` when the chunk is not loaded. It sits in the world list, so a `FAIL` would
become the headline for every mob at the position, and "cannot spawn here" about a
block that can is the precise failure this file exists to prevent. A slow spot is
slow, not shut.

The `MARGINAL` threshold (a mean wait over 60 seconds between *attempts*, before any
gate is consulted) is a presentation decision, not a spawn rule. It marks where the
geometry rather than anything else in the report is what a player is waiting on.

### `PLACEMENT` - decomposed, not delegated

`SpawnPlacements.isSpawnPositionOk` returns one boolean for "is the physical spot
right", which is never a useful answer. `SpawnAuditor.auditPlacement` re-walks the
same checks by hand, per placement type, and reports the specific one that failed:

- **`ON_GROUND`**: floor `isValidSpawn` -> the spawn block itself -> headroom. Each
  empty-block check reports why (`isCollisionShapeFullBlock`, `isSignalSource`,
  non-empty `FluidState`, `PREVENT_MOB_SPAWNING_INSIDE`, `isBlockDangerous`),
  mirroring `NaturalSpawner.isValidEmptySpawnBlock`.
- **`IN_WATER`**: water fluid at the position, non-conducting block above.
- **`IN_LAVA`**: lava fluid at the position.
- **`NO_RESTRICTIONS`**: always passes.
- **Anything else** (a mod's own placement type): falls back to the vanilla boolean
  and names the implementing class, because there is nothing honest to decompose.

### `SPAWN_RULES` - sampled, then attributed by spawn reason

Two problems with the per-type predicate:

1. **It is random.** `Monster.isDarkEnoughToSpawn` rolls `random.nextInt(32)` for
   sky light and samples `monsterSpawnLightTest()` for block light. A single call
   reports a coin flip as a fact. So the auditor rolls it 64 times and reports the
   rate; a borderline position reads `MARGINAL 37% of rolls pass`.

2. **It is a black box.** Mods register their own predicates, and vanilla's are
   opaque compositions. Reimplementing them would rot on every update and would be
   simply wrong for modded mobs.

The fix for (2) is to re-run *the same predicate* under the spawn reasons vanilla
itself defines as exemptions:

| Spawn reason | Exempts |
|---|---|
| `NATURAL` | nothing - the real path |
| `SPAWNER` | `Mob.checkMobSpawnRules` (block below) and the surface-monster sky check |
| `TRIAL_SPAWNER` | the above, **plus** the light requirement (`ignoresLightRequirements`) |

So:

- NATURAL fails, SPAWNER passes -> the cause is somewhere in the spawner-exempt
  group. The **floor** is then checked directly with `isValidSpawn` and named if it
  is at fault. Anything else in that group is reported *as a group*, with its usual
  members offered as leads.
- SPAWNER fails, TRIAL_SPAWNER passes -> **the cause is light**, with the measured
  values and a remedy that distinguishes sky light from block light, since those are
  fixed by opposite actions.
- Both fail -> neither light nor floor. Reported honestly as "biome, height,
  weather, difficulty, or this mob's own condition".

### Two things this technique cannot do, learned the hard way

**Do not name a member of the exemption group by elimination.** The first version
reasoned "the exemption fixed it and the floor is valid, therefore sky access", and
told players that *drowned* need sky. They need water - `Drowned.checkDrownedSpawnRules`
gates that requirement behind `isSpawner` too, and any mod may gate anything there.
The group is open-ended, so ruling out the one member you can check does not
identify the rest. Only the floor is claimed, because only the floor is measured.

**Pair the spawn reasons on a shared seed, and require several seeds to agree.**
The comparison is only controlled while the predicate draws the same number of
random values each way, and some do not - `Slime.checkSlimeSpawnRules` short-circuits
through a chain of `nextFloat` calls. A single seed can desynchronise the runs and
manufacture a difference that has nothing to do with the exemption. `ATTRIBUTION_SEEDS`
independent seeds must agree before any claim is made.

This works for any mod predicate built out of the vanilla helpers, and degrades to
a truthful "not attributable" when it is not. It is the only part of the mod that
is a technique rather than a transcription, and it is why the mod can answer for
modded mobs it has never heard of.

## Access transformers

Two private fields on `NaturalSpawner.SpawnState` have no getter and no substitute:

```
public net.minecraft.world.level.NaturalSpawner$SpawnState spawnPotential
public net.minecraft.world.level.NaturalSpawner$SpawnState localMobCapCalculator
```

Without `spawnPotential` the biome spawn-cost budget (soul sand valley, the deep
dark) can only be guessed at, and it is routinely misdiagnosed as a light problem.
Without `localMobCapCalculator` the per-player cap cannot be read at all.

## What the mod deliberately does not claim

- **`ATTEMPT_REACH` (#8) measures one chunk's anchor roll, not a farm's output.** See
  the section above for the three things it leaves out.
- **A full mob cap (#6, #7) is not reported as a refusal.** It is the normal steady
  state of an overworld and it oscillates at its ceiling constantly, so it reports
  `MARGINAL` and qualifies the answer rather than replacing it.
- **The per-player cap (#7) is a boolean, not a count.** `LocalMobCapCalculator`
  keeps its per-player numbers in a private map behind a private nested `MobCounts`,
  and the three further access transformers that would reach them would bind this mod
  to an inner class name for a figure the global row already carries in actionable
  form. The row reports `canSpawn` and says which cap it is under.
- **The anchor-block rule (#9) is advisory.** A spawn attempt anchors on a random
  position in the chunk that shares the candidate's Y, not on the candidate itself.
  The report says so rather than pretending the check is exact.
- **`POSITION_CHECK` does not name the mod that vetoed.** Attributing an event
  result to a specific listener means walking the bus registration, which is a
  separate piece of work. It reports that a mod vetoed - a conclusion it only
  reaches after proving the space is clear and the mob itself accepts the position -
  but not which mod.
- **Members of the spawner-exempt group other than the floor are not named.**
  Pinpointing them would need a delegating `ServerLevelAccessor` that lies about one
  input at a time so the predicate can be re-run against a changed world view. That
  is the natural next step, and until it exists the report names the group.
