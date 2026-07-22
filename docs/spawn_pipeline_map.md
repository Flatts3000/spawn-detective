# The spawn pipeline, and where each rule comes from

This is the document that keeps the mod honest. Spawn Doctor's entire claim is
that its answers are the *real* rules, not a plausible reimplementation of them.
That claim survives exactly as long as this table stays accurate.

**When Minecraft updates, this is the first file to re-verify.** Read the current
`NaturalSpawner`, `SpawnPlacements`, `SpawnPlacementTypes`, `Monster`, `Mob` and
`ServerChunkCache` sources, walk this table top to bottom, and fix anything that
drifted. A rule that quietly stops matching its call site turns the mod from a
diagnostic into a confident liar, which is worse than shipping nothing.

Verified against **Minecraft 26.1.2 / NeoForge 26.1.2.76**.

## Order of evaluation

Vanilla decides in this order, and `SpawnAuditor` walks it in the same order. The
first `FAIL` is the headline answer because it is the first thing vanilla would
have rejected on.

| # | `SpawnRule` | Vanilla call site | Notes |
|---|---|---|---|
| 1 | `GAMERULE_MOB_SPAWNING` | `ServerChunkCache.tickChunks` -> `GameRules.SPAWN_MOBS` | False empties the whole category list. |
| 2 | `DIFFICULTY` | `Monster.checkMonsterSpawnRules` | Peaceful blocks monsters only; animals still spawn. |
| 3 | `WORLD_BORDER` | `ServerLevel.canSpawnEntitiesInChunk` (border half) | Split out from #4 so the report names which half failed. |
| 4 | `CHUNK_ENTITY_TICKING` | `ServerLevel.canSpawnEntitiesInChunk` -> `entityManager.canPositionTick` | This is simulation distance, not render distance. |
| 5 | `PLAYER_IN_SPAWN_RANGE` | `ChunkMap.anyPlayerCloseEnoughForSpawning` | The 128-block / 8-chunk spawn sphere. |
| 6 | `CATEGORY_GLOBAL_CAP` | `NaturalSpawner.SpawnState.canSpawnForCategoryGlobal` | `max * spawnableChunks / 289`. Counts read from the live spawn state. |
| 7 | `CATEGORY_LOCAL_CAP` | `LocalMobCapCalculator.canSpawn` | Per-player slice. Needs the AT on `SpawnState.localMobCapCalculator`. |
| 8 | `ANCHOR_NOT_CONDUCTOR` | `NaturalSpawner.spawnCategoryForPosition` (first branch) | Advisory: the real anchor is a random position sharing this Y. |
| 9 | `PLAYER_DISTANCE` | `NaturalSpawner.isRightDistanceToPlayerAndSpawnPoint` | 24 blocks, i.e. `576.0` squared. |
| 10 | `WORLD_SPAWN_DISTANCE` | same method, respawn-data branch | 24 blocks from the world spawn point, same dimension only. |
| 11 | `BIOME_SPAWN_LIST` | `NaturalSpawner.mobsAt` | Includes the nether-fortress override **and** NeoForge's `PotentialSpawns` event. |
| 12 | `TYPE_SUMMONABLE` | `NaturalSpawner.isValidSpawnPostitionForType` -> `EntityType.canSummon` | |
| 13 | `DESPAWN_DISTANCE` | same method, `canSpawnFarFromPlayer` branch | Category despawn distance squared. |
| 14 | `PLACEMENT` | `SpawnPlacements.isSpawnPositionOk` | Decomposed by hand per placement type - see below. |
| 15 | `SPAWN_RULES` | `SpawnPlacements.checkSpawnRules` | Sampled, then attributed - see below. Fires NeoForge's `SpawnPlacementCheck`. |
| 16 | `NO_COLLISION` | `ServerLevel.noCollision(type.getSpawnAABB(...))` | |
| 17 | `SPAWN_CHARGE` | `NaturalSpawner.SpawnState.canSpawn` | Biome `MobSpawnCost`. Needs the AT on `SpawnState.spawnPotential`. |
| 18 | `POSITION_CHECK` | `EventHooks.checkSpawnPosition` | NeoForge `PositionCheck`. Where other mods veto. |

## The two rules that are not booleans

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

- NATURAL fails, SPAWNER passes -> the cause is in the spawner-exempt group. The
  auditor then splits it definitively by testing `isValidSpawn` on the block below
  and `canSeeSky` directly, and reports "the floor" or "no sky access".
- SPAWNER fails, TRIAL_SPAWNER passes -> **the cause is light**, and the measured
  light values are reported alongside.
- Both fail -> the cause is neither light nor floor. Reported honestly as "biome,
  height, weather, difficulty, or this mob's own condition".

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

- **The anchor-block rule (#8) is advisory.** A spawn attempt anchors on a random
  position in the chunk that shares the candidate's Y, not on the candidate itself.
  The report says so rather than pretending the check is exact.
- **`POSITION_CHECK` does not name the mod that vetoed.** Attributing an event
  result to a specific listener means walking the bus registration, which is a
  separate piece of work. Today it reports that something vetoed, not who.
- **The overlay is coarser than the probe.** See `AreaScanner`: it grades against
  the biome's heaviest monster types with 8 rolls, not every type with 64. The
  trade is stated in that class and the probe remains the source of truth.
