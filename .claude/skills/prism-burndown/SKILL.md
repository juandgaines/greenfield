---
name: prism-burndown
description: Work the detekt baseline down to zero, one rule at a time, and switch PRISM from report-only to enforcing when it is clear. Use when the user asks about their PRISM baseline, remaining findings, burning down technical debt recorded at install, or turning enforcement on.
user-invocable: true
---

# Burn the baseline down

PRISM was installed in `observe` posture: the findings that already existed
were recorded in the modules' `detekt.baseline.xml` files so day one was not a red build. Every
rule is live — a **new** violation still fails — and what is left in that file
is the list of things this repository already had.

This is how the list gets shorter. **The whole workflow reads a file; it runs
no build until step 4.**

## 1. Read the state, run nothing

```sh
python3 .prism/verify/lib/baseline.py count
```

If it prints `suppressed=0`, or says there is no baseline, there is nothing to
burn down. Tell them so and stop, with one line:

> Nothing is suppressed. Turning enforcement on is step 5 below: it installs the
> pre-push floor and records that this repository enforces.

## 2. Group what is left, highest first

```sh
python3 .prism/verify/lib/baseline.py group
```

One line per rule, `<count> <RuleId>`, worst first. **Show the top five and
stop.** A wall of seventy rules is the raw tool output this workflow exists to
replace; one decision about one rule retires the most entries.

## 3. Take the top rule, and force one of three decisions

Do not fix anything yet. For the rule at the top, look at what it actually
caught:

```sh
python3 .prism/verify/lib/baseline.py list --rule <RuleId>
```

Read two or three of those files, then put **exactly one** of these to the
human. Say which you think it is and why; do not present all three neutrally.

**a. The code is wrong.** The rule is right, this repository should change.
Fix the files. This is the common case for the regression guards —
`NoConsoleLogging`, `NoNotNullAssertion`, `NonAtomicStateFlowAssignment`.

**b. The exclusion is pointed at the wrong path.** *Offer this FIRST for the
six module-path rules* — `KtorCallMustUseSafeCall`,
`HttpClientConstructionOutsideFactory`, `EmptyResultOverResultUnit`,
`WorkerResultTypealiasRequired`, `StartKoinOnlyInAppModule`,
`NoCrossFeatureRouteImport`. Each exempts the one file that legitimately
implements its pattern. If this repository puts that file somewhere else, the
exclusion matches nothing and the rule is reporting the very file it was
written to exempt. **Edit the glob in `detekt.yml`.** That keeps the check and
costs one line.

**c. The rule does not apply to this repository.** This is the answer that
used to be "switch the group off", and that option no longer exists: 67 of the
72 rules have no switch, because six switches decided by judgment produced four
different rule sets across ten installs of one repository. Only
`PALETTE_RULES_ACTIVE` (2 rules) and `THEME_RULES_ACTIVE` (3) remain, they
guard a **name**, and `python3 .prism/verify/lib/probe.py` decides them — not
you, and not the human.

So for everything else, (c) splits into two answers that are easy to confuse:

**c1. It does not apply, and it never will.** Set `active: false` on that one
rule in `detekt.yml`, and **write down why, in the same commit**. It is the
only way to stop a rule *checking*, and a lone `active: false` at line 300 is
illegible six months later. Reserve it for a rule that is about a technology the repository
does not use at all.

**c2. It applies, but not today.** Far more common, and the honest answer for
"seven test-stack rules just reported every test file". Leave the rule on and
stop it *blocking*: set that engine to `observe` for the module in
`.prism/scope.json`, or leave the findings in the baseline. Both keep the
finding visible and both still fail a **new** violation.

That last clause is the whole distinction, and it is worth saying out loud
while the human chooses:

> **A rule switched off never comes back. A baselined finding does.**

## 4. Retire the entries, and let the build check you

Only ever subtract:

```sh
python3 .prism/verify/lib/baseline.py drop --rule <RuleId>
./gradlew staticAnalysis
```

Green means those findings are genuinely gone. Red means they are not, and the
output names what is left — put the entries back only by fixing the code, never
by regenerating.

**Never run `baseline.py collect --force`.** Regenerating absorbs every
violation written since the install, permanently, and nothing reports that it
happened. `collect` refuses over an existing baseline for that reason; `--force`
exists to discard a record deliberately, not to refresh one.

## 5. Repeat, then turn enforcement on

Back to step 2. When `count` reaches zero:

```sh
sh .prism/adapters/git/install.sh
```

That installs `.git/hooks/pre-push` — the only gate that runs with no agent at
all. Then record the decision, because the hook does not: set `"posture"` to
`"enforce"` in `.prism/prism.json`. No gate reads that key; `prism-doctor` does,
and it is what lets the doctor report the difference between what was chosen and
what is true.

```sh
sh .prism/verify/prism-doctor
```

gives the verdict, as it always does; you do not.

**If this repository was installed from the `prism-verify` CLI** rather than the
plain archive, `./prism promote --all` does the floor and the posture record in
one, and `./prism doctor` is the same doctor. Those two verbs exist only in
that form — in an unpacked install the three commands above are the whole of it.

## What this workflow does not do

It does not decide whether a rule is right for this repository. That is a
judgement about someone's codebase and it belongs to the person who owns it —
your job is to put the choice clearly, with the files in front of them, one
rule at a time, in the order that clears the most work.
