# PRISM install record — prismgreen

| | |
|---|---|
| Version | `0.6.3+3e62e3e` (see `.prism/VERSION`) |
| Installed | 2026-09-11 |
| Installed by | Claude Code (Opus 5, 1M context), via `/prism-setup` |
| Harness registered | `claude-code` (Tier 1 — in-turn gates fire, plus the push floor) |
| Repository shape | single module `:app`, freshly generated Android Compose project |
| Base branch | `main` |
| Preflight | PASS — 5 checks (Kotlin 2.4.10, no pre-existing detekt) |
| Doctor verdict | **PASS — 12 checks** |

---

## 1. What landed

### Theirs — yours to edit from now on

| Path | What happened |
|---|---|
| `tooling/prism-rules/` | **new** — 72 detekt rules as source. Edit them; they are yours. |
| `tooling/konsist/` | **new** — 23 architecture tests as source, rendered against this repo's packages. |
| `build-logic/` | **new** — the four convention plugins (`prism.ktlint`, `prism.detekt`, `prism.jacoco`, `prism.static-analysis`). This repo had no `build-logic`, so PRISM's landed whole rather than as a subproject. |
| `detekt.yml` | **new** — rendered. No detekt config existed before, so nothing was merged over. |
| `.editorconfig` | **new** — root. ktlint reads it, and so does your IDE, so both format to the same rules. |
| `.gitignore` | **appended**. `.gradle/` from the fragment was dropped as redundant: the existing `.gradle` already matches at every depth. Nothing of yours was reordered or removed. |
| `gradle/libs.versions.toml` | **merged** — only absent aliases added. Your `agp = 9.3.2` was kept over the fragment's 9.1.0. `detekt` is pinned exactly at `2.0.0-alpha.6` because the rule set compiles against that precise `detekt-api`. |
| `settings.gradle.kts` | `includeBuild("build-logic")`, `include(":tooling:prism-rules")`, `include(":tooling:konsist")`. |
| `build.gradle.kts` (root) | `id("prism.static-analysis")` registers the `staticAnalysis` task, plus `alias(libs.plugins.kotlin.jvm) apply false`. |
| `app/build.gradle.kts` | the three per-module ids: `prism.ktlint`, `prism.detekt`, `prism.jacoco`. |
| `app/detekt.baseline.xml` | **new, 6 entries — COMMIT IT.** |
| `app/ktlint.baseline.xml` | **new, 2 entries — COMMIT IT.** |
| `tooling/*/ktlint.baseline.xml` | **new, empty** — PRISM's own modules. |
| `.claude/settings.json` | **new** — the four hook registrations. No settings file existed before, so nothing was merged over. |
| `.claude/agents/`, `.claude/skills/` | six reviewer agents, plus the `verify`, `finalize` and `prism-burndown` skills. |
| `./prism` | **new, executable — COMMIT IT.** The one command you need afterwards. |

### PRISM's — leave it alone

`.prism/` — the engine, its assets, the parameter registry, the adapter, the
docs (`VERIFY.md`, `RULES.md`, `INSTALL.md`, `INSTALL-GREENFIELD.md`,
`INSTALL-BROWNFIELD.md`) and this record. `.prism/verify/state/` is gitignored
deliberately: it holds live gate verdicts, and committing one grants a PASS to a
machine that never ran the build the verdict describes.

### The AGP 9 wiring note

This project is on AGP 9, which carries its own embedded Kotlin plugin. Both
halves of the fix were applied: the catalog's Kotlin is pinned on the build's
plugin classpath in the root build file, and the two tooling modules use the
**unversioned** `id("org.jetbrains.kotlin.jvm")`. Reverting either half breaks
the build loudly — one with "already on the classpath with an unknown version",
the other with a Kotlin metadata mismatch that reads like a broken payload.

### Build output swept

`classdirs.py sweep` reported **no superseded class-output directories**.
Nothing was deleted.

---

## 2. Parameters — every value, and whether it was answered or defaulted

| Parameter | Value | Source |
|---|---|---|
| `PACKAGE_ROOT` | `com.juandgaines.prismgreen` | **ANSWERED** — the single `package` prefix under `app/src/main`. Confirmed live by `ScopeIntegrityTest` passing, which is the guard against konsist analysing an empty scope. |
| `MODULE_PACKAGE_ROOTS` | `"app" to "com.juandgaines.prismgreen"` | **ANSWERED** — one module in `settings.gradle.kts`, no leading colon. |
| `LAYER_PURITY_DOMAIN_PACKAGE` | `com.juandgaines.prismgreen.core.domain` | **ANSWERED (derived)** — this repo has **no `.domain` package at all**. The prefix recorded is the one that would be used if a shared core domain is added. The rule reports SKIPPED with that reason rather than passing green; see section 4. |
| `PALETTE_OBJECT` | `none` | **ANSWERED by the probe** — legal only because `PALETTE_RULES_ACTIVE` is false. |
| `EXTENDED_TOKEN_HOLDER` | `none` | **ANSWERED by the probe** — same pairing. |
| `THEME_OBJECT` | `PrismgreenTheme` | **ANSWERED by the probe**, at `app/src/main/java/com/juandgaines/prismgreen/ui/theme/Theme.kt:39`. Verified to resolve. |
| `PALETTE_RULES_ACTIVE` | `false` | **ANSWERED by the probe** — see section 3 and section 4. |
| `THEME_RULES_ACTIVE` | `true` | **ANSWERED by the probe** — this repo has a theme composable. |
| `BASE_BRANCH` | `main` | **ANSWERED, not defaulted.** It happens to equal the default, which is why it is worth saying how it was determined: this repository has **no remote**, so `refs/remotes/origin/HEAD` resolves to nothing, and `main` is the only branch that exists. `prism-doctor` confirms it resolves. Re-check this if an `origin` is added whose default branch is not `main`. |
| `HARNESS` | `claude-code` | **ANSWERED** — the harness that ran this install. |
| `POSTURE` | `mixed` | **DERIVED from measurement**, per section 5. The value first rendered (`enforce`) was provisional and was rewritten after the build ran. No gate reads this key; it is the record `prism-doctor` compares `.prism/scope.json` against. |

Parameters that took their shipped default without being asked, because no
template rendered here declares them and nothing consumes them yet:
`COVERAGE_ASSETS_DIR` (`.prism/assets`), `COVERAGE_DEFAULT_THRESHOLD` (`80`),
`COVERAGE_CRITICAL_THRESHOLD` (`100`), `EMULATOR_MIN_SDK` (`26`).
`render.py` reported no unresolved or surprising defaults.

The live coverage policy is **not** those keys — it is
`.prism/verify/thresholds.json`, `default: 80`.

---

## 3. The probe, verbatim

Re-runnable at any time with `./prism probe`.

```
PRISM design-system probe

  PALETTE_OBJECT         none                     no declaration holds three or more raw Color literals
  EXTENDED_TOKEN_HOLDER  none                     no class declares two or more Color-typed properties
  THEME_OBJECT           PrismgreenTheme          app/src/main/java/com/juandgaines/prismgreen/ui/theme/Theme.kt:39
  PALETTE_RULES_ACTIVE   false
  THEME_RULES_ACTIVE     true

  6 raw Color literals sit at top level, in files including:
    app/src/main/java/com/juandgaines/prismgreen/ui/theme/Color.kt:5
    app/src/main/java/com/juandgaines/prismgreen/ui/theme/Color.kt:6
    app/src/main/java/com/juandgaines/prismgreen/ui/theme/Color.kt:7
    app/src/main/java/com/juandgaines/prismgreen/ui/theme/Color.kt:9
    app/src/main/java/com/juandgaines/prismgreen/ui/theme/Color.kt:10
  There is no declaration to name. PALETTE_RULES_ACTIVE=false is
  the correct answer, and those colours are checked by no rule.
  Record that in .prism/INSTALL-RECORD.md -- it is a gap you chose.
```

The probe exited **0** — resolved, not ambiguous. No human tie-break was needed
and none was asked for. Its output was used verbatim; nothing here is a judgement
call that could come out differently on a re-run.

**Rule count check:** 70 active + 2 inactive = 72. That is the expected
arithmetic for this repository (72 − 2 for the palette group). The two inactive
rules are exactly `ThemeColorDirectUse` and `UnwiredThemeColor`, and they sit
under a banner naming the switch that disabled them.

---

## 4. What is now checked by no rule

This section exists so a chosen gap does not become an invisible one.

### Raw colours are unchecked

`PALETTE_RULES_ACTIVE` is `false`, so **`ThemeColorDirectUse` and
`UnwiredThemeColor` do not run here.** Turning them off does not make raw-colour
use legal — it means no rule in the set can express it.

The gap is concrete: `app/.../ui/theme/Color.kt` holds **6 raw `Color(0xFF…)`
values as top-level `val`s** — `Purple80`, `PurpleGrey80`, `Pink80`, `Purple40`,
`PurpleGrey40`, `Pink40`. Nothing stops presentation code reading those names
directly instead of going through the theme, and nothing reports a palette entry
that the colour scheme never wires up.

`RawColorLiteral` — which **is** active — does not close this. It catches hex
literals written at the use site; it does not catch a named `val`.

**To close it:** move those colours into a declaration (`object PrismgreenColors`
or similar), then set `PALETTE_RULES_ACTIVE: true` in `detekt.yml` and replace
both `'none'` values with the real names. Re-run `./prism probe` first — it will
find the declaration and tell you what to write.

### Layer purity is not being exercised

There is no `.domain` package in this repository, so `domain is framework-free`
and `the domain package is transport-free` report **SKIPPED**, naming
`com.juandgaines.prismgreen.core.domain` as the prefix they looked for. That is
correct today and costs nothing — there is no domain layer to keep clean.

**It becomes a finding the moment you add one under a different prefix.** If your
shared domain lands somewhere other than `…core.domain`, re-render
`LayerPurityRulesTest` or edit the constant — otherwise the rule keeps reporting
SKIPPED over a domain layer it never examined, and a green build will say nothing
about it.

### 15 architecture assertions skipped, all legitimately

This repo has no ViewModels, no presentation/dto/domain packages, no
repositories, no Room entities and no `Screen` composables, so the rules about
them are *not applicable* rather than satisfied. Each skip carries its reason;
read them with:

```sh
grep -h -A2 '<skipped' tooling/konsist/build/test-results/test/*.xml
```

Every one currently says "this project has no X". **If you add an X and the skip
persists, that is a defect in the selector or the scope, not a pass.**

### Coverage is measured by nothing yet

The coverage gate observes (section 5). Until a coverage result is recorded, no
number is being held to the 80% floor.

### A dead key in the coverage policy

`.prism/verify/thresholds.json` ships **one override as a worked example**:
`":core:crypto": 100`. This repository has no such module, so it is a key nothing
reads. It was left in place deliberately as a shape to copy. Delete it or replace
it with a real module — both are supported, and neither breaks a test. `default: 80`
is the live value.

---

## 5. Posture — derived per engine, from measured counts

Measured **after** `ktlintFormat` had already corrected everything correctable.

| Engine | Found | Recordable? | Posture | Why |
|---|---|---|---|---|
| detekt | **6** | yes — `app/detekt.baseline.xml` | **`enforce`** | today's 6 are quiet; a new violation fails the build |
| ktlint | **2** | yes — `app/ktlint.baseline.xml` | **`enforce`** | 2 wildcard imports that ktlint cannot auto-correct |
| konsist | **0 failures** (23 tests, 15 skipped) | n/a — nothing to record | **`enforce`** | it found nothing, so nothing needed suppressing |
| coverage | **no measurement exists** | no | **`observe`** | enforcing now would deny *every* push while the build stayed green |

`.prism/scope.json` therefore names **only `coverage`**, and an engine that file
does not name enforces. That is why `posture` is recorded as `mixed` — three
engines block from this commit on, one observes.

The 6 baselined detekt findings, by rule:

```
2 JUnit4InJvmUnitTest
2 NonAssertKAssertion
1 ClassBodyMissingLeadingBlankLine
1 PreviewMustBePrivate
```

All six are in the generated `ExampleUnitTest` / `ExampleInstrumentedTest` /
`MainActivity` scaffolding. None is code anyone wrote on purpose.

**A baselined finding is not a switched-off rule.** The rule is live; this
instance is on a list to be fixed. Promoting to `enforce` does not clear the
list — only fixing the findings does.

### The pre-push floor

**Installed**, at `.git/hooks/pre-push`, executable, byte-identical to the
shipped hook. It was accepted deliberately when asked. It is the only gate that
runs with no agent attached, and the only place the coverage gate ever runs.

Recovery, if it ever blocks work you need to get out: `rm .git/hooks/pre-push`
leaves the repository unprotected but usable. `./prism promote --all` puts it back.

---

## 6. The canary — proof this install is not just install-shaped

Everything above can pass over a repository where the rules never run. This is
the check that cannot.

`fun prismCanary() { println("canary") }` was appended to `MainActivity.kt`,
then `./gradlew staticAnalysis`:

```
e: …/MainActivity.kt:48:21 Remove the 'println' call from shipped code. [NoConsoleLogging]
> Analysis failed with 1 issues.
BUILD FAILED
```

Both halves in one run:

- the rule set is **live** — it saw code written after the install
- it can still **block** — the build failed
- and it failed with **exactly 1 issue**, not 7, which proves the 6 baselined
  findings stayed suppressed while the new violation did not

The canary was then removed and the build confirmed green. Re-run this yourself
any time from `.prism/VERIFY.md`, Part 4.

> One wrinkle worth knowing, because it will bite again: `git checkout --` on
> that file reverted it to its **committed** state, which is *before*
> `ktlintFormat` ran. The formatting pass had to be re-applied. Until this
> install is committed, restoring a file from git undoes the formatting with it.

---

## 7. Where to go next

| Question | Answer |
|---|---|
| What blocks, where? | `./prism status` |
| What is suppressed? | `./prism baseline count`, `./prism baseline group` |
| What does rule X catch? | `.prism/RULES.md` |
| Is this still wired? | `./prism doctor`, then `.prism/VERIFY.md` |
| How do I clear the baseline? | `/prism-burndown` |
| How do I make more of it block? | `.prism/INSTALL-GREENFIELD.md` |

**The promote command that suits this repository is
`./prism promote --engine <name>`, not `./prism promote :app`.** With one module,
the module axis moves all four engines at once — **including coverage**, which
nothing has measured. Gate 3 would then refuse every push for a module that has
tests and production code and no recorded result, while the build stayed green.
Move the engine, not the module.

The only engine left to promote is `coverage`, and it needs a measurement first.
`.prism/INSTALL-GREENFIELD.md` has the sequence; `./prism status` prints the exact
commands, including the emulator step, for this repository.
