# Tests for the two remaining rule-group switches in detekt.yml.in, and for the
# twenty-seven rules that no longer have one. Sourced by run-tests.sh.
#
# THERE USED TO BE SIX SWITCHES OVER THIRTY-TWO RULES. Four are deleted, and
# the reason is a measurement: ten installs of one unchanged repository
# produced four different rule sets, all passing prism-doctor, because the
# switches were decided by an agent reading prose and the same harness reached
# opposite answers on identical code forty minutes apart. MVI, MODULE_PATH,
# NAV and TEST_STACK are now unconditionally active. What is CHECKED is the
# same in every repository; what BLOCKS is moved through .prism/scope.json.
#
# TWO SURVIVE, and only because they guard a NAME the repository may not have.
# A repository can hold a theme composable, an extended-token holder and a
# theme package and still keep its raw colours as top-level vals -- no
# declaration to name. That is a fact about the repository, not a judgment
# about it, so it is probed rather than argued, and the switch records what the
# probe found. With one switch over all five rules that shape had no honest
# answer, and an install wrote paletteObject: 'none' with the rules left ON:
# both matched nothing and reported success over five files that violated them.
#
# WHAT THESE TESTS ARE GUARDING. Three failures now:
#
#   * a rule that quietly REGAINS a switch -- the 27 are asserted against the
#     TEMPLATE, not the rendered output, so `active: @ANYTHING@` on one of them
#     fails here even if today's values happen to render it true.
#   * a surviving switch that flips MORE than its group -- a rule silently
#     deactivated in a repository that has the shape it checks.
#   * a surviving switch that flips LESS than its group -- one member left
#     hardcoded `active: true`, so the switch appears not to work.
#
# All three are invisible in review of a 450-line YAML file and one grep away
# here, so the membership lists below are duplicated from the template ON
# PURPOSE. A test that derived them from the file it is testing would agree
# with any mistake the file makes.
# shellcheck disable=SC1091
. "$HOOK_DIR/tests/fixtures.sh"

DG_TEMPLATES="$HOOK_DIR/../templates"
DG_TMP=$(mktemp -d)

# Every required parameter, so the template renders. The values are arbitrary
# and never inspected -- what is under test is the `active:` line, not the names.
cat >"$DG_TMP/values.json" <<'JSON'
{
  "PACKAGE_ROOT": "com.example.app",
  "MODULE_PACKAGE_ROOTS": "        \"app\" to \"com.example.app\",",
  "LAYER_PURITY_DOMAIN_PACKAGE": "com.example.app.domain",
  "PALETTE_OBJECT": "AppColors",
  "EXTENDED_TOKEN_HOLDER": "ExtendedColors",
  "THEME_OBJECT": "AppTheme"
}
JSON

# Print "<rule> <active-value>" for every rule in the prism: section. Written
# against the file's two/four-space shape rather than with a YAML parser,
# because the payload may not import one.
dg_actives() {
    python3 - "$1" <<'PY'
import re, sys
in_prism, cur = False, None
for line in open(sys.argv[1]):
    line = line.rstrip("\n")
    if re.match(r"^[A-Za-z]", line):
        in_prism = line.startswith("prism:")
        cur = None
        continue
    if not in_prism or not line.strip() or line.strip().startswith("#"):
        continue
    m = re.match(r"^  ([A-Za-z][A-Za-z0-9]*):\s*$", line)
    if m:
        cur = m.group(1)
        continue
    m = re.match(r"^    active:\s*(\S+)\s*$", line)
    if m and cur:
        print(cur, m.group(1))
PY
}

# The names each switch guards. Duplicated from parameters.json ON PURPOSE, for
# the reason the membership lists below are: a test that read `guards` from the
# registry would agree with any mistake the registry makes.
dg_guards() {
    case "$1" in
        PALETTE_RULES_ACTIVE) printf 'PALETTE_OBJECT EXTENDED_TOKEN_HOLDER' ;;
        THEME_RULES_ACTIVE)   printf 'THEME_OBJECT' ;;
        *)                    printf '' ;;
    esac
}

dg_render() {
    # $1 = switch to set false (empty for none); writes to $DG_TMP/out.yml
    #
    # Setting a switch false also sets the names it guards to the inert value,
    # because render.py now REFUSES the two halves disagreeing -- which is the
    # whole point of the split. A test that left a real name beside an inactive
    # group would be asserting against the failure this change exists to cause.
    if [ -n "$1" ]; then
        python3 - "$DG_TMP/values.json" "$1" "$(dg_guards "$1")" >"$DG_TMP/v.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
d[sys.argv[2]] = "false"
for name in sys.argv[3].split():
    d[name] = "none"
print(json.dumps(d))
PY
    else
        cp "$DG_TMP/values.json" "$DG_TMP/v.json"
    fi
    # rc is CHECKED. Left unchecked, a refusal leaves the PREVIOUS out.yml in
    # place and every assertion below silently grades the wrong file.
    rm -f "$DG_TMP/out.yml"
    python3 "$DG_TEMPLATES/../verify/lib/render.py" \
        --parameters "$DG_TEMPLATES/parameters.json" \
        --template "$DG_TEMPLATES/detekt.yml.in" \
        --output "$DG_TMP/out.yml" \
        --values "$DG_TMP/v.json" >/dev/null 2>&1
    if [ ! -f "$DG_TMP/out.yml" ]; then
        printf 'dg_render: render REFUSED for switch [%s]\n' "$1" >&2
        return 1
    fi
}

DG_PALETTE="ThemeColorDirectUse UnwiredThemeColor"
DG_THEME="PreviewMustWrapInTheme RawColorLiteral NoCustomCompositionLocal"
DG_MVI="TextFieldStateInViewModel ObserveAsEventsRequired StateInViewModelRequiresWhileSubscribed StableAnnotationOnUnstableState ScreenStateOnlyInScreenComposable RootAndScreenInSameFile KoinViewModelOnlyInRoot ObserveAsEventsOnlyInRoot RootComposableMustDefaultViewModel EventChannelExposedAsReceiveAsFlow ScreenComposableParameterOrder"
DG_MODULE="KtorCallMustUseSafeCall EmptyResultOverResultUnit WorkerResultTypealiasRequired NoCrossFeatureRouteImport StartKoinOnlyInAppModule HttpClientConstructionOutsideFactory"
DG_NAV="RouteMustBeSerializable RouteMustBeDataObjectOrDataClass NavGraphBuilderExtensionNaming"
DG_TEST="JUnit4InJvmUnitTest JUnit5InInstrumentedTest NonAssertKAssertion MockingLibraryInTest RobolectricInTest JvmComposeTestRule RoomInJvmUnitTest"

# --- the shipped defaults leave every rule live --------------------------
#
# The acceptance bar for the whole change. A repository that supplies nothing
# beyond the six required parameters must receive all 72 rules, exactly as it
# did before the switches existed.
dg_render ""
dg_all=$(dg_actives "$DG_TMP/out.yml")
assert_eq "72" "$(printf '%s\n' "$dg_all" | wc -l | tr -d ' ')" \
    "the prism: section declares 72 rules"
assert_eq "0" "$(printf '%s\n' "$dg_all" | grep -c ' false$' | tr -d ' ')" \
    "with the shipped defaults, no prism rule is inactive"

# --- the 27 unswitched rules carry a LITERAL true in the template --------
#
# Asserted against detekt.yml.in rather than a rendered file. A rendered check
# would pass for `active: @SOMETHING@` whenever that something happened to be
# true, which is exactly the state this change exists to remove.
dg_tpl=$(dg_actives "$DG_TEMPLATES/detekt.yml.in")

for dg_case in \
    "MVI:$DG_MVI" \
    "MODULE_PATH:$DG_MODULE" \
    "NAV:$DG_NAV" \
    "TEST_STACK:$DG_TEST"
do
    dg_group=${dg_case%%:*}
    dg_members=${dg_case#*:}
    dg_bad=""
    for dg_rule in $dg_members; do
        dg_val=$(printf '%s\n' "$dg_tpl" | awk -v r="$dg_rule" '$1==r {print $2}')
        [ "$dg_val" = "true" ] || dg_bad="$dg_bad $dg_rule=$dg_val"
    done
    assert_eq "" "$dg_bad" \
        "every former $dg_group rule is literally active: true in the template"
done

# And nothing else may carry a placeholder. This is the assertion that a
# seventh switch cannot be reintroduced quietly on any of the other 67 rules.
dg_placeheld=$(printf '%s\n' "$dg_tpl" | grep ' @' | cut -d' ' -f1 | sort -u | tr '\n' ' ')
assert_eq "NoCustomCompositionLocal PreviewMustWrapInTheme RawColorLiteral ThemeColorDirectUse UnwiredThemeColor " \
    "$dg_placeheld" \
    "only the five palette/theme rules are switch-governed"

# --- each surviving switch flips its own group, and only its own ---------
for dg_case in \
    "PALETTE_RULES_ACTIVE:$DG_PALETTE" \
    "THEME_RULES_ACTIVE:$DG_THEME"
do
    dg_switch=${dg_case%%:*}
    dg_members=${dg_case#*:}
    dg_size=$(printf '%s\n' $dg_members | wc -l | tr -d ' ')

    dg_render "$dg_switch"
    dg_out=$(dg_actives "$DG_TMP/out.yml")
    dg_off=$(printf '%s\n' "$dg_out" | grep ' false$' | cut -d' ' -f1 | sort)

    # The count is the half that catches a switch reaching too far.
    assert_eq "$dg_size" "$(printf '%s\n' "$dg_off" | grep -c . | tr -d ' ')" \
        "$dg_switch=false deactivates exactly $dg_size rules"

    # The membership is the half that catches a member left behind.
    assert_eq "$(printf '%s\n' $dg_members | sort | tr '\n' ' ')" \
        "$(printf '%s\n' "$dg_off" | tr '\n' ' ')" \
        "$dg_switch=false deactivates exactly its own members"
done

# --- the switches are declared, so --check keeps passing -----------------
#
# A placeholder added to a template without a registry entry renders fine and
# is invisible to the setup skill, which resolves the set it was given. This is
# the assertion that a new switch cannot be added without registering it.
python3 "$DG_TEMPLATES/../verify/lib/render.py" \
    --parameters "$DG_TEMPLATES/parameters.json" \
    --templates-dir "$DG_TEMPLATES/.." --check >/dev/null 2>&1
assert_eq "0" "$?" "every placeholder in the payload is declared"

# --- MagicNumber is deliberately NOT in the theme group ------------------
#
# It is a general rule about magic numbers everywhere; the theme paths are one
# carve-out, and a glob that matches nothing excludes nothing. Folding it in
# would let a switch named "theme" silently remove a check from a repository
# that has plenty of magic numbers and no design system.
dg_render "THEME_RULES_ACTIVE"
assert_contains "$(grep -A2 '^  MagicNumber:' "$DG_TMP/out.yml")" "excludes" \
    "MagicNumber survives THEME_RULES_ACTIVE=false"
assert_not_contains "$(printf '%s\n' "$dg_off")" "MagicNumber" \
    "and it is not a member of any prism group"

rm -rf "$DG_TMP"
