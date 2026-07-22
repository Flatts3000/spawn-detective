# Spawn Doctor

**Why won't this mob spawn here?** Anchor the block, walk away, pick the mob, read the answer.

Spawn Doctor replays Minecraft's real natural-spawn pipeline against one block and
one mob, and tells you which rule rejected it - the mob cap, the chunk not ticking,
the biome's spawn list, the floor, the light level, the hitbox, the spawn-cost
budget, or another mod's veto. It does not guess and it does not reimplement the
rules; it calls them.

It works for mobs from any mod, because it asks the game rather than hardcoding
what it thinks the game does.

- **Minecraft 26.1.2 / NeoForge 26.1.2.76 / Java 25**
- Client and server. Safe on a dedicated server; safe in a pack.
- Adds one item and one command. No world content, no worldgen, no recipes.

## Using it

| Gesture | What it does |
|---|---|
| **Sneak + right-click a block** | Anchor the space above it - where a mob would stand |
| **Right-click** (air, or anything) | Open the report for the anchored block |
| **Sneak + right-click the air** | Clear the anchor |

**Why two steps.** To point at a block you have to stand next to it, and standing
there breaks two real spawn rules: the 24-block player bubble, and the obstruction
check, because your own hitbox occupies the space the mob needs. Anchoring lets you
walk off and take the reading for real. The header shows your live distance from
the anchor, in yellow while you are still close enough to be the problem yourself.

**Then pick a mob.** Search by name or by namespaced id, or click one from *"Mobs
this biome spawns here"*. The banner becomes that mob's verdict:

- **green** - it can spawn here
- **red** - permanently blocked: the floor, the light, the biome, the hitbox
- **yellow** - blocked only *right now*: mob cap full, difficulty, someone standing there

Yellow being its own colour is the point. "Safe" and "safe only at this moment" are
different answers that call for opposite actions, and folding them together is the
most common way these tools mislead people.

The selection sticks, so checking twenty blocks for zombies costs one choice rather
than twenty. Below the banner, **Why** lists that mob's full walk through every
gate, with the measurement behind each verdict.

## The command

```
/spawndoctor                        audit where you are standing
/spawndoctor at <x y z>             audit a position
/spawndoctor for <entity>           audit one mob type here
/spawndoctor at <x y z> for <entity>
```

Text output, so it also works from the server console and command blocks. Requires
gamemaster permission, because the report exposes server-wide mob cap state.

## Building

```
JAVA_HOME=/path/to/jdk-25 ./gradlew build              # compile + unit tests
JAVA_HOME=/path/to/jdk-25 ./gradlew runGameTestServer  # in-world tests
JAVA_HOME=/path/to/jdk-25 ./gradlew runClient          # dev client
```

Both `build` and `runGameTestServer` are required to merge.

## How it stays correct

Every rule maps to a specific vanilla call site, documented in
[`docs/spawn_pipeline_map.md`](docs/spawn_pipeline_map.md). That file is the
contract: if a rule stops matching its call site, the mod is confidently wrong,
which is worse than not existing. It is the first thing to re-verify on a game
update.

The in-world suite asserts the *causes*, not just the verdicts - a sealed lit
chamber must be attributed to light and not to the floor - and several tests exist
specifically to keep old misdiagnoses from returning. A robustness suite runs the
auditor against every entity type in the registry, which in a modded instance means
every mob in your pack.

Where a cause genuinely cannot be narrowed, the report says so and offers leads
rather than picking one. A vague true answer beats a precise false one.

## Licence

MIT.
