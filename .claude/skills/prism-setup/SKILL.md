---
name: prism-setup
description: Install PRISM, a verification framework, into this repository — static analysis with custom detekt and ktlint rules, konsist architecture tests, coverage gates, and the git pre-push enforcement floor. Runs once per project. Use when the user asks to install, set up, or add PRISM.
user-invocable: true
---

# Install PRISM

Read **`prism-setup/setup/SETUP.md`** and follow it exactly, start to finish.

**That path is relative to the REPOSITORY ROOT, not to this skill's own
directory.** It is not part of this skill package — it is the payload the human
unpacked, and it sits beside their `settings.gradle.kts`:

```sh
ls prism-setup/setup/SETUP.md          # from the repository root
```

If it is not there, the payload has not been unpacked yet, or was cleaned up
after an install that already finished. Say so and stop. **Do not improvise an
install from this file** — it is a pointer, not the procedure.

**Ask the human the questions in step 2, but never stop waiting for an answer.**
If nothing can answer you — a `-p`/`exec` run, CI, any session with no human —
take step 2's unattended defaults (**posture `enforce`**), say which ones you
took, record them in `.prism/INSTALL-RECORD.md` as defaulted rather than chosen,
and finish the install. Stopping here having written nothing is a failed
install, not a safe one.

That file is the whole procedure and it is harness-neutral. This wrapper exists
only so Claude Code can find and invoke it.

Three things in it are not suggestions:

1. **`prism-doctor --preflight` runs before you write anything.** If it does not
   exit 0, stop and report. A partial install is worse than none.
2. **Render every template in one batch**, only after all six required
   parameters are confirmed. A template rendered from a guessed value produces a
   gate that reports success over nothing.
3. **`prism-doctor` gives the verdict, not you.** You installed it, so you are
   the least reliable judge of whether it worked. UNDETERMINED is not a pass.

When SETUP.md says "the agent chosen in step 2", the answer here is Claude Code:
install `adapters/claude-code/` and leave the other four untouched.
