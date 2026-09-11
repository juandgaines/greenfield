#!/bin/sh
# Minimal POSIX test runner for the prism-verify hooks.
# Usage: sh tests/run-tests.sh [test_file.sh ...]
# With no arguments it runs every tests/test_*.sh.
set -u

TESTS_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HOOK_DIR=$(dirname "$TESTS_DIR")
export HOOK_DIR

PRISM_TEST_PASSED=0
PRISM_TEST_FAILED=0

assert_eq() {
    # assert_eq <expected> <actual> <label>
    if [ "$1" = "$2" ]; then
        PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
    else
        PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
        printf 'FAIL %s\n  expected: [%s]\n  actual:   [%s]\n' "$3" "$1" "$2"
    fi
}

assert_ne() {
    # assert_ne <unwanted> <actual> <label>
    if [ "$1" != "$2" ]; then
        PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
    else
        PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
        printf 'FAIL %s\n  expected anything but: [%s]\n' "$3" "$1"
    fi
}

assert_contains() {
    # assert_contains <haystack> <needle> <label>
    case "$1" in
        *"$2"*)
            PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
            ;;
        *)
            PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
            printf 'FAIL %s\n  expected to contain: [%s]\n  in: [%s]\n' "$3" "$2" "$1"
            ;;
    esac
}

assert_not_contains() {
    # assert_not_contains <haystack> <needle> <label>
    case "$1" in
        *"$2"*)
            PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
            printf 'FAIL %s\n  expected NOT to contain: [%s]\n  in: [%s]\n' "$3" "$2" "$1"
            ;;
        *)
            PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
            ;;
    esac
}

# Added in 0.6.2, when handoff.py became the one thing in PRISM that deletes a
# file in a consumer's repository. "It is still there" and "it is gone" are the
# assertions that feature is made of, and spelling them as assert_eq on a
# subshell hid which half of the pair had failed.
assert_path_exists() {
    # assert_path_exists <path> <label>
    if [ -e "$1" ]; then
        PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
    else
        PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
        printf 'FAIL %s\n  expected to exist: [%s]\n' "$2" "$1"
    fi
}

assert_path_absent() {
    # assert_path_absent <path> <label>
    if [ -e "$1" ]; then
        PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + 1))
        printf 'FAIL %s\n  expected to be gone: [%s]\n' "$2" "$1"
    else
        PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + 1))
    fi
}

if [ "$#" -gt 0 ]; then
    files="$*"
else
    files=$(ls "$TESTS_DIR"/test_*.sh 2>/dev/null)
fi

# EVERY ASSERTION A TEST CALLS MUST EXIST. `assert_ne` was called by
# test_preflight_detekt.sh for several releases and was never defined: sh
# printed "command not found" to stderr, the assertion did not run, and the
# suite still reported "0 failed". A test that cannot run and does not fail is
# the same silent green this whole framework exists to prevent, one level down.
#
# Checked BEFORE the files are sourced, so the run stops rather than reporting a
# total that quietly omits an assertion.
prism_undefined_assertions() {
    used=$(grep -ohE '\bassert_[a-z_]+' "$TESTS_DIR"/test_*.sh 2>/dev/null | sort -u)
    for name in $used; do
        if ! command -v "$name" >/dev/null 2>&1; then
            printf '%s ' "$name"
        fi
    done
}
# EVERY TEST CLASS MUST BE REACHABLE. test_coverage.py carried an
# `if __name__ == "__main__": unittest.main()` block in the MIDDLE of the file,
# with a class defined after it. Run as a script -- which is exactly how this
# runner runs the python suites -- the interpreter never reaches that class, so
# four assertions about the coverage verdict did not exist. The suite reported
# "55 passed" and none of them was those. Same silent green as an undefined
# assertion helper, one level up: the total was honest about what it ran and said
# nothing about what it did not.
prism_unreachable_classes() {
    for file in "$TESTS_DIR"/test_*.py; do
        [ -f "$file" ] || continue
        # The FIRST call, not the last: running as a script the interpreter
        # stops at whichever comes first, so a second one at the end of the file
        # rescues nothing.
        first_main=$(grep -n 'unittest.main(' "$file" | head -1 | cut -d: -f1)
        last_class=$(grep -n '^class ' "$file" | tail -1 | cut -d: -f1)
        [ -n "$first_main" ] || continue
        [ -n "$last_class" ] || continue
        if [ "$first_main" -lt "$last_class" ]; then
            printf '%s ' "$(basename "$file")"
        fi
    done
}
unreachable=$(prism_unreachable_classes)
if [ -n "$unreachable" ]; then
    printf 'FAIL test class(es) defined AFTER unittest.main() in: %s\n' "$unreachable"
    printf '  Run as a script, the interpreter exits at that call and never\n'
    printf '  defines them. Those tests do not run and nothing fails.\n'
    exit 1
fi

undefined=$(prism_undefined_assertions)
if [ -n "$undefined" ]; then
    printf 'FAIL undefined assertion helper(s) called by the shell suite: %s\n' \
        "$undefined"
    printf '  Every assert_* a test calls must be defined in this file, or the\n'
    printf '  assertion silently does not run and the suite still reports 0 failed.\n'
    exit 1
fi

for file in $files; do
    printf '\n--- %s\n' "$(basename "$file")"
    # shellcheck disable=SC1090
    . "$file"
done

# The python suites are counted here too, so the total this script prints is
# the project's whole hook-test count rather than only the shell half. They
# were previously run by hand, which made "the suite passes" ambiguous about
# which suite. Skipped entirely when a specific file was named.
if [ "$#" -eq 0 ]; then
    for file in "$TESTS_DIR"/test_*.py; do
        [ -f "$file" ] || continue
        printf '\n--- %s\n' "$(basename "$file")"
        output=$(python3 "$file" 2>&1)
        status=$?
        count=$(printf '%s\n' "$output" | sed -n 's/^Ran \([0-9]*\) test.*/\1/p')
        [ -n "$count" ] || count=0
        if [ "$status" -eq 0 ]; then
            PRISM_TEST_PASSED=$((PRISM_TEST_PASSED + count))
        else
            PRISM_TEST_FAILED=$((PRISM_TEST_FAILED + count))
            printf '%s\n' "$output"
        fi
    done
fi

printf '\n%s passed, %s failed\n' "$PRISM_TEST_PASSED" "$PRISM_TEST_FAILED"
[ "$PRISM_TEST_FAILED" -eq 0 ] || exit 1
