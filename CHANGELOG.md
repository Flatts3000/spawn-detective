# Changelog

## v0.1.0-alpha.3

### Fixed

- **A green verdict no longer over-promises on a spot the spawner rarely reaches.**
  On a small platform in a void or skyblock world, every gate genuinely passes and
  the farm still sits empty, because the natural spawner almost never picks that
  height - and the report had no way to say so. It now measures how often a spawn
  attempt in the chunk anchors at the block's Y, reads that from the chunk's real
  surface heights rather than estimating it, and shows the answer beside the verdict.
  A lone platform in an empty chunk is exactly as eligible as a full floor and 256
  times as slow; the report says both. The verdict still reads "can spawn here",
  because it can, but it turns yellow and carries the wait.
- **The same applies to a mob that only sometimes passes its own rules.** One
  clearing its light roll three times in a hundred used to read identically to one
  that spawns on every attempt. The rate now travels with the yes.
- **The mob caps are visible again on every surface.** The report screen, the Jade
  tooltip and `/spawndetective for <entity>` all walked only the per-mob gates, so a
  player sitting against a full mob cap read a report with every line green and no
  cap row on it anywhere. Both caps now appear on that path, with the global one
  showing how much room is left (`62 / 70`) rather than only whether it is full.

## v0.1.0-alpha.2

### Fixed

- **The report screen is no longer blurred out by the Menu Background Blur video
  setting.** The screen described its report and then ran the background pass last,
  so Minecraft's menu-background blur (a full-screen effect, on by default) smeared
  the report along with the world behind it - the whole thing was only legible with
  the setting turned off. The background is now extracted first, before the report,
  so the report stays sharp whatever the setting is.

## v0.1.0-alpha.1

First release. Alpha because it has never been run outside a development
environment, not because anything is known to be broken.

**What it does.** Anchor a block with the Spawn Probe, walk away, pick a mob, and
it tells you whether that mob can spawn there and which rule stopped it. It walks
Minecraft's real spawn pipeline rather than reimplementing it, so it answers for
mobs from any mod, including ones it has never heard of.

- **Spawn Probe.** Sneak-right-click a block to anchor it, walk 25 blocks away,
  right-click to read. The two steps exist because standing next to a block breaks
  two real spawn rules: nothing spawns within 24 blocks of a player, and your own
  body fills the space a mob needs. Crafted from a sapling over a stick.
- **Report screen.** One mob, one answer. Search any mob by name or id, or pick
  from the ones the biome actually spawns there. The choice sticks between blocks
  and survives a relog. Below the verdict, the mob's full walk through every gate
  with the measurement behind each one.
- **Green, red, yellow.** Green spawns. Red never will. Yellow means only
  something temporary is stopping it, like a full mob cap - it will spawn once
  that clears. Those need different actions, so they are different colours.
- **Jade support.** Hold the probe with a mob selected and the look-at tooltip
  shows that mob's verdict for the block you are looking at, live. Colours are
  inverted there: when you are spawn-proofing, a possible spawn is the bad news.
- **JEI support.** An info page on the probe explaining the gesture.
- **`/spawndetective`** for the console and command blocks, including
  `/spawndetective for <entity>` to ask about one mob and a whole-position sweep.
- **Config.** `crafting.enabled` turns the recipe off for packs that would rather
  hand the item out themselves.

**Known limits, stated rather than hidden.**

- Where a cause cannot be narrowed down, the report says so and offers leads
  instead of picking one. A vague true answer beats a precise false one.
- Beyond the floor, it cannot yet name which mob-specific condition is failing -
  the ones a monster spawner would bypass, like a drowned needing water.
- It reports that another mod vetoed a spawn, but not which mod.
- English only.
- The item texture is placeholder art.
