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

Guardrails:
  - No em-dashes (use hyphens, commas, or restructure)
  - No mod-internal jargon (SpawnVerdict, PositionCheck, MobCategory, auditType)
  - Concrete over abstract: name the actual rule, not "various factors"
  - Do not oversell the alpha. The known limits stay on the page; a diagnostic
    tool that overstates itself is doing the exact thing it exists to prevent.
  - Links use absolute GitHub URLs (CF readers have no repo context)
-->

**Your mob farm is not spawning and you do not know why.** Spawn Detective tells you. Anchor the block, walk away, pick a mob, and it names the rule that stopped it: the light level, the floor, the mob cap, the biome, your own hitbox, the spawn-cost budget, or another mod's veto.

It does not guess, and it does not reimplement Minecraft's rules. It runs the game's real spawn checks against that exact block and reports what they returned.

## How to use it

1. **Sneak + right-click a block** with the Spawn Probe. That anchors the space above it, where a mob would stand.
2. **Walk at least 25 blocks away.**
3. **Right-click** anywhere. The report opens.
4. **Pick a mob.** Search by name or by id, or click one from the list of mobs the biome actually spawns there.

Craft the probe from a sapling above a stick.

**Why you have to walk away.** Standing next to a block to point at it breaks two real spawn rules: nothing spawns within 24 blocks of a player, and your own body fills the space the mob needs. Anchoring lets you take the reading for real instead of a reading of you standing there. The header shows your live distance from the anchor, in yellow while you are still close enough to be the problem.

Your mob choice sticks, so checking twenty blocks for zombies costs one selection rather than twenty.

## Three answers, not two

* **Green.** It can spawn here.
* **Red.** It never will. The floor, the light, the biome, the hitbox.
* **Yellow.** Only something temporary is stopping it. The mob cap is full, the difficulty is set to peaceful, someone is standing there. Clear that and it spawns.

Yellow having its own colour is the point. "Safe" and "safe only right now" call for opposite actions, and folding them together is the most common way a spawn-proofing check misleads you.

Under the verdict, **Why** lists the mob's full walk through every gate, with the measurement behind each one. Not "light: failed", but the block light and sky light it actually read.

## It knows your pack's mobs

Spawn Detective asks the game rather than hardcoding what it thinks the game does, so it answers for mobs from mods it has never heard of. If a mod adds a mob with a custom spawn condition, the probe runs that mod's own check and reports the result.

The same goes the other way. When another mod cancels a spawn through the standard events, the report says so instead of blaming a vanilla rule that was fine.

## Jade and JEI

**With [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) installed**, this is at its best passively. Hold the probe with a mob selected and look around: the tooltip shows that mob's verdict for whatever block you are looking at, live, as you sweep a room. You can light a spawn-proofed room by walking it.

The colours are inverted there on purpose. In a look-at tooltip you are usually spawn-proofing, so a possible spawn is the bad news: `can spawn` is red, `cannot spawn` is green.

**With [JEI](https://www.curseforge.com/minecraft/mc-mods/jei) installed**, the probe gets an information page explaining the gesture. There are no recipes to browse; the mod adds one item.

Both are optional and neither is bundled.

## From the console

```
/spawndetective                        check where you are standing
/spawndetective at <x y z>             check a position
/spawndetective for <entity>           check one mob here
/spawndetective at <x y z> for <entity>
```

Text output, so it works from the server console and from command blocks. It needs gamemaster permission, because the report exposes server-wide mob cap state.

## Installing

Drop the jar in `mods/`. It needs NeoForge for the matching Minecraft version and nothing else.

**Install it on both the client and the server.** The checks run server-side, because that is where live spawn state lives and where other mods' spawn events fire. The report renders client-side. On a server without it, the probe does nothing.

Safe in a pack: no world content, no worldgen, no recipes beyond the probe itself, and the recipe can be switched off in the config for packs that would rather hand the item out another way.

## Alpha, and what that means

This is a first release. Alpha because it has not been run outside a development environment yet, not because anything is known to be broken. It ships with 49 automated tests and 34 in-world tests, and those tests assert the cause: a sealed, lit chamber has to come back attributed to the light and not to the floor.

The limits it has, stated rather than hidden:

* Where a cause cannot be narrowed to one rule, the report says so and offers leads instead of picking one. A vague true answer beats a confident wrong one.
* Beyond the floor, it cannot yet name which mob-specific condition is failing, the kind a monster spawner would bypass. A drowned needing water is an example.
* It reports that another mod blocked a spawn, but not which mod.
* English only for now.

**If it ever gives you a wrong answer, please report it.** Being wrong is the only bug that really matters in a tool like this, and there is an issue template built for exactly that report.

## Links

* [GitHub repository](https://github.com/Flatts3000/spawn-detective)
* [Report a wrong answer or a bug](https://github.com/Flatts3000/spawn-detective/issues)
* [Version history](https://github.com/Flatts3000/spawn-detective/blob/main/CHANGELOG.md)
* [Community Discord](https://discord.gg/r6MhZ73nsM)

NeoForge only. No Fabric port planned.
