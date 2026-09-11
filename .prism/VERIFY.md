# Verifying a PRISM install

**For the agent that just installed PRISM, and for anyone re-checking later.**
Every command here was executed against a real render before being written
down; none is illustrative.

This is the counterpart to `SETUP.md`. That one installs; this one decides
whether the install is real. They are deliberately separate documents, for the
same reason `prism-doctor` exists: the agent that just spent fifteen minutes
wiring a repository is the least reliable judge of whether it wired it
correctly, so the checks here are mechanical and the verdict comes from the
doctor rather than from an opinion.

Installed at `.prism/VERIFY.md`, so it survives the install's own cleanup — which
deletes `prism-setup/` — and can be re-run against the repository at any time.

**Nobody is expected to type any of this.** There are 35-odd commands below and
they are the agent's, not the reader's: a person's whole interface is `./prism
status`, `./prism promote` and `./prism doctor`, and `setup` runs the doctor that
gives the verdict. This document exists because the verdict has to come from
somewhere mechanical, and because an agent asked to "check the install" with no
checklist checks whatever it thinks of first. If you are reading it as a human,
read it as the answer to *what did the agent actually verify* — the survey at the
top is what the setup command does before it writes anything.

The procedure has four parts, and the order is not optional:

1. **Survey** the repository *before* installing — this produces the expectations
2. **Verify the render** matched the survey
3. **Verify the mode** — `observe` and `enforce` diverge here, and only here
4. **The negative test** — the one that can fail in a way nothing else catches

> **The rule that governs all of it:** a check that passes because it examined
> nothing is worse than a check that fails. Several steps below exist only to
> prove a rule was pointed at real code.

---

# Survey the repository, before installing

Run all of this from the repository root **before the setup command runs**. Write the
answers down. They are what you check the install against.

## The shape of the repository

```sh
# modules
grep -c 'include(' settings.gradle.kts

# is there a presentation layer, and Root/Screen files?
git ls-files | grep -c '/presentation/'
git ls-files '*Root.kt' | wc -l
git ls-files '*Screen.kt' | wc -l

# a design system? DO NOT grep for it by hand -- an earlier version of this
# file used `object.*Colors`, which misses a `data class` palette, and that
# single grep was the root cause of the rule-set divergence. Run the probe:
python3 prism-setup/core/verify/lib/probe.py --root .
#   exit 0 = resolved   exit 3 = ambiguous, and a human must pick
#   AFTER the install, prism-setup/ is gone -- the same probe lives at
#   .prism/verify/lib/probe.py, which is the path to use when re-running this

# the shared core domain — the layer-purity rule's subject.
# Prefer the candidate whose parent segment is core/common/shared. Do NOT
# tie-break on file count: a measured repo had the shared domain and a
# feature's domain at 20 files each.
git grep -h '^package ' -- '*.kt' | sed 's/^package //; s/[[:space:]]*$//' \
  | grep -o '^.*\.domain' | sort | uniq -c | sort -rn

# navigation
git grep -l 'androidx.navigation' -- '*.kt' | head

# the test stack
git grep -lE 'import (io\.mockk|org\.mockito|com\.google\.common\.truth|io\.kotest|org\.robolectric)' \
    -- '*/src/test/*' '*/src/androidTest/*'
```

**These last two no longer decide anything.** The navigation and test-stack
rules are unconditionally active, so what these greps predict is the **volume
of findings you will see on day one**, not which rules install. A repository
using Truth will see all seven test-stack rules report on every test file —
that is expected, it is what the baseline is for, and it is no longer a reason
to switch anything off.

## The active count you should expect

**This used to be arithmetic over six switches you had to predict. It is now
one subtraction, and a command computes the operand.**

```sh
python3 prism-setup/core/verify/lib/probe.py --root .   # before installing
./prism probe             # after: prism-setup/ is removed
```

Sixty-seven of the seventy-two rules are unconditionally active — nothing in
the install can turn them off. Only two groups can be off, and only because
they guard a **name** the repository may not have:

| Group | Rules | Off when |
|---|---|---|
| `PALETTE_RULES_ACTIVE` | 2 | no **declaration** holds the raw colours — they are top-level `val`s |
| `THEME_RULES_ACTIVE` | 3 | no composable named `*Theme` |

```
72 − 2 (if PALETTE is false) − 3 (if THEME is false) = expected active
```

So there are exactly **four possible answers**: 72, 70, 69 or 67. Anything else
is a defect, and [When the count is not one of the four](#when-the-count-is-not-one-of-the-four) says which kind.

**Both worked examples are measured repositories, not invented ones:**

| Repository | Probe found | Expected |
|---|---|---|
| Foldio | `FoldioColors`, `FoldioExtendedColors`, `FoldioTheme` | **72 active, 0 inactive** |
| Tasky | no palette declaration (35 top-level `Color(0x…)` vals); `TaskyTheme` present | **70 active, 2 inactive** |

Tasky is the case worth understanding. It is a single-module app **with** a
theme composable and an extended-token holder whose colours are top-level
`val`s. `THEME_RULES_ACTIVE` stays true — the repo has a theme, so three of the
five design-system rules still apply — and only the palette pair goes off.

**Write the number down before you install.** Part 2 checks the install against
it, and a number computed after seeing the result proves nothing.

### Why this is now four answers instead of dozens

Ten installs of one unchanged repository once produced **four different rule
sets** — 63, 60, 49 and 43 of 72 — every one passing `prism-doctor`, because
six switches were decided by an agent reading prose. The same harness answered
the same question both ways forty minutes apart. Four of those switches are
deleted and the remaining two are answered by the probe, which is why the
expected count is now something you can look up rather than argue.

## When the count is not one of the four

| You got | What it means |
|---|---|
| a number that is not 72, 70, 69 or 67 | rules were switched off by hand, or the install came from a build older than the switch deletion. Check `.prism/VERSION`. |
| `t + f ≠ 72` | rules were lost or duplicated in the merge — a `detekt.yml` merge defect, not a switch |
| the right count, wrong members | the `awk` in 1.3 above names every rule that is off; compare its output against the two group lists below. (`prism-inventory.sh` section 2 does the same thing mechanically, but it is a maintainer tool and **is not in this archive** — do not go looking for it.) |

The two switchable groups, in full — check against this if a count comes out
wrong:

| Group | Rules |
|---|---|
| `PALETTE_RULES_ACTIVE` | ThemeColorDirectUse, UnwiredThemeColor |
| `THEME_RULES_ACTIVE` | PreviewMustWrapInTheme, RawColorLiteral, NoCustomCompositionLocal |

Everything else — all 67 — carries a literal `active: true` in the template.
`test_detekt_groups.sh` asserts that against the **template** rather than a
rendered file, so a rule cannot quietly regain a switch.

## Rules that scope themselves, and one that used to not

No prism rule is scoped by a path glob that could match nothing. Three used to
be: `ScreenStateOnlyInScreenComposable` and `StableAnnotationOnUnstableState`
carried `includes: ['**/presentation/**']`, and `ObserveAsEventsRequired`
carried `includes: ['**/*Root.kt']`. In a repository that named things
differently those three did not report the wrong thing — they **matched nothing
and passed**, which reads as success.

All three already scope themselves by what they are *about*: a `*State` class,
an `@Composable` taking a `*State` parameter, a `LaunchedEffect` that collects.
The includes are gone.

---

# Verify the render matched the survey

After the setup command finishes, before anything else.

## Count what actually landed

```sh
# active rules in the prism: section
awk '/^prism:$/{p=1;next} /^[a-z]/{p=0} p&&/^    active: true$/{n++} END{print n+0}' detekt.yml

# inactive
awk '/^prism:$/{p=1;next} /^[a-z]/{p=0} p&&/^    active: false$/{n++} END{print n+0}' detekt.yml
```

The two must sum to **72**. Anything else means rules were lost or duplicated.

**Compare `active: true` against the number from 1.2.** A mismatch is a finding
either way:
- **Fewer active than expected** → a check you should be receiving is switched off
- **More active than expected** → rules are pointed at a shape this repo does not
  have, and you will see false positives (or worse, silent passes — see 1.4)

## Name the rules that are off

```sh
awk '/^prism:$/{p=1;next} /^[a-z]/{p=0} \
     p&&/^  [A-Z][A-Za-z0-9]*:$/{r=$1} \
     p&&/^    active: false$/{sub(":","",r); print r}' detekt.yml
```

Check the list against 1.3. It should be **exactly** the union of the groups you
expected `false` — no strays, no omissions.

## Every `false` must carry its reason

```sh
grep -n -B40 '^    active: false$' detekt.yml | grep -i 'group switch' | sort -u
```

Each disabled group sits under a banner naming its switch and a `TO CHANGE THIS`
block. **A rule switched off with no banner above it is a finding** — six months
later nobody can tell a deliberate exclusion from an accident.

The window is 40 lines because the banners carry that `TO CHANGE THIS` block
now; a narrower `-B12` finds nothing and reads as though the banner were
missing. Anchor on `^    active: false$` rather than the bare string, or the
banners' own prose about placeholder names matches too.

## Every `true` must RESOLVE — the mirror of the last one

2.3 asks why a rule is off. Nothing asked the opposite question, and that is
exactly where the worst measured defect lived.

Some rules take the **name of a declaration in your repository**. A rule that is
`active: true` and points at a name that does not exist runs, matches nothing,
and reports success over the code it was pointed at. `'none'` is PRISM's
convention for an inert name — legal only beside a switch that is `false`.

```sh
# every consumer-named parameter, with the rule's own active line
grep -nE "^    (paletteObject|extendedTokenHolder|themeName):" detekt.yml

# each name must be a declaration you can open
git grep -nE '(object|class|data class|fun|val)[[:space:]]+<THE NAME>' -- '*.kt'
```

**Expect** every name to resolve. A placeholder — `none`, `n/a`, `TODO` — beside
an active rule is the failure. If the shape genuinely is not in your repository,
the correct answer is the group switch set `false` with the name `'none'`, not a
plausible-looking string.

Both halves are enforced now — `render.py` refuses to write the file, and the
rules refuse to run — so this check should be redundant on a fresh install. Run
it anyway on an install made before that, and after any hand edit to
`detekt.yml`, which render never sees.

> The mechanical version of this check lives in `prism-inventory.sh`, the
> delivery audit that ships with the test artifact rather than with an install.
> **It is not in this archive**, and earlier versions of this document told you to
> run it at `<path-to>/prism-inventory.sh` — a path nobody could fill in. What is
> installed is the pair above: `render.py` refuses to write a placeholder, and the
> rules refuse to run against one.

## The config declares what the rules module implements

```sh
./prism doctor
```

This is the real check, and it is mechanical: the doctor plants a deliberate
violation of a PRISM rule and confirms it was reported. Copying the rules module
in does **not** enable it — four separate things must land, and missing one fails
silently with `BUILD SUCCESSFUL` over unexamined code.

**Expected: a verdict, and PASS.** Read what it names; do not summarise it.


## A skipped architecture test is a check you did not receive

`staticAnalysis` exits 0 on a skipped test, and nothing in the payload counts
skips. So a konsist rule whose subject set came out empty is invisible to every
gate — green build, rule never ran.

```sh
grep -ho 'skipped="[0-9]*"' tooling/konsist/build/test-results/test/*.xml \
  | sort | uniq -c
```

**A non-zero skip count is not automatically wrong** — `assertAllWhereApplicable`
skips precisely so that "not applicable" and "misconfigured" stay different
facts. But each skip carries a reason, and you have to read it:

```sh
grep -h -A2 '<skipped' tooling/konsist/build/test-results/test/*.xml | head -40
```

| Reason says | Meaning |
|---|---|
| the shape genuinely is not in this repository | correct — no MVI, no domain layer |
| a package prefix that *should* exist | **a finding** — the parameter is pointed at the wrong place |

This is the check for `LAYER_PURITY_DOMAIN_PACKAGE` in particular. It is derived
rather than asked, and a wrong derivation cannot fail loudly by design — it
reports SKIPPED. **This grep is the only thing that surfaces it.**

---

# Verify the mode

The two postures diverge here. Run only your section.

## If you installed as `observe`

**What it claims:** findings that existed at install are recorded and do not
block. Every rule stays live. A *new* violation still fails.

### The record

```sh
./prism doctor
```

Expect `"posture": "observe"`.

**No gate reads this key** — it is a record, not a switch. It exists so the
doctor can compare the record against reality.

### The fragments — where the silent trap lives

```sh
./gradlew prismBaseline
find . -path '*/build/prism/baseline/*.xml' | sort
```

**Expect one file per detekt task, and no two tasks of the SAME MODULE sharing
a name.**

```sh
# must print nothing:
find . -path '*/build/prism/baseline/*.xml' \
  | sed 's|/build/prism/baseline/|  |' | sort | uniq -d
```

> **Compare within a module, not across the repository.** Until 0.6.3 this check
> compared bare basenames repository-wide and told you it "must be empty" — which
> on a multi-module repository it can never be. Every module runs the same
> `detekt` / `detektDebug` / `detektMain` tasks, so on 26 modules
> `detektBaseline.xml` legitimately appears 26 times. The check was reported by
> an installing agent that ran the meaningful version instead and wrote the
> defect down rather than working around it.
>
> **Why it matters at all.** detekt's baseline writer *replaces* the findings
> list rather than merging into it. If two tasks of one module write the same
> file, you keep only the last one's findings — and the baseline silently
> under-covers, with nothing reporting that it happened.

### Merge

```sh
./prism baseline
./prism baseline count
./prism baseline group
```

`collect` **refuses over an existing baseline** unless `--force`. That refusal is
correct behaviour — do not reach for `--force` to get past it.

One `detekt.baseline.xml` should now exist **per module with findings**, beside
that module's build script, and each should be tracked by git. There is no root
file: an id carries the bare filename, so a single root baseline recorded two
modules' same-named findings as one entry.

```sh
git status --short '*/detekt.baseline.xml'
./prism baseline count
```

`count` prints the total, the per-module split, and a `stale=` field — entries
naming a file the module no longer has, which suppress nothing.

**Record the count.** Nobody has measured day-one findings on a repository PRISM
did not grow up in.

### Green

```sh
./gradlew staticAnalysis
```

**Expect: detekt reports zero findings.** This is the entire claim of `observe`.

If it still reports findings you expected suppressed, before writing it up:

```sh
./gradlew staticAnalysis --no-configuration-cache
```

Gradle's configuration cache may not see a freshly written baseline. If the
second run is green and the first was not, **that is a finding** — it means the
docs need a sentence they currently do not have.

### What the baseline does *not* cover

`staticAnalysis` is ktlint + detekt + konsist.

- **ktlint** is *fixed*, never baselined — `./gradlew ktlintFormat`
- **konsist** has no baseline and should not get one

So if `staticAnalysis` fails after 3A.4, read *which* tool failed before
concluding the baseline is broken.

### The floor, if you accepted it

```sh
ls -l .git/hooks/pre-push
```

If you **declined** it: absent is correct, and `prism-doctor` must report that as
a **chosen state, not a gap**. A doctor that FAILs a supported configuration is a
doctor people learn to ignore — so a FAIL here is a finding.

---

## If you installed as `enforce`

**What it claims:** every gate blocks, and there is nothing suppressed.

> **Read this section knowing what it used to miss.** Until 0.6.0 it checked the
> record, the absence of a baseline, the hook, and a green build — and **every
> one of those passes on a repository where nothing blocks at all.** That is not
> hypothetical: the first outside install answered `enforce`, got an all-`observe`
> `.prism/scope.json`, and `prism-doctor` then reported *"posture: enforce —
> every gate blocks"* over four engines that blocked nothing. The check below is
> first because it is the only one that can tell the difference.

### The one check that decides it ⚠️

```sh
ls .prism/scope.json 2>/dev/null && echo "FINDING: this repository does not enforce"
```

**Expect: no such file.** Enforcing is the **absence** of `.prism/scope.json`,
not the word `enforce` anywhere. Every engine reads that file and treats "not
there" as *every engine, every module*; when it is there, whatever it says is
what happens, and the record in `prism.json` is a note no gate consults.

If the file exists, read it before concluding anything — a repository
mid-promotion legitimately has one, with some modules at `enforce`:

```sh
./prism status
./prism status
```

A file whose `default` block is four `observe` values is the defect above, not a
mid-promotion state. `./prism promote --all` is the fix, and it removes the file.

### The record, and whether anything agrees with it

```sh
./prism doctor
./prism doctor
```

Expect `"posture": "enforce"` **and** the doctor's D10 reporting that
`.prism/scope.json` agrees with it. D10 resolves the file to `enforce`,
`observe`, `mixed` or `unreadable` and **FAILs on disagreement**; `mixed` passes,
because a partly-promoted repository is a supported state and a doctor that fails
supported states is a doctor people learn to ignore.

A FAIL here is the whole finding, and it is not cosmetic: it means the answer
somebody gave at install was discarded.

### No baseline

```sh
find . -name detekt.baseline.xml -not -path '*/build/*' | grep . \
  && echo "FINDING: enforce install produced a baseline"
```

A missing baseline file is **inert by design** — detekt only passes `--baseline`
when the file exists. Its absence is the correct state here. A baseline **with
entries** under `enforce` is a repository that claims everything blocks while a
list of findings is exempt; `./prism status` names the count.

### The floor is real *and executable*

```sh
ls -l .git/hooks/pre-push
test -x .git/hooks/pre-push && echo OK || echo "FINDING: hook is not executable"
```

> **Git skips a non-executable hook silently.** No warning, no output, exit 0.
> It is the exact definition of an install that looks wired and is not, and it is
> why this is its own check rather than a glance at `ls`.

### The build is genuinely clean

```sh
./gradlew staticAnalysis
```

**Expect: passes.** If it fails, `enforce` was the wrong answer for this
repository — that is not a defect, it is the framework telling you the truth.
Reinstall as `observe` and note it.

---

# The negative test ⚠️ two halves, and you need both

**Everything above can pass while the install checks nothing.** This is the one
step that cannot.

**Read this first, because the obvious version of this test is wrong.** A rule
being *live* and a rule *blocking the build* are two different facts, and on a
`scoped` install they have different answers. `.prism/scope.json` is what
decides whether an engine blocks; `posture` in `prism.json` decides nothing and
is read by no gate. So a canary that only checks the exit code cannot tell "the
rules are not wired" from "this module is observing on purpose".

Do both halves. The first proves the rule set is real; the second proves it can
still stop you.

```sh
# pick any Kotlin source file that is NOT under test/ or androidTest/
echo 'fun prismCanary() { println("canary") }' >> <some-file>.kt
```

## The finding must be REPORTED

```sh
./gradlew staticAnalysis 2>&1 | grep NoConsoleLogging
```

**A line naming `NoConsoleLogging` and your file MUST appear**, whatever the
exit code. This is the half that cannot be faked: an install where the rules
never ran prints nothing here.

| Result | Meaning |
|---|---|
| **The finding is printed** | ✅ The rule set is live and it sees new code. |
| **Nothing is printed** | 🚨 **Stop and report.** Either the rules are not wired, or the baseline is absorbing *new* violations — which makes the install worse than not installing. |

Whether the build then *failed* depends on posture, and 4b is where you check it:

```sh
./prism status        # the module's detekt column: observe or enforce
```

- **`enforce`** — `staticAnalysis` must have **failed**. If it passed while
  printing the finding, that is a defect: report it.
- **`observe`** — `BUILD SUCCESSFUL` is **correct**. Observing means reported
  and not blocking; that is the whole point of the on-ramp. An install that
  installed as `enforce` has **no scope file at all** and so can
  only land in the `enforce` case above.

## And it must be able to BLOCK

With the canary still in place, make detekt block and run again.
**Take the restore point first** — during an install `.prism/scope.json` is
freshly placed and not yet committed, so `git checkout` cannot bring it back,
and the promote below is undone by restoring the file rather than by a verb
(there is deliberately no way to make something block *less* from the CLI):

```sh
cp .prism/scope.json scope.json.before

./prism promote --engine detekt

./gradlew staticAnalysis
```

**It MUST now fail, naming `NoConsoleLogging` at the canary's line** — and the
baselined findings must stay quiet. That is the single most important property
of the whole on-ramp: history suppressed, new code blocked.

Then undo both:

```sh
mv scope.json.before .prism/scope.json
git checkout -- <some-file>.kt
```

| Result | Meaning |
|---|---|
| **Fails, naming only the canary's line** | ✅ The on-ramp closes. Promotion is a real gate, and the baseline suppresses history rather than new code. |
| **Fails, naming baselined findings too** | The baseline is not being read. Report it. |
| **Passes** | 🚨 **Stop and report.** `enforce` is not reaching the build; `BUILD SUCCESSFUL` is being printed over a violation you just wrote. |

`NoConsoleLogging` is chosen deliberately: it is one of the **67 rules that are
unconditionally active**, so it is live regardless of what the probe answered in
Part 1. A canary that depended on a switch would prove nothing about a repo that
switched it off.

---

# Summary — the whole procedure

```sh
# ── Part 1: survey (BEFORE the setup command) ────────────────────────
git ls-files | grep -c '/presentation/'
git ls-files '*Root.kt' | wc -l
git grep -lE '(object|class|data class)[[:space:]]+[A-Za-z]*(Colors|Palette)' -- '*.kt'
git grep -l 'androidx.navigation' -- '*.kt' | head
git grep -lE 'import (io\.mockk|org\.mockito|com\.google\.common\.truth|io\.kotest|org\.robolectric)' \
    -- '*/src/test/*' '*/src/androidTest/*'
#    → compute expected active count: 72 − (rules of each switch you expect false)

# ── Part 2: the render (AFTER the setup command) ─────────────────────
awk '/^prism:$/{p=1;next} /^[a-z]/{p=0} p&&/^    active: true$/{n++} END{print n+0}' detekt.yml
awk '/^prism:$/{p=1;next} /^[a-z]/{p=0} p&&/^  [A-Z][A-Za-z0-9]*:$/{r=$1} \
     p&&/^    active: false$/{sub(":","",r); print r}' detekt.yml
grep -ho 'skipped="[0-9]*"' tooling/konsist/build/test-results/test/*.xml | sort | uniq -c
./prism doctor

# ── Part 3A: observe ─────────────────────────────────────────────────
./gradlew prismBaseline
find . -path '*/build/prism/baseline/*.xml' \
  | sed 's|/build/prism/baseline/|  |' | sort | uniq -d   # must be empty
./prism baseline
./prism baseline count
./prism baseline group
./gradlew staticAnalysis                                    # must be green

# ── Part 3B: enforce ─────────────────────────────────────────────────
ls .prism/scope.json 2>/dev/null && echo FINDING   # absence IS enforce
./prism doctor                      # D10 must not FAIL
test -x .git/hooks/pre-push && echo OK || echo FINDING
./gradlew staticAnalysis                                    # must be green

# ── Part 4: the canary (BOTH halves) ─────────────────────────────────
echo 'fun prismCanary() { println("canary") }' >> <some-file>.kt
./gradlew staticAnalysis 2>&1 | grep NoConsoleLogging       # MUST PRINT
./prism status                     # observe => BUILD SUCCESSFUL is correct
#                                    enforce => it must have FAILED
cp .prism/scope.json scope.json.before      # it is not committed yet
# then promote that one module's detekt in .prism/scope.json and:
./gradlew staticAnalysis                    # MUST FAIL, at the canary's line
mv scope.json.before .prism/scope.json; git checkout -- <some-file>.kt
```

Record everything in `RESULTS.md`, with the build stamp.
