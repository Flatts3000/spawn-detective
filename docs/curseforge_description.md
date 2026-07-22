<!--
This file is the canonical source for the CurseForge project Description.

Workflow:
  1. Edit this file when you want to update the CF page.
  2. Copy the body (everything below this comment) into the CF Description field.
  3. Save on CF.

Why a separate doc instead of reusing README.md: the CF Description is a
self-contained onboarding surface for players who land on the CF page and never
visit GitHub. README.md targets contributors and people already using the mod.

This page is FEATURE-focused, NOT a changelog. Per-version history lives in
CHANGELOG.md and is linked below.

Voice: the quest-voice spec at
F:\minecraft-repos\mc-pack-toolkit\quest-voice\voice_spec.md. Teach the player who
has never debugged a farm, guide the one who has, cut every span that does neither.
No selling: this mod either answers the question or it does not, and a page that
hypes a diagnostic tool is undercutting the only thing it is for.

Guardrails:
  - No em-dashes or en-dashes
  - No mod-internal jargon (SpawnVerdict, PositionCheck, MobCategory, auditType)
  - Name the actual rule, never "various factors"
  - The known limits stay on the page
  - Absolute GitHub URLs (CF readers have no repo context)
-->

**Anchor a block, walk away, pick a mob.** Spawn Detective tells you whether that mob can spawn on that block, and which rule stopped it: the light, the floor, the mob cap, the biome, your own hitbox, the spawn budget, or another mod's veto.

It does not guess. It runs the game's own spawn checks against that block and reports what came back.

## Using it

Craft the Spawn Probe from a sapling over a stick.

1. Sneak + right-click a block. That anchors the space above it, where a mob would stand.
2. Walk 25 blocks off.
3. Right-click. The report opens.
4. Pick a mob. Search by name or id, or take one from the list of mobs that biome spawns.

**Walking away is not busywork.** Standing next to a block breaks two real spawn rules: nothing spawns within 24 blocks of a player, and your own hitbox fills the space the mob needs. Anchor it, step back, and you get a reading of the block instead of a reading of you standing there. The header tracks your distance and stays yellow until you are far enough out.

Your mob choice sticks. Checking twenty blocks for zombies is one pick, not twenty.

## Green, red, yellow

Green spawns. Red never will: the floor, the light level, the biome, the hitbox.

Yellow is the one worth having. Nothing permanent is wrong, something temporary is in the way, and clearing it lets the mob in. A full mob cap does this. So does peaceful. A room you spawn-proofed and a room that is quiet because the cap is full look the same until something changes, and answering both with "safe" is how a spawn-proofing pass misses a corner.

Under the verdict, **Why** walks the mob through every gate with the measurement behind each one. Not "light: failed" but the block light and sky light it read.

## Mobs from other mods

It asks the game rather than hardcoding what it thinks the game does. A mob from a mod it has never seen gets the same treatment: that mod's own spawn check runs, and the report says what the check returned.

Same going the other way. When another mod cancels the spawn, you get told that, instead of a vanilla rule taking the blame for something that was fine.

## Jade

Install [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) and it works while you walk. Hold the probe with a mob picked and look around: the tooltip gives that mob's verdict for whatever block you are looking at. Light a room by sweeping it and watching the tooltip flip.

Colours invert there. In a look-at tooltip you are usually spawn-proofing, so `can spawn` is the bad news and shows red.

## JEI

[JEI](https://www.curseforge.com/minecraft/mc-mods/jei) gets an info page on the probe explaining the gesture. One item, no recipes to browse.

Jade and JEI are both optional and neither is bundled.

## From the console

```
/spawndetective                        check where you stand
/spawndetective at <x y z>             check a position
/spawndetective for <entity>           check one mob here
/spawndetective at <x y z> for <entity>
```

Plain text, so it runs from the server console and from command blocks. Needs gamemaster permission: the report shows server-wide mob cap numbers.

## Installing

Drop the jar in `mods/`. NeoForge, nothing else required.

**Both sides.** The checks run on the server, where the live spawn state is and where other mods' spawn events fire. The report draws on the client. On a server without it, the probe does nothing.

Safe in a pack: no worldgen, no world content, one item, and the recipe has a config switch for packs that would rather hand the probe out some other way.

## Alpha, and what that means

First release. Alpha because it has not run outside a development environment yet, not because something is known to be broken. It ships with 49 automated tests and 34 in-world tests, and those tests assert the cause: a sealed lit room has to come back blamed on the light and not on the floor.

What it cannot do yet:

* Some spots narrow to a group of rules instead of one. The report says so and hands you the leads. A vague true answer beats a confident wrong one.
* Past the floor it cannot name which mob-specific condition failed, the kind a spawner would skip. A drowned needing water is the example.
* It tells you another mod blocked the spawn, not which mod.
* English only.

**If it gives you a wrong answer, file it.** Being wrong is the only bug that counts in a tool like this, and there is an issue template built for that report.

## Links

* [GitHub](https://github.com/Flatts3000/spawn-detective)
* [Report a wrong answer or a bug](https://github.com/Flatts3000/spawn-detective/issues)
* [Version history](https://github.com/Flatts3000/spawn-detective/blob/main/CHANGELOG.md)
* [Discord](https://discord.gg/r6MhZ73nsM)

NeoForge only. No Fabric port planned.
