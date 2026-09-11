# A second install run must not undo the first install's work.
#
# test_place.py proves the three outcomes of the placement mechanism one call at
# a time. This suite proves the SEQUENCE, because the loss was never a single
# call: it was step 5a and step 8b, then weeks of promotion, then step 5a and
# step 8b again. Every individual copy was correct. The composite destroyed the
# only file that decides what blocks.
#
# WHAT WAS LOST, concretely. `.prism/scope.json` is the record of which modules
# have been promoted, so a re-run that restored the all-`observe` template
# demoted every module at once -- silently, because no gate reports a posture and
# nothing in the install's own output mentioned the file. `./prism status` was
# the only place it ever showed, and only if somebody happened to run it.
#
# So the assertions below walk the real install commands from SETUP.md, in the
# real order, with a real promotion in the middle.
#
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

RS_TEMPLATES="$HOOK_DIR/../templates"
RS_PLACE="$HOOK_DIR/lib/place.py"
RS_PRISM="$HOOK_DIR/../prism"

# An installed repository, the shape the install leaves behind: the fixture plus
# the two things a real install places that the fixtures do not.
rs_repo() {
    repo=$(prism_fixture_repo)
    cp "$RS_PRISM" "$repo/prism"
    chmod +x "$repo/prism"
    mkdir -p "$repo/.prism/adapters/git"
    cp "$HOOK_DIR/../adapters/git/install.sh" \
       "$HOOK_DIR/../adapters/git/pre-push" "$repo/.prism/adapters/git/"
    python3 -c '
import json, sys
json.dump({"baseBranch": "main", "harness": "none", "posture": "observe"},
          open(sys.argv[1], "w"), indent=2)
' "$repo/.prism/prism.json"
    printf '%s' "$repo"
}

# Step 5a and step 8b as SETUP.md gives them, so a change to either command in
# the document that is not made here shows up as a test that stops testing the
# install.
rs_step_5a() { ( cd "$1" && python3 "$RS_PLACE" lines \
    --src "$RS_TEMPLATES/gitignore.fragment" --dest .gitignore 2>&1 ); }
rs_step_8b() { ( cd "$1" && python3 "$RS_PLACE" file \
    --src "$RS_TEMPLATES/scope.json" --dest .prism/scope.json 2>&1 ); }

rs_promoted() {
    # prints the engines $2 enforces in $1's scope file, sorted, or "none"
    python3 -c '
import json, sys
try:
    data = json.load(open(sys.argv[1]))
except Exception as error:
    print("unreadable: %s" % error); raise SystemExit(0)
entry = data.get("modules", {}).get(sys.argv[2])
if not entry:
    print("none"); raise SystemExit(0)
print(" ".join(sorted(k for k, v in entry.items() if v == "enforce")))
' "$1/.prism/scope.json" "$2"
}

# --- the first run ---------------------------------------------------------

rs1=$(rs_repo)
printf '/local.properties\n' > "$rs1/.gitignore"

assert_contains "$(rs_step_5a "$rs1")" "APPENDED" \
    "the first run appends the gitignore fragment"
assert_contains "$(rs_step_8b "$rs1")" "PLACED" \
    "and places scope.json, which is what observe means"

# --- then somebody uses the repository -------------------------------------
#
# ./prism promote needs no Gradle to do the part that persists: it retires the
# module's baselines and flips its four engines to enforce. The Gradle run it
# prints afterwards is the PROOF, not the mechanism, which is why the loss could
# happen to a promotion that had already been verified.

rs1_promote=$( cd "$rs1" && sh ./prism promote :core:domain 2>&1 ); rs1_code=$?
assert_eq "0" "$rs1_code" "a module is promoted"
assert_contains "$rs1_promote" ":core:domain now enforces every engine" \
    "and says so"
assert_eq "coverage detekt konsist ktlint" \
    "$(rs_promoted "$rs1" ":core:domain")" \
    "all four engines are recorded as enforcing for that module"

# A consumer edit to the other file the install touches, so the re-run is
# judged on both of them.
printf '/my-scratch-dir/\n' >> "$rs1/.gitignore"
rs1_ignore_before=$(cat "$rs1/.gitignore")

# --- the second run --------------------------------------------------------
#
# THE REGRESSION. Same commands, same repository, nothing about the tree telling
# the installer that anything happened in between.

rs1_5a_again=$(rs_step_5a "$rs1")
assert_contains "$rs1_5a_again" "UNCHANGED" \
    "the second run appends nothing to .gitignore"
assert_eq "$rs1_ignore_before" "$(cat "$rs1/.gitignore")" \
    "and leaves it byte-identical, consumer line included"
assert_eq "1" "$(grep -c '^\*\*/build/$' "$rs1/.gitignore")" \
    "the fragment appears once, not twice"
assert_eq "absent" \
    "$([ -e "$rs1/.gitignore.prism-new" ] && echo present || echo absent)" \
    "and no .prism-new is written for a file that needed no change"

rs1_8b_again=$(rs_step_8b "$rs1")
assert_contains "$rs1_8b_again" "KEPT" \
    "the second run reports scope.json as the consumer's"
assert_eq "coverage detekt konsist ktlint" \
    "$(rs_promoted "$rs1" ":core:domain")" \
    "THE PROMOTION SURVIVES the second run"
assert_eq "present" \
    "$([ -f "$rs1/.prism/scope.json.prism-new" ] && echo present || echo absent)" \
    "and the template we would have written is beside it, named"
assert_eq "$(cat "$RS_TEMPLATES/scope.json")" \
    "$(cat "$rs1/.prism/scope.json.prism-new")" \
    "and .prism-new is exactly what we ship, so a diff is worth reading"

# KEPT exits 0 on purpose: making a difference fatal pushes the agent toward the
# one recovery that loses the work -- delete theirs and run setup again.
rs_8b_status=$( cd "$rs1" && python3 "$RS_PLACE" file \
    --src "$RS_TEMPLATES/scope.json" --dest .prism/scope.json >/dev/null 2>&1 ); rs_8b_code=$?
assert_eq "0" "$rs_8b_code" "a kept file is not an install failure"

# --- and the one place it ever showed still shows it -----------------------
#
# The loss was invisible everywhere else. If ./prism status could not report the
# surviving promotion, this test would be proving the file's bytes rather than
# the property anybody cares about.
rs1_status=$( cd "$rs1" && sh ./prism status 2>&1 )
assert_contains "$rs1_status" ":core:domain" \
    "./prism status names the promoted module after the re-run"

# --- a third run, to be sure it is idempotence and not an off-by-one -------
rs1_third=$(rs_step_8b "$rs1")
assert_contains "$rs1_third" "KEPT" "a third run keeps it too"
assert_eq "coverage detekt konsist ktlint" \
    "$(rs_promoted "$rs1" ":core:domain")" \
    "and the promotion is still there"

prism_fixture_cleanup "$rs1"
