# Tests for prism-doctor's harness check — D8. Sourced by run-tests.sh.
#
# The harness-adapter spec puts one sentence on the production decision path:
# REGISTRATION IS NOT ENFORCEMENT. Four ways a harness ends up configured
# correctly and gating nothing were observed while probing, and in every one the
# failure mode is silence rather than an error.
#
# So the check that reads those registrations has to be shown a broken tree, not
# only a good one. Every case below sabotages exactly one thing and requires the
# doctor to fail and to name it. A verifier that has only ever been run against
# a correct install is an assertion, not a check.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

DH_DOCTOR="$HOOK_DIR/prism-doctor"
DH_ADAPTERS=$(CDPATH= cd -- "$HOOK_DIR/../adapters" && pwd -P)

# A repository just complete enough to reach D8. The gates before it fail here
# and that is fine — the assertions below read the harness lines only, so this
# stays fast and does not need a Gradle build.
dh_repo() {
    repo=$(prism_fixture_repo)
    mkdir -p "$repo/.prism/adapters" "$repo/.prism/verify"
    cp -R "$DH_ADAPTERS/lib" "$repo/.prism/adapters/lib"
    cp "$DH_ADAPTERS/harnesses.json" "$repo/.prism/adapters/harnesses.json"
    printf '%s' "$repo"
}

dh_set_harness() {
    python3 -c '
import json, sys
path, name = sys.argv[1], sys.argv[2]
json.dump({"baseBranch": "main", "harness": name}, open(path, "w"), indent=2)
' "$1/.prism/prism.json" "$2"
}

dh_run() {
    ( cd "$1" && PRISM_ROOT="$1" sh "$DH_DOCTOR" 2>&1 )
}

# --- an install with no adapter is supported, not a failure ---------------
dh1=$(dh_repo)
dh_set_harness "$dh1" "none"
assert_contains "$(dh_run "$dh1")" "no harness adapter selected" \
    "an agentless install reports the floor as the gate, not a missing adapter"

# --- a harness that is not one of the five is named, not ignored ----------
dh2=$(dh_repo)
dh_set_harness "$dh2" "emacs"
dh2_out=$(dh_run "$dh2")
assert_contains "$dh2_out" "not a supported harness" \
    "an unrecognised harness fails rather than silently checking nothing"
assert_contains "$dh2_out" "claude-code" \
    "and the failure names what IS supported"

# --- selected but never installed ----------------------------------------
dh3=$(dh_repo)
dh_set_harness "$dh3" "claude-code"
assert_contains "$(dh_run "$dh3")" "no .claude/settings.json" \
    "a selected harness with no registration is reported as missing"

# --- registered, but pointing at a dispatcher that is not there -----------
dh4=$(dh_repo)
dh_set_harness "$dh4" "cursor"
mkdir -p "$dh4/.cursor"
cp "$DH_ADAPTERS/cursor/hooks.json" "$dh4/.cursor/hooks.json"
rm -f "$dh4/.prism/adapters/lib/prism-hook"
assert_contains "$(dh_run "$dh4")" "dispatcher that is not installed" \
    "hooks registered against a missing dispatcher fail, and say which file"

# --- THE ONE-WORD BYPASS -------------------------------------------------
#
# Measured on Cursor: an identical crashing hook allows the command without
# failClosed and blocks it with the key set. A registration that is valid JSON,
# names the right command, and omits this key is a gate-shaped thing.
dh5=$(dh_repo)
dh_set_harness "$dh5" "cursor"
mkdir -p "$dh5/.cursor"
python3 -c '
import json, sys
doc = json.load(open(sys.argv[1]))
for entries in doc["hooks"].values():
    for entry in entries:
        entry.pop("failClosed", None)
json.dump(doc, open(sys.argv[2], "w"), indent=2)
' "$DH_ADAPTERS/cursor/hooks.json" "$dh5/.cursor/hooks.json"
dh5_out=$(dh_run "$dh5")
assert_contains "$dh5_out" "failClosed" \
    "stripping failClosed fails, and the message names the key"
assert_contains "$dh5_out" "ALLOWS the action" \
    "and says what the harness does without it"

# --- the same file, correct, passes --------------------------------------
dh6=$(dh_repo)
dh_set_harness "$dh6" "cursor"
mkdir -p "$dh6/.cursor"
cp "$DH_ADAPTERS/cursor/hooks.json" "$dh6/.cursor/hooks.json"
assert_contains "$(dh_run "$dh6")" "cursor: hooks registered" \
    "the shipped cursor registration passes unmodified"

# --- a registration that calls something else entirely --------------------
dh7=$(dh_repo)
dh_set_harness "$dh7" "cursor"
mkdir -p "$dh7/.cursor"
printf '{"version":1,"hooks":{"stop":[{"command":"echo ok","failClosed":true}]}}' \
    > "$dh7/.cursor/hooks.json"
assert_contains "$(dh_run "$dh7")" "does not call prism-hook" \
    "a registration wired to something else is not PRISM's gates"

# --- tier 2 is reported as NOT ACTIVE, and it is not a failure ------------
#
# The spec is explicit: shipping the configuration inert is permitted so the
# wiring is in place when the mechanism starts working; reporting it as
# enforcing is not.
for dh_t2 in copilot antigravity; do
    dh8=$(dh_repo)
    dh_set_harness "$dh8" "$dh_t2"
    dh8_reg=$(python3 -c '
import json, sys
print(json.load(open(sys.argv[1]))["harnesses"][sys.argv[2]]["registration"])
' "$DH_ADAPTERS/harnesses.json" "$dh_t2")
    mkdir -p "$dh8/$(dirname "$dh8_reg")"
    cp "$DH_ADAPTERS/$dh_t2/hooks.json" "$dh8/$dh8_reg"
    dh8_out=$(dh_run "$dh8")
    assert_contains "$dh8_out" "NOT ACTIVE" \
        "$dh_t2 reports its in-turn gate as NOT ACTIVE"
    assert_contains "$dh8_out" "pre-push is the enforcement" \
        "and names the floor as the enforcement in effect for $dh_t2"
done

# --- malformed JSON is caught before anything else ------------------------
dh9=$(dh_repo)
dh_set_harness "$dh9" "codex"
mkdir -p "$dh9/.codex"
printf '{"hooks": [broken' > "$dh9/.codex/hooks.json"
assert_contains "$(dh_run "$dh9")" "not valid JSON" \
    "a malformed registration fails; the harness would ignore it silently"

rm -rf "$dh1" "$dh2" "$dh3" "$dh4" "$dh5" "$dh6" "$dh7" "$dh8" "$dh9"

# --- D9: the catalog, the most likely under-ship -------------------------
#
# Measured on a virgin fixture: omitting the merge fails at build-logic script
# compilation with "Unresolved reference 'ktlint'" — loud, but it names PRISM's
# own build file rather than the step that was skipped, so a consumer reads it
# as a broken payload. These assert the doctor turns that into a sentence.

dh_catalog_repo() {
    repo=$(dh_repo)
    mkdir -p "$repo/.prism/templates" "$repo/gradle"
    cp "$HOOK_DIR/../templates/libs.versions.toml.fragment" \
        "$repo/.prism/templates/libs.versions.toml.fragment"
    printf '%s' "$repo"
}

# every alias present -> pass
dh10=$(dh_catalog_repo)
dh_set_harness "$dh10" "none"
cp "$HOOK_DIR/../templates/libs.versions.toml.fragment" \
    "$dh10/gradle/libs.versions.toml"
assert_contains "$(dh_run "$dh10")" "carries every alias the payload references" \
    "a catalog containing the whole fragment passes"

# one alias removed -> fail, and the message names THAT alias
dh11=$(dh_catalog_repo)
dh_set_harness "$dh11" "none"
grep -v '^ktlint-cli' "$HOOK_DIR/../templates/libs.versions.toml.fragment" \
    > "$dh11/gradle/libs.versions.toml"
dh11_out=$(dh_run "$dh11")
assert_contains "$dh11_out" "missing entries the payload needs" \
    "a catalog missing one alias fails"
assert_contains "$dh11_out" "ktlint-cli" \
    "and the failure names the alias, not just the file"

# --- the same alias under the consumer's OWN spelling -> pass ------------
#
# MEASURED ON A 26-MODULE INSTALL, and it was reported as the opposite. A catalog
# alias becomes a Kotlin accessor by splitting on - _ . and camel-casing, so
# `detekt-gradle-plugin` and `detekt-gradlePlugin` are two strings and ONE
# accessor. Comparing literally, the doctor called ours missing and recommended
# adding it -- and adding it is what breaks the repository: Gradle refuses to
# generate accessors for a catalog with a name clash, so no project in the build
# configures and the error names neither PRISM nor the merge.
#
# So the comparison is on the accessor. The consumer's spelling is theirs.
dh11b=$(dh_catalog_repo)
dh_set_harness "$dh11b" "none"
sed 's/^detekt-gradle-plugin/detekt-gradlePlugin/' \
    "$HOOK_DIR/../templates/libs.versions.toml.fragment" \
    > "$dh11b/gradle/libs.versions.toml"
dh11b_out=$(dh_run "$dh11b")
assert_contains "$dh11b_out" "carries every alias the payload references" \
    "the consumer's own spelling of an alias satisfies the check"
assert_not_contains "$dh11b_out" "detekt-gradle-plugin," \
    "and the doctor does not ask them to add a second one that would clash"

# --- and the two that actually clashed are not in the fragment at all ----
#
# Every project derived from Now-in-Android declares `android-gradlePlugin` and
# `kotlin-gradlePlugin`. PRISM's build-logic used to ask for the hyphenated
# spelling of both; it now names those two coordinates directly, so the clash
# cannot arise from the fragment even before the comparison above sees it.
dh_fragment="$HOOK_DIR/../templates/libs.versions.toml.fragment"
assert_eq "0" "$(grep -c '^android-gradle-plugin' "$dh_fragment")" \
    "the fragment does not ship an android-gradle-plugin alias"
assert_eq "0" "$(grep -c '^kotlin-gradle-plugin' "$dh_fragment")" \
    "nor a kotlin-gradle-plugin one"
assert_eq "1" "$(grep -c '^agp' "$dh_fragment")" \
    "but it still ships the agp VERSION, which build-logic reads to name them"

# no catalog at all -> undetermined, never a pass
dh12=$(dh_catalog_repo)
dh_set_harness "$dh12" "none"
assert_contains "$(dh_run "$dh12")" "no gradle/libs.versions.toml" \
    "a repository with no catalog is reported, not passed over"

rm -rf "$dh10" "$dh11" "$dh12"

# --- the wrapper paths in the manifest name files that exist --------------
#
# bootstrap.sh copies from these, so a row pointing at a file the payload does
# not carry is a broken first step for that harness -- and it would be found by
# the consumer, not here.
#
# `setup/` is STAGING ONLY: SETUP.md step 11 deletes prism-setup/ once the
# install is done, so an installed tree has no setup directory and nothing to
# check. Skipping loudly there rather than failing -- the first version of this
# test resolved the path relative to the engine and reported every wrapper as
# missing when the suite ran inside an install, which is the same mistake
# test_base_branch.sh made with the template registry.
dh_setup_dir=""
for dh_candidate in "$HOOK_DIR/../../setup" "$HOOK_DIR/../setup"; do
    if [ -d "$dh_candidate" ]; then
        dh_setup_dir=$(CDPATH= cd -- "$dh_candidate" && pwd -P)
        break
    fi
done

if [ -z "$dh_setup_dir" ]; then
    printf 'SKIP harness wrapper paths — no setup/ directory beside the engine.\n'
    printf '     This is an installed tree, where staging has been removed.\n'
else
    dh_boot_missing=$(python3 -c '
import json, os, sys
adapters, setup_dir = sys.argv[1], sys.argv[2]
rows = json.load(open(os.path.join(adapters, "harnesses.json")))["harnesses"]
gone = []
for name, row in rows.items():
    if not os.path.isfile(os.path.join(setup_dir, row["wrapper_source"])):
        gone.append(name + ":" + row["wrapper_source"])
    if not row.get("cli"):
        gone.append(name + ":no cli")
    if not str(row.get("wrapper_dest", "")).strip():
        gone.append(name + ":no wrapper_dest")
    # The burn-down workflow ships to every harness, so every row needs a
    # destination for it. A row without one silently installs the workflow
    # nowhere, on the harness whose users most need it.
    if not str(row.get("command_dest", "")).strip():
        gone.append(name + ":no command_dest")
    # A skill is a DIRECTORY named for the skill holding a file that must be
    # called SKILL.md, and the frontmatter name has to match that directory or
    # the harness lists it under the wrong name. Only the non-empty check
    # existed here, which is how codex kept shipping a bare prompt with no
    # frontmatter at all after its CLI had learned to read skills.
    # Two failures measured on real installs, both from the wrapper text:
    #   * codex resolved `prism-setup/setup/SETUP.md` against the SKILL PACKAGE
    #     and reported "its required setup/SETUP.md is missing" -- a skill
    #     resolves paths relative to itself, a prompt did not;
    #   * codex asked the posture question in a -p session, got no answer, and
    #     stopped having written nothing. Asking is right; blocking is not.
    body = open(os.path.join(setup_dir, row["wrapper_source"])).read()
    if "SETUP.md" in body and "REPOSITORY ROOT" not in body.upper():
        gone.append(name + ":wrapper does not say the path is repo-root relative")
    if "enforce" not in body:
        gone.append(name + ":wrapper does not name the unattended posture default")
    for key in ("wrapper_dest", "command_dest"):
        dest = str(row.get(key, ""))
        if not dest.endswith("/SKILL.md"):
            continue
        folder = dest.split("/")[-2]
        if key == "wrapper_dest":
            head = open(os.path.join(setup_dir, row["wrapper_source"])).read(400)
            if not head.startswith("---"):
                gone.append(name + ":" + key + " is a skill with no frontmatter")
            elif ("name: " + folder) not in head:
                gone.append(name + ":" + key + " name does not match " + folder)
print(",".join(gone))
' "$DH_ADAPTERS" "$dh_setup_dir")
    assert_eq "" "$dh_boot_missing" \
        "every harness row names a wrapper file that ships, a CLI, and two destinations"
fi

# --- preflight must not refuse a configuration the framework supports -------
#
# Two checks used to grade themselves UNDETERMINED at preflight, which forces
# the whole run to exit 2 -- and SETUP.md step 1 tells the installing agent that
# anything but exit 0 means STOP. So preflight refused to install on:
#
#   antigravity, copilot   tier 2 BY DESIGN, while the check's own text said
#                          "This is a supported install, not a failure"
#   codex                  a trust grant its own text says to make AFTER setup
#
# All three are supported. None was a reason to refuse writing a file. This is
# the same rule D6 already follows for a declined pre-push floor: a chosen,
# documented state reports as chosen, because a doctor that fails a supported
# configuration is a doctor people learn to ignore.
#
# Missed for a whole test round because every prior probe ran BARE preflight;
# the harness block only executes when --harness is passed.
#
# RUN IN A FIXTURE, NOT IN THE CURRENT DIRECTORY. This loop used to invoke
# prism-doctor wherever the suite happened to be running. In a checkout that is
# a git repository and preflight proceeds; inside the EXTRACTED ARCHIVE it is
# not, preflight answers "not a git repository, and PRISM_ROOT is unset", and
# every harness comes back UNDETERMINED. The assertion then failed for a reason
# that had nothing to do with what it was testing -- and it failed only in the
# one place that matters, since build-zip.sh runs this suite from inside the
# archive it is about to ship. Every other check in this file already uses a
# fixture with PRISM_ROOT set; this one now does too.
dh_pf_repo=$(dh_repo)
dh_pf_refused=""
for dh_pf_h in $(python3 -c '
import json, sys
print(" ".join(json.load(open(sys.argv[1]))["harnesses"]))
' "$DH_ADAPTERS/harnesses.json"); do
    ( cd "$dh_pf_repo" && PRISM_ROOT="$dh_pf_repo" \
        sh "$HOOK_DIR/prism-doctor" --preflight --harness "$dh_pf_h" ) >/dev/null 2>&1
    dh_pf_rc=$?
    # 0 = pass, 1 = a real prerequisite failure (fine -- that is preflight's
    # job). 2 = undetermined, which stops the install and is what this forbids.
    [ "$dh_pf_rc" -eq 2 ] && dh_pf_refused="$dh_pf_refused $dh_pf_h"
done
assert_eq "" "$dh_pf_refused" \
    "preflight refuses no supported harness with UNDETERMINED"
