# Security Policy

## Supported Versions

Spawn Doctor is pre-release; only the latest commit on `main` is supported. Once
stable releases are published, this section will be updated with a version table.

## Reporting a Vulnerability

If you discover a security vulnerability in Spawn Doctor, **please do not open a
public issue.** Use one of these private channels instead:

1. **GitHub Security Advisories** (preferred): the repo's
   [Security tab](https://github.com/Flatts3000/spawn-doctor/security/advisories/new).
   This is the canonical channel and routes directly to the maintainer.
2. **Direct contact**: message the maintainer via the GitHub profile at
   [@Flatts3000](https://github.com/Flatts3000).

## What Counts

For a Minecraft mod the realistic threat surface is small but non-empty, and this
one has a specific shape worth naming: it runs a real spawn simulation on the
**server**, on demand, from a **client** action.

- **Server crashes** triggered by auditing a position or an entity type - an
  unguarded call into a third-party spawn predicate, chunk generator, entity
  constructor, or `PositionCheck` handler. Any exception that escapes the auditor
  and reaches the server tick is a valid report.
- **Resource exhaustion** - a request that makes the server do unbounded work.
  The audit is capped (sample counts, per-category candidate limits) precisely so a
  crafted request cannot turn into a denial of service; a way around those caps is a
  valid report.
- **Malformed payload handling** - the network payloads carry a position, a
  dimension, and an entity id. A payload that causes a crash or an out-of-bounds
  read on the server is a valid report.
- **Information disclosure** - the report exposes server-wide mob cap state, which
  is why the command requires gamemaster permission. A path that surfaces that state
  to an unprivileged player is a valid report.

If you're unsure whether something qualifies, report it privately and we'll classify
it together.

## Response Timeline

- **Acknowledgement**: within 7 days.
- **Initial assessment**: within 14 days.
- **Fix + disclosure**: varies by severity. Critical issues get a hotfix release;
  lower-severity issues land in the next regular release.

This is a hobby OSS project - timelines are best-effort, not contractual.

## Disclosure

We follow coordinated disclosure: report privately, we work on the fix, and we
publish the advisory and the fix together. We'll credit the reporter unless you
request anonymity.
