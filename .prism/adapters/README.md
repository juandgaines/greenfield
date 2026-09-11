# PRISM on Claude Code

> Generated from `core/adapters/harnesses.json` by a tool in the framework
> repository, `tools/adapter-readmes.py`, which is not part of an install.
> Edit the manifest, not this file.

**Tier 1.** Registration: `.claude/settings.json`

## What fires, and when

PRISM checks each file as you write it, verifies the work before Claude Code finishes its turn, and blocks at git push.

| Gate | When |
|---|---|
| formatting | after each file write |
| static analysis, compile, unit tests | at turn end |
| static analysis, compile, unit tests | at each subagent boundary |
| the full push gate | before `git push` |

Every one of them runs through `.prism/adapters/lib/prism-hook`, which is the
only entry point this registration names. The gates themselves know nothing
about Claude Code; the dispatcher translates this harness's event shape into the one
the engine reads.

## The floor

`.git/hooks/pre-push` is the gate that works with no agent running at all, and
it is what protects the repository when someone pushes from a terminal. It is
not a fallback for a harness that can block.

It is installed **unless it was declined at setup**, which is a recorded
decision rather than an omission: `.prism/prism.json` carries the posture,
`prism-doctor` reports which state this repository is in, and
`./prism promote --all` installs the floor later.

## Report-only, and what it does not change

An install may record the findings that already existed in
each module's `detekt.baseline.xml`, so that adopting PRISM does not begin with a failing
build. **The same hooks fire and the same gates run.** What changes is that
findings recorded at install are not reported — and a *new* violation of the
same rule still is.

Working that list down is what `.claude/skills/prism-burndown/SKILL.md` is for; `prism-doctor` counts what
is left, and reports when there is nothing.


## Review layer

This harness dispatches subagents concurrently, so it receives the full six-reviewer pipeline.
