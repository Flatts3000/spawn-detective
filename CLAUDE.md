# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project

Spawn Doctor is a **single-purpose diagnostic mod**: it answers "why won't mobs
spawn at this position?" definitively. Targets **NeoForge 26.1.2.76 on Minecraft
26.1.2, Java 25** (`JavaLanguageVersion.of(25)`; on this machine set
`JAVA_HOME="C:/Program Files/Java/jdk-25"` for every gradlew invocation).
NeoForge-only, no Fabric port, no Architectury layer.

A 1.21.1 backport is planned on a `mc-1.21.1` branch once the design settles.
Fixes flow old -> new only, as in the Productive Frogs repo.

## The one rule that matters

**Every reported rule must map to a real vanilla call site, in the real order,
reading real live world state.** Never hardcode a threshold that can be read from
`dimensionType()`, the biome, or the live spawn state. Never reimplement a spawn
predicate - call it.

A diagnostic that is confidently wrong is worse than no diagnostic. If a check
cannot be made honestly, report `Verdict.UNKNOWN` with the reason; do not guess.

`docs/spawn_pipeline_map.md` is the contract between this mod and the game. Any
change to `SpawnAuditor`'s rule set updates that file in the same commit, and a
game update means re-verifying every row of it before anything else.

## Acceptance criterion (standing)

**The mod must work on any world and any settings, vanilla or modded.** It has no
control over its inputs: any mod's entity type, any mod's `MobCategory` (it is an
extensible enum), any dimension height, any chunk generator, any unloaded chunk,
any custom `SpawnPlacementType`, a server that has not run a spawn tick yet.

Practically this means:
- Iterate `MobCategory.values()`, never a hardcoded list.
- Guard every call into third-party code (spawn predicates, chunk generators,
  entity constructors, `PositionCheck` handlers) and degrade to `UNKNOWN`.
- Handle "no player in the dimension", "no spawn state yet", "category declares no
  per-chunk max", "position outside build height".
- `AuditRobustnessTests` enforces this - it runs the auditor over every entity type
  in the registry, so in a modded instance it covers that pack automatically.

## Common commands

- **`./gradlew build`** - compile + JUnit. Required CI job.
- **`./gradlew runGameTestServer`** - the in-world suite. Separate required CI job;
  `build` does **not** invoke it. Run it before pushing any change to `audit/`.
- **`./gradlew runClient`** - dev client. **The only way to verify the overlay** -
  GameTest is blind to client rendering.
- **`./gradlew prepareAllRuns`** - regenerate run VM-args after a `clean`.

Run one in-world test: `runClient`, then `/test run spawndoctor:<test_name>`.

## Architecture

### `audit/` - the engine, server-side only

`SpawnAuditor.audit(level, pos)` walks the pipeline and returns an `AuditReport`:
world/chunk rules once, then a section per `MobCategory`, then a `Candidate` per
mob type on that biome's spawn list. `SpawnAuditor.auditType(level, pos, type)`
walks one specific type, skipping the biome list - that is what
`/spawndoctor for <entity>` and the tests use.

`SpawnRule`'s **enum order is the pipeline order**; the report walks it top to
bottom and the first `FAIL` is the headline. Adding a rule means inserting it at
the right position, not appending.

Two rules are deliberately not booleans, and both are explained at length in
`docs/spawn_pipeline_map.md`:

- **`PLACEMENT`** is decomposed by hand per placement type so the report says "the
  floor is Grass Block" rather than "placement: false".
- **`SPAWN_RULES`** is sampled 64 times (the predicate rolls the RNG) and then
  attributed by **re-running the same predicate under `SPAWNER` and
  `TRIAL_SPAWNER`**, which vanilla defines as exempting the floor check and the
  light check respectively. This is the mod's one real technique, and it is why it
  can diagnose mobs from mods it has never heard of. Do not replace it with a
  reimplementation of the vanilla predicates.

### `audit/AreaScanner` - the overlay's data source

Deliberately cheaper than a full audit: 8 rolls against the biome's 4 heaviest
monster types, not 64 against all of them. The trade is documented in the class.
Grades are `SpawnGrade`: red spawns now, yellow is blocked only by something
temporary, no marker is safe. **Keep yellow distinct from safe** - collapsing them
is how these tools mislead people.

### `network/` + `client/` - the overlay

Client asks (`ScanRequestPayload`), server grades on the server thread via
`context.enqueueWork`, client renders (`ScanResultPayload` -> `ClientScanState` ->
`SpawnOverlayRenderer`). The grid is a dense `byte[]`, not a position list.

Server-side clamps on radius and requester distance are load-bearing: a scan is
real server work done on request.

On 26.1, custom world geometry goes through **`SubmitCustomGeometryEvent` ->
`submitCustomGeometry(poseStack, RenderTypes.debugQuads(), renderer)`**. The older
`RenderLevelStageEvent` direct-buffer form is gone.

### Dist safety

`SpawnProbeItem.use` calls a client class from inside an `isClientSide()` branch -
safe because the branch never runs on a dedicated server, so the class is never
loaded. Keep client-only code behind such a branch or in an `@OnlyIn(Dist.CLIENT)`
class reached only from one.

## Conventions

- **Java 25, 4-space indent, no tabs, no wildcard imports.** Imports alphabetical,
  one block. Records for value types.
- **`Identifier`, not `ResourceLocation`** - 26.1 renamed it.
- **No em-dashes or en-dashes anywhere**, including comments and commit messages.
- **Conventional Commits**; one logical change per commit, body explains the why.
- **Docs filenames are snake_case.**
- **No hard mod dependencies.** Compat is JSON conditions or nothing.
- Comments explain *why*, especially where a number is a vanilla constant or where
  a check is deliberately approximate. Anyone reading this code is auditing it
  against Mojang's, and needs to know which is which.
