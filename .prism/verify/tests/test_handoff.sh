# Tests for handoff.py — the end of the install, and the only place PRISM
# deletes anything in a consumer's repository.
# Sourced by run-tests.sh.
#
# TWO PROPERTIES ARE PINNED HERE, and neither is about the happy path.
#
#   1. A FAILING DOCTOR REMOVES NOTHING. "Remove the installer once everything is
#      ready" has a load-bearing second half: a repository that did not verify
#      still needs its installer in order to try again.
#   2. IDENTITY, NOT NAME. Until 0.6.2 the payload was removed by an `rm -rf`
#      that an agent typed out of a document, and the artifact was never removed
#      at all. Deleting a path because of what it is called is how a piped
#      variant of one such line destroyed three konsist templates during 0.6.0's
#      phase 7. So a file called `prism-verify` that is not an installer archive
#      must SURVIVE, and say why.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

TH_PY="$HOOK_DIR/lib/handoff.py"

# A finished install, plus everything the installer leaves lying around.
th_repo() {
    repo=$(mktemp -d "${TMPDIR:-/tmp}/prism-handoff.XXXXXX")
    mkdir -p "$repo/.prism" "$repo/prism-setup/setup"
    printf '0.6.2\n' > "$repo/.prism/VERSION"
    printf '0.6.2\n' > "$repo/prism-setup/VERSION"
    printf '# Installing\n' > "$repo/prism-setup/setup/SETUP.md"
    printf '#!/bin/sh\n' > "$repo/prism"
    printf '{"default": {"konsist": "observe", "coverage": "observe"}, "modules": {}}\n' \
        > "$repo/.prism/scope.json"
    python3 -c '
import sys, zipfile, os
with zipfile.ZipFile(os.path.join(sys.argv[1], "prism-verify"), "w") as archive:
    archive.writestr("prism-setup/setup/SETUP.md", "# Installing")
' "$repo"
    printf '%s' "$repo"
}

th_run() {
    repo=$1
    shift
    TH_OUT=$( python3 "$TH_PY" --root "$repo" "$@" 2>&1 )
    TH_CODE=$?
}

# --- 1. a doctor that did not pass removes nothing -------------------------
th1=$(th_repo)
th_run "$th1"
assert_ne "0" "$TH_CODE" "without --doctor-passed it refuses"
assert_contains "$TH_OUT" "still needs its installer" "and says why"
assert_path_exists "$th1/prism-verify" "the artifact survives a failing verdict"
assert_path_exists "$th1/prism-setup/VERSION" "and so does the payload"

# --- 2. the happy path ------------------------------------------------------
th2=$(th_repo)
th_run "$th2" --doctor-passed
assert_eq "0" "$TH_CODE" "with the verdict it finishes"
assert_path_absent "$th2/prism-verify" "the installer removed itself"
assert_path_absent "$th2/prism-setup/VERSION" "and took the payload with it"
assert_path_exists "$th2/prism" "./prism is NEVER a candidate"
assert_path_exists "$th2/.prism/VERSION" "and neither is .prism/"

# The banner is the hand-off, so it has to carry the two nouns and the verbs.
assert_contains "$TH_OUT" "./prism " "the banner names the command"
assert_contains "$TH_OUT" ".prism/" "and where PRISM lives"
assert_contains "$TH_OUT" "promote --engine" "and every verb ./prism documents"
assert_contains "$TH_OUT" "konsist observe" "and the posture, read from scope.json"
assert_contains "$TH_OUT" "detekt enforce" "including the engines that do block"

# It LISTS the commands. It does not rank them: that is the 0.6.2 copy rule.
assert_not_contains "$TH_OUT" "recommend" "the banner recommends nothing"
assert_not_contains "$TH_OUT" "you want" "and tells nobody what they want"

# --- 3. identity, not name --------------------------------------------------
th3=$(th_repo)
printf 'not an archive\n' > "$th3/prism-verify"
th_run "$th3" --doctor-passed
assert_eq "0" "$TH_CODE" "a decoy does not fail the hand-off"
assert_path_exists "$th3/prism-verify" \
    "a file named prism-verify that is not an installer SURVIVES"
assert_contains "$TH_OUT" "not a PRISM installer archive" "and the report says why"

# A payload from a different build is not this install's payload.
th4=$(th_repo)
printf '0.5.0\n' > "$th4/prism-setup/VERSION"
th_run "$th4" --doctor-passed
assert_path_exists "$th4/prism-setup/VERSION" "a payload of another version survives"
assert_contains "$TH_OUT" "VERSION that is not 0.6.2" "and the report names the mismatch"

# A directory called prism-setup with no SETUP.md in it is somebody else's.
th5=$(th_repo)
rm -f "$th5/prism-setup/setup/SETUP.md"
th_run "$th5" --doctor-passed
assert_path_exists "$th5/prism-setup/VERSION" "no SETUP.md, no removal"

# --- 4. --dry-run writes nothing -------------------------------------------
th6=$(th_repo)
th_run "$th6" --doctor-passed --dry-run
assert_eq "0" "$TH_CODE" "dry run succeeds"
assert_path_exists "$th6/prism-verify" "and removes nothing"
assert_contains "$TH_OUT" "REMOVED" "while reporting what it would remove"

# --- 5. an unfinished install cannot be handed off --------------------------
th7=$(th_repo)
rm -f "$th7/.prism/VERSION"
th_run "$th7" --doctor-passed
assert_ne "0" "$TH_CODE" "no .prism/VERSION means no finished install"
assert_path_exists "$th7/prism-verify" "so nothing is removed"
