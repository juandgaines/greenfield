# Installing PRISM

PRISM is a **verification** framework for Kotlin and Android repositories: 72
detekt rules, 23 architecture tests, formatting, coverage, and the gates that run
them. It checks code. It has no opinion about how your project builds.

**Budget 10–15 minutes** of your agent's time. Yours is three questions.

---

## One command

```sh
./prism-verify
```

No subcommand. It checks that PRISM can install here **before writing anything**,
unpacks, asks which agent runs your gates, installs that agent's setup command,
and opens the install session in the terminal you are already in.

If `./prism-verify` does not run at all, you do not have `python3` — use
[the archive](#the-archive-if-you-have-no-python3) below.

| Options | |
|---|---|
| `./prism-verify claude-code` | name the agent instead of being asked: `claude-code`, `cursor`, `codex`, `copilot`, `antigravity` |
| `./prism-verify --no-launch` | install the setup command and print the invocation, without opening the session |
| `./prism-verify --version` | the build stamp — quote it in any bug report |

Off a terminal — a CI job, a `-p` session, anything with no human attached — it
**refuses rather than guessing** which agent should enforce your code, names the
ones it found, and exits 2. Name the harness to proceed.

**When the install finishes and the doctor passes, `./prism-verify` removes
itself.** From then on the repository has `./prism` and `.prism/`, which is what
the rest of this document is about.

> **Creating a new project rather than adding PRISM to an old one?** Read
> [Starting fresh](INSTALL-GREENFIELD.md) instead. A generated Android template
> is not a clean repository — it arrives with 17 findings — and that document is
> the short, bounded path to a project that blocks from its first commit.
>
> **Adding PRISM to a codebase that already has history?**
> [Installing into a codebase that already exists](INSTALL-BROWNFIELD.md).

---

## The three questions you will be asked

Everything else the agent determines from your code.

**1. Which agent runs the gates day to day?** Usually the one you are talking
to. It decides which hook registration lands.

| Answer | Gates fire during the turn? | What lands |
|---|---|---|
| `claude-code` | yes | `.claude/settings.json`, merged into yours |
| `cursor` | yes | `.cursor/hooks.json` |
| `codex` | yes — **once you trust the hooks** | `.codex/hooks.json` |
| `copilot` | **no** — ships inert | `.github/hooks/prism.json` |
| `antigravity` | **no** — ships inert | `.agents/hooks.json` |

`.git/hooks/pre-push` runs whatever you answer. For Copilot CLI and Antigravity
it is not a fallback — it is the **entire** enforcement, because their hooks
cannot be delivered from inside a repository. Their registration file is still
written, marked inert, so the wiring is correct if that ever changes.

Codex re-asks for trust on every install **and every upgrade**: its grant is
keyed to the hook file's contents, so writing a new one invalidates it.

**2. Let the install measure what blocks, or override it?** The default is to
measure, and it is almost always the right answer — see below.

**3. Do you want the `pre-push` floor?** — asked only when at least one engine is
left observing, on one of the first three agents. `.git/hooks/pre-push` is the
only gate that runs with no agent at all. Declining it will **not** quiet your
sessions: your agent's turn-end gate still runs every check. What changes is that
a push you type yourself is not refused, and the coverage gate — which runs only
at push — does not run at all.

### What the install decides, and why it is per engine

A repository that did not grow up under 72 rules has findings. The install does
**not** conclude from that that nothing should block. It asks, per engine,
whether that engine can **record** what it found:

| engine | can record? | installed as |
|---|---|---|
| detekt | yes — one `detekt.baseline.xml` per module | **`enforce`** |
| ktlint | yes — one `ktlint.baseline.xml` per module | **`enforce`** |
| konsist | **no** — a JUnit suite has no baseline | `observe`, if it found anything |
| coverage | **no measurement exists yet** | `observe` |

So on a repository with 330 pre-existing findings, **detekt and ktlint block from
the install commit.** Today's findings are recorded in files that go into git and
stop failing the build; a **new** violation of any of those same rules still
fails it. The baseline is a list of what you already had, not a switch.

Coverage observes because nothing has measured it — not because the floor is
unmet, but because there is no number. Enforcing it in that state would refuse
**every** push while your build stayed green, so the install will not do that to
you. `./prism coverage` takes the measurement.

`.prism/prism.json` records this as `posture: mixed`, which is the ordinary
result and not a half-finished one.

**Almost nothing is switched off at install.** All 72 rules land active in every
repository. The only exception is five design-system rules that need the *name* of
a declaration in your code — your colour palette, your theme composable. If your
repository has no such declaration there is no name to give them, so they are
switched off as a group, and the install works that out by reading your Kotlin
rather than by asking anyone to judge. [Your design
system* chapter in the documentation folder is the whole story;
`.prism/INSTALL-RECORD.md` records what happened in yours.

> **A rule switched off never comes back. A baselined finding should.**

---

## What lands in your repository

```
.prism/              ours. Rarely edited. Deleting it uninstalls PRISM.
tooling/             YOURS — 72 detekt rules and the architecture tests, as
                     source, so you can edit, delete and add to them
detekt.yml           YOURS — turn any rule off here
detekt.baseline.xml  YOURS — one per module: findings that predate the install.
                     Shrinks as you fix them; delete it when empty
ktlint.baseline.xml  YOURS — one per module, same idea
.editorconfig        root, so your IDE formats to the same rules the gate checks
build-logic/         four convention plugins merged into yours
.prism/scope.json    what BLOCKS, per module and per engine. Only names the
                     engines that do NOT block; absent entirely = all enforce
.git/hooks/          the pre-push floor, unless you declined it
```

PRISM adds **four** convention plugins — `prism.ktlint`, `prism.detekt`,
`prism.jacoco`, `prism.static-analysis`. It has no opinion about your
`compileSdk`, your `minSdk`, your dependencies or your own convention plugins.

## When the install finishes

It ends by running `prism-doctor`, which verifies the install rather than
performing it — including planting a deliberate violation of a PRISM rule and
confirming it was actually reported. An install that merely *looks* wired is the
one failure this framework cannot afford, so the check is mechanical and the
agent does not get to grade its own work.

Re-run it any time, and let it repair what it can:

```sh
./prism doctor
```

The doctor answers "is PRISM wired?". The wider procedure — did the rules that
landed match this repository, does what blocks behave the way it claims, and does
a **new** violation still fail the build — is installed alongside it:

```sh
cat .prism/VERIFY.md
```

## Where you stand, and what is left

```sh
./prism status
```

One screen: what blocks per module and per engine, what is suppressed and for how
long, and what the coverage gate would say right now.

Working the remaining suppressions down:

```sh
./prism baseline count    # how many are left
./prism baseline group    # by rule, worst first
/prism-burndown                                # the guided way through it
```

Never run `baseline.py collect --force`. Regenerating absorbs every violation
written since the install, permanently, and nothing reports that it happened.

When an engine has nothing left to suppress, promote it:

```sh
./prism promote --engine konsist     # one engine, every module
./prism promote :core:domain         # one module, every engine
./prism promote --all                # everything, plus the pre-push floor
```

`--all` removes `.prism/scope.json`, and its absence *is* every engine enforcing
everywhere. That is the finished state.

Which of the first two you use depends on the shape of the repository. A new
project is one module and moves engine by engine — [Starting
fresh](INSTALL-GREENFIELD.md). A repository with several moves module by module
— [Installing into a codebase that already
exists](INSTALL-BROWNFIELD.md). Both documents walk their axis end to end.

## Every command

`./prism` is the whole interface after the install. This is all of it:

```
./prism status [:module …]     what blocks, what is suppressed, and what the
                               coverage gate would say right now
./prism baseline               record what the engines find today, so it stops
                               blocking and a NEW violation of the same rule
                               still does
./prism coverage [:module …]   measure coverage and record it against the
                               source it measured
./prism promote :module        make one module block on every engine
./prism promote --engine <e>   make one engine block in every module
./prism promote --all          make everything block, and install the push floor
./prism doctor                 check this install, and repair what it can
./prism probe                  re-read your palette and theme names
```

`./prism` on its own is `./prism status`. There is no verb that makes anything
block **less** — stepping back is an edit to `.prism/scope.json`, deliberately,
so it leaves a diff somebody can see.

## Requirements

Kotlin 2.4+, Gradle with `settings.gradle.kts`, `python3`, `git`, a POSIX shell.
detekt is pinned at 2.0.0-alpha.6 on the `dev.detekt` coordinates — the rule set
compiles against that exact API, so the pin ships with the framework rather than
being looked up.

---

## The archive, if you have no `python3`

`prism-verify-0.6.2.zip` is the same payload without the Python entry point. The
steps are the ones `setup` collapses, in the same order:

```sh
unzip prism-verify-0.6.2.zip                      # produces prism-setup/
sh prism-setup/core/verify/prism-doctor --preflight
sh prism-setup/setup/bootstrap.sh                 # installs your agent's command
```

`bootstrap.sh` lists the agent CLIs on your machine and installs the setup command
for the one it finds. If several are installed it asks; off a terminal it refuses
and names them. Then invoke it — the command it prints is the one to use, because
it is **not the same on every agent**: `/prism-setup` on Claude Code, Cursor and
Antigravity, `$prism-setup` on Codex, and on Copilot a sentence naming the skill.

The wrapper only points at a document. If your agent has no such command, point
at it yourself:

> Read `prism-setup/setup/SETUP.md` and follow it.

That works on every agent, with no file installed at all.

The archive path runs preflight **second**, so a repository that fails it has
already had `prism-setup/` written into it. `./prism-verify` runs preflight
first, and writes nothing at all to a repository it refuses.

On this path nothing removes the installer when you are done. Delete
`prism-setup/` and the `.zip` yourself once `./prism doctor`
passes.
