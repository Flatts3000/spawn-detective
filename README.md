# Spawn Detective

**Why won't this mob spawn here?** Anchor the block, walk away, pick the mob, read the answer.

Spawn Detective replays Minecraft's real natural-spawn pipeline against one block and
one mob, and tells you which rule rejected it - the mob cap, the chunk not ticking,
the biome's spawn list, the floor, the light level, the hitbox, the spawn-cost
budget, or another mod's veto. It does not guess and it does not reimplement the
rules; it calls them.

It works for mobs from any mod, because it asks the game rather than hardcoding
what it thinks the game does.

- **Minecraft 26.1.2 / NeoForge 26.1.2.76 / Java 25**
- Client and server. Safe on a dedicated server; safe in a pack.
- Adds one item and one command. No world content, no worldgen, no recipes.

**Download:** not yet published - the first alpha is being packaged. Until then,
build from source (below) or take the jar from a green
[CI run](https://github.com/Flatts3000/spawn-detective/actions).

**Reporting:** [issues](https://github.com/Flatts3000/spawn-detective/issues) for bugs
and wrong answers, [discussions](https://github.com/Flatts3000/spawn-detective/discussions)
for questions. **If it told you the wrong thing, please file it** - use the
*Misdiagnosis* template. Being wrong is this mod's only real bug class, and one
pinned-down wrong answer is worth more than any feature request.

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

## Jade and JEI

**Jade** is where this mod is at its best passively. Hold the probe with a mob
selected and look at any block: the tooltip shows that mob's verdict for the space
above it, live, as you sweep a room. Note the colours are inverted from the screen's
- in a look-at tooltip you are spawn-proofing, so `can spawn` is **red** and
`cannot spawn` is **green**.

Both surfaces resolve through the same `SpawnVerdict`, so they cannot disagree about
a block. The audit only runs while you are holding a probe with a mob chosen; nobody
else pays for it.

Jade is a soft dependency and a manual install. For a dev session,
`python scripts/fetch_dev_mods.py` places it into `run/mods`.

**JEI** gets an info page for the probe and nothing else. There are no recipes or
machines here, so a recipe category would be theatre - but JEI is where people look
when they find an unfamiliar item, so the page explains the gesture.

## The command

```
/spawndetective                        audit where you are standing
/spawndetective at <x y z>             audit a position
/spawndetective for <entity>           audit one mob type here
/spawndetective at <x y z> for <entity>
```

Text output, so it also works from the server console and command blocks. Requires
gamemaster permission, because the report exposes server-wide mob cap state.

## Installing

Drop the jar in `mods/`. Needs **NeoForge for Minecraft 26.1.2** and nothing else.

Install it on **both sides**: the audit runs on the server (it reads live spawn
state and fires the spawn events mods hook), and the report renders on the client.
On a server without it, the probe does nothing.

**Jade** and **JEI** are optional. With Jade, the look-at tooltip shows the selected
mob's verdict live. With JEI, the probe gets an info page. Neither is required and
neither is bundled.

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

## Releasing

`./gradlew publishCurseForge` uploads the built jar. It needs two things that are
deliberately not in the repo:

- `curseForgeProjectId=<id>` in `gradle.properties`, once the project page exists
- `CURSEFORGE_API_KEY=<token>` in `.env` at the repo root (gitignored)

The changelog for the upload is pulled from this repo's `CHANGELOG.md` by matching
`## v<version>`, so the two cannot drift apart. Publishing is not wired into CI: a
release should be a deliberate act, and a pipeline that publishes on green will
eventually publish something nobody meant to ship.

The jar includes the in-world test suite (~60 KB). That is on purpose - a pack
author who suspects this mod is misreporting can run `/test run spawndetective:*`
inside their own pack, with their own mods loaded. For a mod whose entire value is
being right, shipping its self-verification is worth the download size.

## Contributing

[`CONTRIBUTING.md`](CONTRIBUTING.md) has the details. The short version: both test
suites pass, changes to the rule set update `docs/spawn_pipeline_map.md` in the same
PR, and no cause is ever claimed by elimination.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md). Security
issues go through [`SECURITY.md`](SECURITY.md), privately, not the issue tracker.

## Licence

MIT. See [`LICENSE`](LICENSE).
