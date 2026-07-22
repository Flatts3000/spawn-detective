<!--
  Thanks for contributing! Brevity is fine - the goal is to give the reviewer the
  context they need. See CONTRIBUTING.md for the correctness bar this mod is held to.
-->

## Summary

<!-- 1-3 sentences. What does this PR do and why? -->

## Type of Change

- [ ] `feat` - new capability
- [ ] `fix` - bug fix
- [ ] `refactor` - restructuring with no behavior change
- [ ] `docs` - documentation only
- [ ] `test` - test additions/updates
- [ ] `chore` - tooling, build, or infrastructure
- [ ] `ci` - CI/CD changes
- [ ] `perf` - performance

## Correctness

<!-- The load-bearing section for this repo. -->

- [ ] This PR does not change what the mod reports
- [ ] Every rule this PR touches still maps to its vanilla call site, and
      `docs/spawn_pipeline_map.md` is updated in this PR
- [ ] No cause is claimed by elimination anywhere in the new code - a narrowing to a
      group is reported as a group, and an unmeasurable check reports `UNKNOWN`
- [ ] Calls into third-party code (spawn predicates, chunk generators, entity
      constructors, `PositionCheck` handlers) are guarded and degrade to `UNKNOWN`

## Testing

- [ ] `./gradlew build` passes locally (unit suite)
- [ ] `./gradlew runGameTestServer` passes locally (in-world suite)
- [ ] New behavior has a test that fails without this change
- [ ] A fixed misdiagnosis has a regression test **named for the mistake**, with a
      javadoc explaining the shape of the error
- [ ] Client changes were verified by eye in `runClient` (GameTest cannot see the
      screen)

## Notes for Reviewer

<!-- Anything to look at twice? Known limitations? If a test needed changing,
     say why the test was wrong rather than the code. -->
