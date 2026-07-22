# Contributing to Spawn Doctor

Thanks for your interest. This document covers how to file issues, what a good bug
report looks like for a diagnostic mod, and what a pull request needs to land.

## The bar this mod is held to

Spawn Doctor exists to answer one question correctly. **A diagnostic that is
confidently wrong is worse than no diagnostic**, because someone will spend an hour
digging out a room based on what it told them. Every change is judged against that.

Two rules follow from it, and they are not negotiable:

1. **Every reported rule maps to a real vanilla call site, in the real order,
   reading real live world state.** Do not reimplement a spawn predicate; call it.
   Do not hardcode a threshold that can be read from the dimension type, the biome,
   or the live spawn state.
2. **Never claim a cause by elimination.** If the evidence narrows to a group,
   report the group. `Verdict.UNKNOWN` with an honest reason is a correct answer; a
   confident guess is not. This mod has shipped that mistake before, more than once,
   and there is a regression test named for each occasion.

`docs/spawn_pipeline_map.md` is the contract between this mod and the game. Any
change to the rule set updates that file in the same PR.

## Reporting Issues

- **Misdiagnosis** - it gave you the wrong answer, or no answer where it should
  have had one. Use the **Misdiagnosis** template. This is the most valuable report
  you can file; be specific about what you believe the real cause was and how you
  established it.
- **Bugs** - crashes, broken rendering, anything that is not about the content of
  the answer. Use the **Bug Report** template. Include your Minecraft version,
  NeoForge version, and mod list or modpack name.
- **Feature ideas** - use the **Feature Request** template. Frame it as the question
  you wanted answered, not the UI you imagined.
- **General questions** - use
  [GitHub Discussions](https://github.com/Flatts3000/spawn-doctor/discussions)
  rather than the issue tracker.

Don't open issues for security vulnerabilities - see [SECURITY.md](./SECURITY.md).

### What makes a misdiagnosis report actionable

The engine is deterministic given a world and a position, so the single most useful
thing you can attach is **the world**, or a small superflat recreation of the spot.
Failing that:

- The exact block coordinates, dimension, and biome.
- The mob you selected, by namespaced id.
- What the report said, screenshot or text.
- What you believe was actually stopping the spawn, and how you know - a mob that
  spawned after you changed one thing is strong evidence; a hunch is a starting
  point, which is still worth filing.

## Submitting Pull Requests

### Branching

- `main` is protected - all changes land via PR.
- Branch from `main`, named like `fix/light-attributed-to-floor` or
  `feat/report-which-mod-vetoed`.
- Don't push directly to `main`.

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <short subject>

<body - explain WHY, not what>
```

Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci`, `perf`.

One logical change per commit. The body carries the reasoning; the diff already
shows the what.

### Code Quality Expectations

- **Tests are not optional here.** A change to `audit/` ships with a test that
  fails without it. A fixed misdiagnosis ships with a regression test **named for
  the mistake**, not for the feature - `no_blame_without_a_culprit` is the house
  style, and the javadoc explains the shape of the error so the next person
  understands what the assertion is defending.
- **Both suites pass.** `./gradlew build` runs the unit tests; `./gradlew
  runGameTestServer` runs the in-world tests. `build` does not invoke the second
  one. Both are required to merge.
- **Client changes need a `runClient` pass.** GameTest cannot see the screen. Every
  layout bug this repo has had was found by looking at it.
- **A failing test is not automatically a bug in the code.** Two of the existing
  tests were written with bad isolation and the engine was right both times. Work
  out which it is before you change anything.
- **Guard every call into third-party code.** Any mod's spawn predicate, chunk
  generator, entity constructor, or `PositionCheck` handler can throw. Degrade to
  `UNKNOWN`; never let a foreign exception take down the report.
- **No hard dependencies on other mods.** Jade and JEI are `compileOnly` and load
  only when present.

### Java Style

- Java 25, 4-space indent, no tabs.
- No wildcard imports. Imports in a single alphabetical block, no semantic groups
  (matches Mojang vanilla style and the existing files).
- Records for value types; `@Nullable` (JetBrains, ships with NeoForge) on ambiguous
  returns.
- **`Identifier`, not `ResourceLocation`** - 26.1 renamed it.
- **No em-dashes or en-dashes**, anywhere, including comments.
- Comments explain *why*, particularly where a number is a vanilla constant or a
  check is deliberately approximate. Anyone reading this code is auditing it against
  Mojang's and needs to know which is which.

### Before You Open a PR

1. `./gradlew build` passes locally.
2. `./gradlew runGameTestServer` passes locally.
3. CI green on your branch.
4. `docs/spawn_pipeline_map.md` updated if the rule set changed.
5. PR description explains the **why**.

### Review

- The maintainer reviews when bandwidth permits. This is an OSS hobby project;
  expect days, not hours.
- Push back on review feedback if you disagree. That is a normal part of it.
- Approved, green CI, no unresolved threads, and the maintainer squash-merges.

## What We Probably Won't Accept

- **Reimplementing a vanilla spawn predicate** to get a more specific answer.
  Specificity bought by duplicating Mojang's logic goes stale silently on the next
  update, which is exactly the failure mode this mod cannot have.
- **Observer discounting** - auditing "as if the player had stepped away". An
  earlier build did this and every headline became an assumption. The anchor gesture
  exists so the numbers are real.
- **A second surface that can disagree with the first.** The screen and the Jade
  tooltip resolve through the same `SpawnVerdict` deliberately. A world overlay was
  removed for exactly this reason.
- **Hard dependencies on other mods**, or a Fabric port - NeoForge-only by design.

## No bounties, no automated PRs

This project does **not** offer bounties and does not participate in Opire, Algora,
or any other third-party bounty platform. Comments invoking bounty-platform commands
(`/opire`, `/algora`, `/try`) are ignored, and an issue appearing on a bounty board
does not mean one is offered here.

**Unsolicited automated or bot pull requests are closed without review.** If you are
a human who wants to work on an issue, say so in a comment first, then open the PR
yourself.

## Maintainer Cadence

- Issue triage: within ~1 week.
- PR review: ~1 week, sometimes longer.
- Releases: irregular, driven by feature batches or upstream NeoForge updates.

A misdiagnosis report jumps the queue. Being wrong is this mod's only real bug class.
