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
- **`./gradlew runClient`** - dev client. **The only way to verify the screen** -
  GameTest is blind to client rendering, and every layout bug this repo has had was
  found by looking, not by testing.
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
- **`SPAWN_RULES`** is sampled and then attributed by **re-running the same
  predicate under `SPAWNER` and `TRIAL_SPAWNER`**, which vanilla defines as
  exemptions. This is the mod's one real technique, and it is why it can diagnose
  mobs from mods it has never heard of. Do not replace it with a reimplementation of
  the vanilla predicates. Two hard-won constraints, both with regression tests:
  **never name a member of the exemption group by elimination** (that shipped
  "drowned need sky"), and **require several seeds to agree** before making any
  claim, because predicates that short-circuit draw unequal numbers of random values
  and desynchronise a single-seed comparison.

### The anchor gesture, and why it exists

Sneak-right-click stores a `GlobalPos` on the item (`SDDataComponents.ANCHOR`,
lodestone-compass style); right-click reads it. This is not a convenience - it is
what makes the numbers real. Standing next to a block to probe it breaks the
24-block player rule and puts your hitbox in the mob's space, so an earlier build
audited "as if you had stepped away" and every headline was an assumption.
**Never reintroduce observer discounting.** If a reading is affected by where the
player stands, the answer is to let them stand somewhere else, not to pretend.

A world overlay used to exist (`AreaScanner`, `SpawnGrade`, scan payloads, a
`SubmitCustomGeometryEvent` renderer). It was removed: it graded a box against a
*sample* of the biome's mobs while the probe answers precisely for a named mob, and
two surfaces disagreeing about the same block is how a diagnostic tool loses trust.

### `client/screen/` - one block, one mob, one answer

The screen asks about a single mob. It used to audit every mob the biome offered
and summarise them in one banner, which produced verdicts like "slime +6 more -
needs sky": several findings averaged into a sentence true of nothing. **Averaging
answers does not produce an answer.**

`PositionReport` (cheap, sent on probe) carries what is true of the place;
`MobAuditPayloads` (expensive, sent on selection) carries the per-mob verdict. No
per-mob work happens until a mob is named. `MobSelection` keeps the choice sticky
across probes and drops the cached answer the moment the position changes, so a
verdict can never be shown against the wrong coordinates.

Layout rules learned from real breakage: right-aligned values must reserve the
label's width (`drawPair`) or they paint over it; disclosure triangles are drawn
with `fill` because the font's glyphs render as specks; and **widgets render after
the panel background**, or they end up buried under it.

### `integration/` - Jade and JEI, both soft

**Jade** (`compileOnly` only - a `runtimeOnly` dep would double-load against a real
install and trip the duplicate-modid check) shows the selected mob's verdict for the
looked-at block. Three constraints, all load-bearing:

1. It resolves through **`SpawnVerdict`, the same as the screen**. Two surfaces
   disagreeing about one block is how a diagnostic loses its reader.
2. `shouldRequestData` returns false unless the player holds a probe with a mob
   selected. The audit is real server work on every look-at tick.
3. Jade 26.1 forbids a data provider from also implementing a component provider -
   register the server half through a single-interface delegate sharing the client
   half's UID. And **every plugin UID needs a `config.jade.plugin_<modid>.<uid>`
   lang key**, or the client resource reload fails.

`scripts/fetch_dev_mods.py` drops Jade into `run/mods` for dev runs.

**JEI** gets an ingredient info page and deliberately nothing else. This mod has no
recipes; a recipe category would teach nobody anything.

The selected mob rides on the item (`SDDataComponents.SELECTED_MOB`) rather than in
client memory, because the server must read it off the held stack for Jade. That it
also survives a relog is the point, not a side effect.

### Dist safety

Client-only classes are reached only from `Dist.CLIENT` event subscribers or from
clientbound payload handlers, which never run on a server. **Do not annotate them
`@OnlyIn`** - NeoForge 26.1 removed that annotation'''s runtime effect and logs an
error for every mod still using it, which for a diagnostic mod means polluting the
logs it exists to help people read.

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
