# Branch policy

Two long-lived lines, one repo, one CurseForge project. This file is the contract
for which branch gets what and which direction fixes travel.

## The lines

| Branch | Game | Stack | Role |
|---|---|---|---|
| `main` | Minecraft 26.1.2 | NeoForge 26.1.2.76, Java 25 | **Active development.** All new work starts here. |
| `mc-1.21.1` | Minecraft 1.21.1 | NeoForge 21.1.230, Java 21 | **Backport.** Receives ported fixes; no original feature work. |

Each line owns its own `gradle.properties`, `CHANGELOG.md`, and dependency
coordinates (JEI and Jade ship different artifacts per game version). Those files
differ between the branches permanently and by design. Seeing them in a diff does
not mean something went wrong.

Version numbers are shared. A fix released as `0.2.1` on `main` is released as
`0.2.1` on `mc-1.21.1`, so a bug report naming a version identifies the same
behaviour on either game version. The jar name carries the Minecraft version
(`spawndetective-1.21.1-0.2.1.jar`), which is what distinguishes the files.

## Fixes flow new to old

**Fix on `main` first, then cherry-pick to `mc-1.21.1`.**

```
main         A---B---C          (fix lands here)
                  \
mc-1.21.1     ------C'          (cherry-picked, adapted for the 1.21.1 API)
```

**Never merge between the lines.** A merge would drag 26.1 API calls onto a branch
that cannot compile them, and the two branches' build files would fight forever.
Cherry-pick, adapt the API surface by hand, verify both suites on the target
branch.

This is the opposite direction from the Productive Frogs repo, and the difference
is worth understanding rather than copying. There, 1.21.1 was the mature stable
line and 26.1 was the new port, so fixes flowed old to new. Here, 26.1 is where
this mod is developed and 1.21.1 is a port of it. **The direction always runs from
the line where the work actually happens toward the line that trails it.**

### When a cherry-pick will not apply cleanly

Expect this. The auditor's logic is portable; the API it calls is not. A fix
touching `SpawnAuditor` usually needs `EntitySpawnReason` swapped for
`MobSpawnType` and `Identifier` for `ResourceLocation`, and a fix touching the
report screen may need rewriting outright because the two game versions render
GUIs through different pipelines.

When that happens, port the *intent*, not the diff, and reference the original
commit in the message:

```
fix: light attributed to the floor in sealed rooms

Port of a1b2c3d from main, adapted for 1.21.1: EntitySpawnReason ->
MobSpawnType, and the sampler needed the RandomSource passed explicitly
because SpawnPlacements.checkSpawnRules does not supply one here.
```

The regression test comes with it. A fix that ships to one line without its test
on the other is how the two lines silently diverge in behaviour.

## What each line accepts

**`main`** takes everything: features, fixes, refactors, docs.

**`mc-1.21.1`** takes ported fixes, security fixes, and the docs that describe
them. It does **not** take original features. If something can only exist on
1.21.1, it is a strong sign it belongs in a different mod.

**Neither line takes a rule the other cannot report.** The two must agree about a
world, or `docs/spawn_pipeline_map.md` stops being a single contract and the mod
starts giving version-dependent answers to a version-independent question. If a
1.21.1 API genuinely cannot support a rule that `main` reports, the 1.21.1 build
reports `UNKNOWN` for it with the reason, and the pipeline map says so.

## Feature branches

Branch from the line you are targeting, usually `main`:

```
feat/<short-description>      new capability
fix/<short-description>       bug fix
docs/<short-description>      documentation only
chore/<short-description>     tooling, build, deps
```

Name a fix branch after the misdiagnosis it corrects rather than the code it
touches: `fix/light-blamed-on-floor` beats `fix/spawnauditor-refactor`. The branch
name is the first thing a reviewer reads.

Branches are deleted on merge. Both lines squash-merge.

## Protection

Both `main` and `mc-1.21.1` are protected:

- Required status checks: `build` and `gameTest`, both up to date with the base
- Conversations must be resolved
- No force pushes, no deletion
- Squash merge only

The same CI workflow runs on every branch, so a `mc-1.21.1` push is checked by the
same two jobs against that branch's own stack.

## Releasing

Both lines publish to **one CurseForge project (1621450)**, tagged with their game
version. Release them in whichever order suits; there is no requirement that
`main` ship first, and a 1.21.1-only hotfix is a legitimate release.

Each line's `CHANGELOG.md` covers only that line. Do not copy `main`'s history onto
`mc-1.21.1`; a player on 1.21.1 reading about a fix they never had is worse than a
short changelog.

## A game update, and when a line dies

A new Minecraft version means a new line branched from `main`, and `main` moves to
the new version. The old line either freezes (hotfix-only) or is retired.

**On any game update, re-verify every row of `docs/spawn_pipeline_map.md` before
anything else.** That file maps each reported rule to its vanilla call site, and a
call site that quietly changed shape is how this mod starts being confidently
wrong. The compiler will not catch it: a predicate that still compiles but now
takes its light check somewhere else produces a clean build and a false answer.

Retiring a line means saying so in its README and leaving the branch in place.
Deleting it would break every permalink in an issue thread.
