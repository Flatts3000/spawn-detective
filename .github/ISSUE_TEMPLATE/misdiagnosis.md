---
name: Misdiagnosis
about: Spawn Doctor gave you the wrong answer, or no answer where it should have had one
title: "[Misdiagnosis] "
labels: ["misdiagnosis"]
assignees: []
---

<!--
  This is the most valuable report you can file. Being wrong is this mod's only
  real bug class, and a report that pins down one wrong answer is worth more than
  a dozen feature ideas.
-->

## What it said

<!-- The verdict and the named cause. A screenshot of the report screen is ideal;
     the "Why" list below the banner is the part that matters most. -->

**Mob (namespaced id, e.g. `minecraft:zombie`):**
**Verdict shown (green / red / yellow):**
**Cause it named:**

## What was actually stopping it

<!-- What do you believe the real cause was? -->

## How you established that

<!-- Strongest to weakest, roughly:
     - A mob spawned there after you changed exactly one thing (say which)
     - Another tool or a manual check of the vanilla rule disagreed
     - Reasoning about the spot

     A hunch is still worth filing. Say which of these it is. -->

## The location

- **Dimension:**
- **Coordinates (the anchored block):**
- **Biome:**
- **Describe the spot:** <!-- cave, sealed room, open field, spawn platform, roof
                              of a farm, on top of a slab, in water... -->

## Can you share the world?

<!-- The engine is deterministic given a world and a position, so a world - or a
     small superflat recreation of just this spot - turns this from a discussion
     into a test case. Attach a zip, or say "no" and we'll work from the details. -->

- [ ] World or recreation attached
- [ ] Can produce one if it would help
- [ ] Can't share it

## Environment

- **Minecraft version:**
- **NeoForge version:**
- **Spawn Doctor version:**
- **Other mods installed (or modpack name and version):**
- **Single-player or dedicated server:**
- **Which surface:** <!-- report screen / Jade tooltip / /spawndoctor command -->

## Did the surfaces agree?

<!-- If you checked more than one - the screen, the Jade tooltip, the command - did
     they say the same thing? They resolve through the same code and are supposed
     to be incapable of disagreeing, so a disagreement is itself a serious bug. -->

## Logs

<details>
<summary>Log excerpt</summary>

```
(paste here)
```

</details>
