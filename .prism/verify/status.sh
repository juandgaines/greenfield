#!/bin/sh
# prism-verify status — what would the coverage gate say right now?
#
# READ-ONLY BY CONSTRUCTION, and that is the whole design:
#
#   * it renders a verdict and exits 0 no matter what it finds. It is not a
#     gate. It has no allow path, so there is nothing here to weaken and no
#     way to "pass" it.
#   * NO HOOK MAY CALL IT. A gate that consults a reporting script is a gate
#     whose verdict can be changed by editing the reporting script.
#   * it runs no Gradle task and touches no build output. It stats files and
#     reads XML.
#
# Why it exists: there was no way to see the gate's opinion without attempting
# a push. Section 6 needed the thirteen-module kind/freshness/coverage table
# constantly and hand-rolled a throwaway script for it every time — which is
# how a status view drifts away from what actually denies.
#
# It does not drift here because it asks the same questions of the same code:
# lib/coverage.py for kind, freshness, measurement and threshold, and
# lib/affected.sh's prism_refresh_commands for the commands to fix anything
# stale — the very renderer push-gate.sh prints from.
#
# Usage: sh .prism/verify/status.sh [module ...]
set -u

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"

THRESHOLDS="$HOOK_DIR/thresholds.json"

# WHAT WOULD ACTUALLY DENY, not what would deny if everything were promoted.
# The two structural arms below -- no jacoco wiring, and sources with no tests --
# used to print "would DENY" without asking scope.json anything, so on an
# all-observe install (which is what every install starts as) this table claimed
# two verdicts the coverage gate would never reach. That was tolerable while this
# script was a thing you had to know about; it stopped being tolerable when
# `./prism status` made it the primary screen.
#
# The measured arms need no such lookup: coverage.py's `verdict` already returns
# `observe` for a module the scope file observes.
coverage_posture() {
    python3 "$HOOK_DIR/lib/scope.py" get --module "$1" --engine coverage \
        --root "$PRISM_ROOT" 2>/dev/null || printf 'enforce\n'
}

prism_require_root
if [ "$?" -ne 0 ]; then
    printf 'prism-verify status: no usable repository root (PRISM_ROOT=[%s]).\n' \
        "$PRISM_ROOT"
    exit 0
fi

if [ "$#" -gt 0 ]; then
    modules="$*"
else
    modules=$(prism_all_modules)
fi

printf '%-34s %-15s %-18s %8s %8s\n' MODULE KIND STATE COVERAGE REQUIRED
printf '%s\n' '---------------------------------------------------------------------------------------'

stale_entries=""
low_count=0
stale_count=0
pass_count=0
notyet_count=0
blocked_count=0
observe_count=0

for module in $modules; do
    required=$(python3 "$HOOK_DIR/lib/coverage.py" threshold \
        --module "$module" --config "$THRESHOLDS" 2>/dev/null || printf '?')
    kind=$(python3 "$HOOK_DIR/lib/coverage.py" check \
        --module "$module" --root "$PRISM_ROOT" 2>/dev/null || printf 'unreadable')

    case "$kind" in
        no-jacoco)
            if [ "$(coverage_posture "$module")" = "observe" ]; then
                printf '%-34s %-15s %-18s %8s %8s\n' \
                    "$module" "$kind" "blocks if promoted" "-" "$required"
                notyet_count=$((notyet_count + 1))
            else
                printf '%-34s %-15s %-18s %8s %8s\n' \
                    "$module" "$kind" "would DENY" "-" "$required"
                blocked_count=$((blocked_count + 1))
            fi
            continue
            ;;
        none)
            has_sources=$(python3 "$HOOK_DIR/lib/coverage.py" has-sources \
                --module "$module" --root "$PRISM_ROOT" 2>/dev/null || printf 'no')
            if [ "$has_sources" = "yes" ]; then
                if [ "$(coverage_posture "$module")" = "observe" ]; then
                    printf '%-34s %-15s %-18s %8s %8s\n' \
                        "$module" "no tests" "blocks if promoted" "-" "$required"
                    notyet_count=$((notyet_count + 1))
                else
                    printf '%-34s %-15s %-18s %8s %8s\n' \
                        "$module" "no tests" "would DENY" "-" "$required"
                    blocked_count=$((blocked_count + 1))
                fi
            else
                printf '%-34s %-15s %-18s %8s %8s\n' \
                    "$module" "no sources" "n/a" "-" "$required"
            fi
            continue
            ;;
    esac

    xml=$(prism_coverage_xml_for "$module" "$kind")
    verdict=$(python3 "$HOOK_DIR/lib/coverage.py" verdict --module "$module" \
        --root "$PRISM_ROOT" --xml "$xml" --kind "$kind" \
        --config "$THRESHOLDS" 2>/dev/null)
    state=$(printf '%s' "$verdict" | awk '{print $1}')
    actual=$(printf '%s' "$verdict" | awk '{ if (NF >= 2) print $2; else print "-" }')

    case "$state" in
        # OBSERVE HAS ITS OWN ARM, because it is the state every module is in
        # while somebody is preparing to promote it. coverage.py prints the
        # number it measured and exits 0; this used to fall to the catch-all,
        # which hardcodes a dash, counts the module as having no current
        # result, and prints a refresh command that changes nothing. The
        # documented loop -- measure, read the number, write tests, repeat,
        # then promote -- could not be run from this tool for any module not
        # already promoted, which is every module you would ever run it for.
        #
        # Three shapes come back: a measurement, or `no-report`, or
        # `no-line-data`. Only the first is a number, and only the last two
        # are genuinely stale.
        observe)
            case "$actual" in
                no-report|no-line-data)
                    printf '%-34s %-15s %-18s %8s %8s\n' \
                        "$module" "$kind" "observe (no result)" "-" "$required"
                    stale_entries="$stale_entries $module=$kind=$actual"
                    stale_count=$((stale_count + 1))
                    ;;
                *)
                    # Shown as it would be judged if promoted today, so the gap
                    # somebody is meant to be closing is legible. Nothing here
                    # blocks -- that is what `observe` means.
                    case "$required" in
                        ''|*[!0-9]*) observe_state="observe" ;;
                        *)
                            if [ "$(awk -v a="$actual" -v r="$required" \
                                    'BEGIN { print (a + 0 >= r + 0) ? "y" : "n" }')" \
                                    = "y" ]; then
                                observe_state="observe (clears)"
                            else
                                observe_state="observe (short)"
                            fi
                            ;;
                    esac
                    printf '%-34s %-15s %-18s %8s %8s\n' \
                        "$module" "$kind" "$observe_state" "$actual" "$required"
                    observe_count=$((observe_count + 1))
                    ;;
            esac
            ;;
        pass)
            printf '%-34s %-15s %-18s %8s %8s\n' \
                "$module" "$kind" "ok" "$actual" "$required"
            pass_count=$((pass_count + 1))
            ;;
        # A module with tests and no production sources. It has a KIND, so it
        # never reaches the `none` arm above, and until 0.6.0 it fell to the
        # catch-all and read "would DENY" -- which the gate then did.
        no-sources)
            printf '%-34s %-15s %-18s %8s %8s\n' \
                "$module" "$kind" "no sources" "-" "$required"
            ;;
        low)
            printf '%-34s %-15s %-18s %8s %8s\n' \
                "$module" "$kind" "would DENY (low)" "$actual" "$required"
            low_count=$((low_count + 1))
            ;;
        *)
            printf '%-34s %-15s %-18s %8s %8s\n' \
                "$module" "$kind" "would DENY" "-" "$required"
            stale_entries="$stale_entries $module=$kind=$state"
            stale_count=$((stale_count + 1))
            ;;
    esac
done

printf '\n%s ok, %s observed, %s below threshold, %s without a current result, %s structurally blocked\n' \
    "$pass_count" "$observe_count" "$low_count" "$stale_count" "$blocked_count"
if [ "$notyet_count" -gt 0 ]; then
    printf '%s module(s) would be structurally blocked if promoted — no jacoco\n' \
        "$notyet_count"
    printf 'wiring, or sources with no tests. Coverage observes them today, so\n'
    printf 'nothing there denies a push yet.\n'
fi

if [ -n "$stale_entries" ]; then
    printf '\nTo refresh the modules with no current result:\n'
    needs_device="no"
    for entry in $stale_entries; do
        module=$(printf '%s' "$entry" | cut -d= -f1)
        kind=$(printf '%s' "$entry" | cut -d= -f2)
        state=$(printf '%s' "$entry" | cut -d= -f3)
        printf '\n  %s (%s)\n' "$module" "$state"
        if [ "$state" = "device-run-no-data" ]; then
            printf '    the suite RAN for this code but brought back no .ec — a killed\n'
            printf '    connected run. The device lock below stops it racing again.\n'
        fi
        prism_refresh_commands "$module" "$kind" | sed 's/^/    /'
        if prism_kind_needs_device "$kind"; then
            needs_device="yes"
        fi
    done
    if [ "$needs_device" = "yes" ]; then
        printf '\n  The connected runs need a booted emulator (minSdk 26):\n'
        printf '    python3 %s --min-sdk 26\n' \
            "$PRISM_ASSETS_DIR/select_emulator.py"
        printf '    android emulator start <avd>\n'
    fi
fi

# Always. This script has no verdict to express through an exit status, and
# giving it one would be the first step towards something calling it as a gate.
exit 0
