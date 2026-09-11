# Starting fresh: a new project that blocks from day one

Every other chapter assumes a repository with history. This one is for the
opposite case — a project you are creating now — and it exists because that case
was the one PRISM got wrong for longest.

The expectation is reasonable and almost right: *a new project has nothing to
suppress, so it should enforce everything.* What makes it only *almost*
right is the word "nothing".

---

## Which kind of new project you have

There are two ways to make one, and **they are not the same project**. Which you
used changes the numbers below, which engines block on day one, and whether one
whole section of this document applies to you at all.

| | `android create` (the CLI) | Android Studio's New Project |
|---|---|---|
| what it generates | `MainActivity`, `Navigation`, a `MainScreen` composable, a `MainScreenViewModel`, a `DataRepository` | `MainActivity` with a `Greeting` composable, and nothing else |
| a ViewModel? | **yes** | **no — none at all** |
| detekt findings | **15** | **6** |
| ktlint findings | **1** | **2**, both `no-wildcard-imports` |
| konsist | **1 failure** — so it starts `observe` | **0 failures** — so it starts `enforce` |
| observing after install | konsist **and** coverage | **coverage only** |

The worked example below is the CLI one, because it is the harder of the two:
everything the Studio shape needs is a subset. Where they diverge, it is called
out.

**Both** produce a `src/test/` and a `src/androidTest/`, which matters later —
see the coverage step.

### The CLI project, in detail

Measured against PRISM's rule set, `android create` output arrives with
**17 findings before you write a line**:

| | count |
|---|---|
| `ClassBodyMissingLeadingBlankLine` | 5 |
| `PreviewMustBePrivate` | 2 |
| `ModifierMissing` | 1 |
| `MatchingDeclarationName` | 1 |
| `OneShotFlowBuilder` | 1 |
| `UnusedParameter` | 1 |
| `KoinViewModelOnlyInRoot` | 1 |
| `NonAssertKAssertion` | 1 |
| ktlint, not auto-correctable | 1 |
| konsist — `Screen composables take no ViewModel()` | 1 |

### The Studio project, in detail

Fewer, because there is less code — no ViewModel, no navigation, no repository:

| | count |
|---|---|
| `JUnit4InJvmUnitTest` | 2 |
| `NonAssertKAssertion` | 2 |
| `ClassBodyMissingLeadingBlankLine` | 1 |
| `PreviewMustBePrivate` | 1 — on `GreetingPreview` |
| ktlint `no-wildcard-imports` | 2 |

Every one of them is in scaffolding Studio wrote, and **konsist passes
outright** — 15 of its 23 assertions have nothing to match on, so they are
skipped rather than failed. That is why a Studio project starts with **three**
engines enforcing rather than two.

Nothing is wrong with either template and nothing is wrong with the rules. They
are two different opinions about Kotlin meeting each other for the first time.

Two of those you cannot fix by editing the generated code:
`KoinViewModelOnlyInRoot` wants Koin and `NonAssertKAssertion` wants assertK, and
a bare template has neither. Until 0.6.1 that fact alone was enough to put a
brand-new repository into `observe` — nothing blocking, on a project with no
history to protect.

## What happens instead now

The install asks a better question, **per engine**: *can this engine record what
it found?*

| engine | can record? | your new project gets |
|---|---|---|
| detekt | yes — `detekt.baseline.xml` | **`enforce`** |
| ktlint | yes — `ktlint.baseline.xml` | **`enforce`** |
| konsist | no — it is a JUnit suite | `observe` |
| coverage | no measurement exists yet | `observe` |

So **detekt and ktlint block from your first commit.** The template's findings
are recorded and quiet; anything *you* write is held to the full rule set. The
two engines that cannot record what they found say so honestly instead of
pretending, and `.prism/scope.json` names exactly those two:

```json
{
  "default": { "konsist": "observe", "coverage": "observe" },
  "modules": {}
}
```

**An engine this file does not name enforces.** That is why the file is short
and why the shortest file is the strictest one.

So you never *add* a line to make something block — you **delete** the line that
stops it. Writing `"konsist": "enforce"` is a no-op, and it makes the file
longer without making it stricter.

---

## The whole sequence, start to finish

Six steps, in order:

1. create the project
2. raise Kotlin to 2.4
3. put it under git — **and know your branch name**
4. install
5. answer two questions
6. check the base branch landed right

Three and six are the ones people get wrong, and both are called out below.

### Create the project

Either way works, and the table at the top says how they differ:

```sh
android create --name="My App" empty-activity
cd MyApp
```

or **File → New Project → Empty Activity** in Android Studio, which is what most
people do. Studio also writes a `.gitignore`; `android create` does not, so on
the CLI path add one before your first commit or `git add -A` will commit
`local.properties`.

### Raise Kotlin to 2.4

Preflight requires it and the template pins 2.3.20. In
`gradle/libs.versions.toml`:

```toml
kotlin = "2.4.10"
```

Skip it and the install stops before writing anything, saying exactly this:

```
FAIL  Kotlin 2.3.20 is below 2.4
      detekt 2.0 requires Kotlin 2.4, and PRISM's 72 rules compile against it.
      This is a change to YOUR build, not something setup can do for you.
```

It will not edit your build for you, which is why this step is yours.

### Put it under git — and know your branch name

`android create` does not create a repository:

```sh
git init
git add -A
git commit -m "android create empty-activity"
```

**Now check what branch you are on**, because PRISM needs it and the default may
not be yours:

```sh
git branch --show-current
```

Git gives you `master` unless `init.defaultBranch` says otherwise —
`git config --global init.defaultBranch` tells you which you have. **PRISM's
`baseBranch` defaults to `main`.** If those two disagree, every gate resolves
"what changed?" against a branch that does not exist and **every gate denies**.

Two ways out, either is fine:

```sh
git branch -M main        # rename yours to match the default
```

or leave it and correct PRISM in step 6.

If you add a remote, that is what decides it from then on:

```sh
git remote add origin <url>
git push -u origin main
git symbolic-ref --short refs/remotes/origin/HEAD | sed 's|^origin/||'
```

That last command is the one to trust. `git remote show origin` answers
`(unknown)` on a local or freshly-created remote, and writing `(unknown)` into
the config is a real way people have broken this.

### Install

```sh
./prism-verify
```

One command: it checks the repository, unpacks, asks which agent runs your gates,
installs that agent's setup command, and opens the install session in the same
terminal. [Installing PRISM](INSTALL.md) lists everything it asks.

### Answer two questions

**Which agent runs your gates.** The install lists the agent CLIs it found on
this machine and asks which of them will run the gates. Naming the agent you are
talking to installs that agent's setup command; naming another installs that
one's.

**How what blocks is decided.** Three options. This is what each produces on a
project you generated a minute ago:

| Option | What it produces here |
|---|---|
| **Measure and decide per engine** — what the installer does if you press Enter | detekt and ktlint **enforce**, with the template's findings written to a baseline. konsist and coverage **observe**. `./gradlew staticAnalysis` is green, your first push is allowed, and a violation *you* write is refused. |
| **Force `enforce`** | No baseline is written. The build fails on the 17 findings the template arrived with. Coverage enforces with nothing measured, and gate 3 refuses a module that has tests, production code and no recorded result — so every push is denied until you measure. Fixing all 17 first is what makes this option behave. |
| **Force `observe`** | Nothing blocks. detekt and ktlint report new violations and allow them, in every module, alongside konsist and coverage. This was the only behaviour available before 0.6.1. |

The 17 findings are the fact all three turn on: a generated project is not a
clean project, so "nothing to suppress" — the intuition this chapter opens with —
is not true of it.

Either override is recorded in `.prism/INSTALL-RECORD.md` as *chosen* rather than
*derived*, with the counts, so whoever reads it later can see it was deliberate.

### Check the base branch landed right

This is the last step, and it is worth thirty seconds. Open `.prism/prism.json`:

```json
{ "baseBranch": "main", "harness": "claude-code", "posture": "mixed" }
```

`baseBranch` must be the branch your work merges **into** — the one from step 3.
Edit it if it is wrong; it is an ordinary file and nothing regenerates it.

Then let the doctor confirm the whole install, this key included:

```sh
./prism doctor
```

A wrong value is not silent — it is the one thing here that fails loudly:

```
FAIL  baseBranch 'main' does not resolve in this repository
      Every gate computes its scope against it, so every gate will deny.
      Set it to a branch that exists: git branch -a
```

A clean install ends `PASS — 12 checks`, and one of those checks plants a real
violation and confirms it was reported, so "it looks wired" and "it is wired" are
not the same verdict.

## Proving it, in one edit

The claim is "new code is blocked". Test it rather than trusting it — add a file
with something a rule catches:

```kotlin
package com.example.myapp

object Scratch {

    fun hello() {
        println("hello")
    }
}
```

```sh
./gradlew staticAnalysis
```

```
e: Scratch.kt:6:9 Remove the 'println' call from shipped code. [NoConsoleLogging]
BUILD FAILED
```

Delete the file and it is green again. Note what that proves: the template's five
baselined `ClassBodyMissingLeadingBlankLine` findings stayed quiet while the
*sixth* one, in your new file, failed the build. Same rule. **History suppressed,
new code blocked** — which is the whole point of a baseline, and the reason
`enforce` and "there is a baseline" are not opposites.

---

## Finishing the other two engines

You start with two of four blocking. Getting the rest is a short, bounded job.

**The one rule: achieve the coverage first, promote second.** Coverage is the
only engine that does not run in `./gradlew staticAnalysis` and does not run
locally at all — it runs at `git push`. Promote before the tests exist and you
get a green local build and a refused push, with nothing on screen connecting
the two.

**The axis you move here is the engine, not the module.** A new project is one
module, so `./prism promote :app` would turn on all four engines at once —
promoting the entire codebase in one step. One engine at a time is the shape
that fits:

```sh
./prism promote --engine konsist
```

**Commit before you promote.** Promotion drops baseline entries, and setting the
posture back does not restore them. Only git does.

### konsist — one refactor, and one decision it forces

> **Skip this whole section if you used Android Studio.** It generates no
> ViewModel and no `Screen` composable, so konsist has nothing to match on: it
> passes at install and is already `enforce`. Measured on a real Studio project:
> 0 failures, 15 of 23 assertions skipped. `./prism status` will show konsist
> already enforcing, and there is nothing here for you to do. Go to
> [coverage](#coverage--one-measurement).

The CLI-generated `MainScreen` takes the ViewModel directly. PRISM's MVI contract
wants a stateless `Screen` with a `Root` above it that owns the ViewModel, so
the screen can be previewed and tested on its own.

**Name the Root for the screen, not for the file.** The pair is `<Name>Root` and
`<Name>Screen`. The template's screen is already called `MainScreen`, so `Name`
is `Main` and the Root is **`MainRoot`**:

```kotlin
@Composable
fun MainRoot(viewModel: MainScreenViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MainScreen(state = state, onAction = viewModel::onAction)
}

@Composable
fun MainScreen(state: MainScreenUiState, onAction: (MainScreenAction) -> Unit) { … }
```

Calling it `MainScreenRoot` compiles and passes konsist, and then detekt asks for
a composable called `MainScreenScreen`:

```
MainScreen.kt:15:1 Declare the 'MainScreenScreen' composable in this file next to
'MainScreenRoot', so the Root/Screen pair stays together. [RootAndScreenInSameFile]
```

**And renaming to `…Root` activates a rule the bare template cannot satisfy.** A
Root composable's ViewModel parameter must default to `koinViewModel()` —
`RootComposableMustDefaultViewModel` accepts that call and no other, so the
template's `viewModel()` is refused:

```
MainScreen.kt:19:5 Give the 'MainScreenViewModel' parameter of 'MainRoot' the default
'koinViewModel()', so navigation can call the Root without arguments.
[RootComposableMustDefaultViewModel]
```

So this step is a refactor **plus a decision**, and both answers are honest:

- **adopt Koin**, and write `koinViewModel()` as above; or
- **switch that one rule off** in `detekt.yml`, with the reason in a comment:

```yaml
  # OFF because this project does not use Koin, and this rule accepts ONLY
  # `koinViewModel()` -- not `viewModel()`, which is what the template ships.
  # Turn it back on the day Koin is adopted.
  RootComposableMustDefaultViewModel:
    active: false
```

konsist itself has no baseline, so its finding has to be *fixed* rather than
recorded — there is nothing to record it in. When
`./gradlew :tooling:konsist:test` is green and `./gradlew staticAnalysis` is
green:

```sh
./prism promote --engine konsist
```

### coverage — one measurement

Coverage observes because nothing has measured it. It is not that the floor is
unmet; there is no number at all. Enforcing it in that state would deny **every**
push while your build stayed green, so the install refuses to do that to you.

Take the measurement, then promote:

```sh
./prism coverage
./prism promote --engine coverage
```

`./prism coverage` runs the suite, produces the report, records it against the
source it measured, and prints the number:

```
  :app                           combined    unit + instrumented, on emulator-5554
                                 14.3% of lines covered, floor 80 — reported, not blocking
```

**Expect to need an emulator, and know why.** Both generators ship a
`src/androidTest/` as well as a `src/test/`, and a module with both is measured
as *combined* — unit and instrumented together. An empty `androidTest`
directory is enough to make it so. That means a brand-new project needs a booted
device before it can produce any coverage number at all, which surprises people
who have not written an instrumented test yet.

`./prism coverage` uses an emulator that is already running. If none is, it says
which to start and stops — **it never boots a virtual machine on your machine.**

Two honest ways forward, and the second is often right on a new project:

```sh
android emulator start <avd>      # then run ./prism coverage again
```

or delete the source set you are not going to use:

```sh
rm -r app/src/androidTest
```

That makes the module unit-test-only, and it measures with no device at all. Do
it only if you mean it — adding instrumented tests later is fine, and coverage
simply goes back to needing a device when you do.

A floor below 80 is a legitimate starting ramp — set it in
`.prism/verify/thresholds.json` to a number you have **already measured**, and
raise it as you go. A floor you have not met denies every push.

### Then finish the repository

When all four engines enforce, `.prism/scope.json` has nothing left to say, and
one command says it:

```sh
./prism promote --all
```

That removes the file — **its absence *is* every engine enforcing everywhere** —
installs the pre-push floor if it was declined at install, records the posture,
and ends by running the doctor. The order is the floor, then the file, then the
record, so a promotion that fails halfway leaves the record honest rather than
claiming a repository enforces when nothing was installed.

There is deliberately no bare `./prism promote`. Promoting a whole repository is
a decision worth typing out.

## Working the baseline down

Promoting an engine does **not** clear its baseline. Whatever was suppressed
stays suppressed until somebody fixes it, and a suppressed finding is not a
solved one.

```sh
./prism baseline count    # how many are left
./prism baseline group    # which rules they are, worst first
```

Work by rule, not by file: one rule's findings usually share one cause, so
fixing them is one decision applied many times rather than many decisions. When
a rule reaches zero, its entries disappear on the next `./prism baseline` and
nothing is suppressing it any more.

`./prism status` reports how long each suppression has been there, so a state
meant to be temporary cannot become permanent by inattention.

## Proving the gate is live, per engine

A clean repository passing an enforced gate proves nothing on its own — it may
be passing because the gate is off. Plant a violation, watch it fail, remove it,
watch it pass, **once per engine you promoted**, because each enforces through a
different mechanism and a detekt canary tells you nothing about konsist.

```sh
# detekt
println("canary")

./gradlew staticAnalysis      # must FAIL, and must name the rule
# remove it
./gradlew staticAnalysis      # must pass again
```

On one module you cannot prove a promotion *differentially* — there is no second
module to leave observing — so the per-engine half is the whole proof, and
skipping it leaves you with nothing.

> **A green build can lie about this.** Gradle's configuration cache carries the
> posture, because `ignoreFailures` is set at configuration time. If a promote
> appears to have done nothing, re-run with `--no-configuration-cache` before
> concluding anything.

> **`--no-verify` does not get you past the push gate.** Under an agent harness
> the gate is a hook on the shell, so it adjudicates the whole command before
> anything runs and the flag never reaches git. A chained `add && commit &&
> push` is refused *in full*. Put the push in its own command.

Reverting a canary rewrites the file, which moves its timestamp — and the
coverage gate decides freshness by comparing your report against the code on
disk. `./prism coverage` stamps the report with the content it measured, so the
canary changes the file, the revert changes it back, and the recorded
fingerprint says the bytes are the same ones. It refuses to stamp a report that
does **not** match, so it cannot be used to make a stale number look current.

## When the project grows past one module

Everything above moves along the engine axis, which is what a one-module project
has. Once there are several, the other axis opens: `./prism promote :core:domain`
turns on all four engines for **that module only**, which lets each module carry
its own standard while the rest keeps working.

[Installing into a codebase that already exists](INSTALL-BROWNFIELD.md) walks
that axis in full — which module to take first, what `./prism promote` does in
what order, how to set a floor above the default, and how to step back. It
applies to a grown greenfield exactly as it does to inherited code.

---

## Two rules a bare template cannot satisfy

`KoinViewModelOnlyInRoot` and `NonAssertKAssertion` will sit in your baseline
until you either adopt Koin and assertK or turn the rules off. Both are honest
options and the choice is yours:

- **adopt them** — they are the stack PRISM's rules were extracted from, and
  assertK is already in the version catalog the install merges;
- **turn them off**, in `detekt.yml`, one rule at a time, with the reason in a
  comment. See [the three different meanings of
  "off" first — a rule switched off, a rule baselined, and a rule whose group
  is inactive are three different states — because
  a rule switched off never comes back and a baselined finding should.

What you should *not* do is leave them baselined and forget: `./prism status`
reports how long each suppression has been there precisely so that a temporary
state cannot become permanent by inattention.

---

**Next:** [Installing PRISM](INSTALL.md), or [Installing into a codebase that
already exists](INSTALL-BROWNFIELD.md) once this project has more than one module.
