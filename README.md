# Spawn Doctor

**Why won't mobs spawn here?** Point the probe at the block and find out.

Spawn Doctor replays Minecraft's real natural-spawn pipeline against one block
position and tells you which rule rejected it - the mob cap, the chunk not
ticking, the 24-block player bubble, the biome's spawn list, the floor, the light
level, the hitbox, the biome spawn-cost budget, or another mod's veto. It does not
guess and it does not reimplement the rules; it calls them.

It works for mobs from any mod, because it asks the game rather than hardcoding
what it thinks the game does.

- **Minecraft 26.1.2 / NeoForge 26.1.2.76 / Java 25**
- Client and server. Safe on a dedicated server; safe in a pack.
- Adds one item and one command. No world content, no worldgen, no recipes.

## Using it

### The Spawn Probe

| Action | What it does |
|---|---|
| Right-click a block | Audit the space **above** it - where a mob would stand |
| Sneak + right-click a block | Audit **that** block - for water and lava mobs |
| Right-click the air | Toggle the live overlay |

The report is one headline answer plus only what failed. Hover any mob line to see
its full rule walk and what to do about the blocker.

### The overlay

Colours mark what happens once you walk away:

- **Red** - a mob can spawn here right now.
- **Yellow** - physically spawnable, blocked only by something temporary: you are
  standing too close, the mob cap is full, or the difficulty is Peaceful. **This
  will spawn the moment that changes.**
- **No marker** - safe. Either the shape of the world rejects every mob, or your
  lighting already does.

Yellow being its own colour is the point. An overlay that folds "safe" and "safe
only while you stand here" into one colour is the most common way these tools
mislead people.

### The command

```
/spawndoctor                        audit where you are standing
/spawndoctor at <x y z>             audit a position
/spawndoctor for <entity>           audit one mob type here
/spawndoctor at <x y z> for <entity>
```

`for <entity>` skips the biome spawn list, so "why won't zombies spawn here" is
answerable even in a biome whose list has no zombies - which is exactly when
people ask.

Requires gamemaster permission, because the report exposes server-wide mob cap
state.

## Building

```
JAVA_HOME=/path/to/jdk-25 ./gradlew build              # compile + unit tests
JAVA_HOME=/path/to/jdk-25 ./gradlew runGameTestServer  # in-world tests
JAVA_HOME=/path/to/jdk-25 ./gradlew runClient          # dev client
```

Both `build` and `runGameTestServer` are required to merge.

## How it stays correct

Every rule the mod reports maps to a specific vanilla call site, documented in
[`docs/spawn_pipeline_map.md`](docs/spawn_pipeline_map.md). That file is the
contract: if a rule stops matching its call site, the mod is confidently wrong,
which is worse than not existing. It is the first thing to re-verify on a game
update.

The in-world test suite asserts the *causes*, not just the verdicts - a sealed lit
chamber must be attributed to light and not to the floor - and a robustness suite
runs the auditor against every entity type in the registry, which in a modded
instance means every mob in your pack.

## Licence

MIT.
