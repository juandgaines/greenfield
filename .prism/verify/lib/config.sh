#!/bin/sh
# Read PRISM's runtime configuration. Sourced, never executed.
#
# Runtime configuration is the first of the three substitution classes: it is
# read live on every run and rendered into nothing. Changing a value takes
# effect on the next gate invocation with no file regenerated.
#
# Because gates read these values, this file sits on the production decision
# path, and it inherits that path's rule: an inability to ask the question is
# not an answer of "no". A configuration file that is absent, unparseable, or
# missing a key a gate needs makes that gate DENY and say why. It does not fall
# back to a built-in default.
#
# The framework already learned this on the line above push-gate.sh's extractor:
# a broken python3 once allowed EVERY push through, ungated and silently,
# because "could not read" and "nothing to check" printed the same thing. The
# same trap is available here -- a missing prism.json quietly meaning "use the
# defaults" would gate a consumer's repository against PRISM's guesses while
# reporting success. So this distinguishes the two cases with a sentinel, as
# push-gate.sh does.

# Where the config lives. PRISM_CONFIG is an override for the test suite and
# for a caller running against a tree that is not its own; it cannot disable
# anything, only redirect the read.
prism_config_path() {
    if [ -n "${PRISM_CONFIG:-}" ]; then
        printf '%s' "$PRISM_CONFIG"
    else
        printf '%s' "${PRISM_ROOT:-.}/.prism/prism.json"
    fi
}

# prism_config_get <key> [subkey]
#
# Prints the value on stdout and returns 0. Returns 1 with a human-readable
# reason on stderr when the config cannot be read or the key is absent. A gate
# calling this MUST treat a non-zero return as a denial, not as an empty value.
prism_config_get() {
    prism_config_key="$1"
    prism_config_sub="${2:-}"
    prism_config_file=$(prism_config_path)

    if [ ! -f "$prism_config_file" ]; then
        printf 'prism: no configuration at %s\n' "$prism_config_file" >&2
        printf 'The gate cannot determine %s and will not guess.\n' \
            "$prism_config_key" >&2
        return 1
    fi

    # Sentinel-tagged for the same reason push-gate.sh tags its extraction:
    # "python3 says the key is absent" and "python3 could not run" must not
    # arrive as the same empty string.
    prism_config_out=$(
        PRISM_K="$prism_config_key" PRISM_SK="$prism_config_sub" \
        python3 -c '
import json, os, sys
key = os.environ["PRISM_K"]
sub = os.environ["PRISM_SK"]
try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        data = json.load(handle)
except Exception as exc:
    print("BADFILE" + str(exc))
    raise SystemExit(0)
if not isinstance(data, dict) or key not in data:
    print("NOKEY")
    raise SystemExit(0)
value = data[key]
if sub:
    if not isinstance(value, dict) or sub not in value:
        print("NOKEY")
        raise SystemExit(0)
    value = value[sub]
if isinstance(value, bool):
    value = "true" if value else "false"
print("OK" + str(value))
' "$prism_config_file" 2>/dev/null
    )
    prism_config_status=$?

    if [ "$prism_config_status" -ne 0 ] || [ -z "$prism_config_out" ]; then
        printf 'prism: python3 exited %s reading %s\n' \
            "$prism_config_status" "$prism_config_file" >&2
        printf 'The gate cannot read its configuration, so it cannot verify\n' >&2
        printf 'anything. Fix python3, then retry.\n' >&2
        return 1
    fi

    case "$prism_config_out" in
        OK*)
            printf '%s' "${prism_config_out#OK}"
            return 0
            ;;
        NOKEY)
            printf 'prism: %s is not set in %s\n' \
                "$prism_config_key" "$prism_config_file" >&2
            printf 'The gate will not substitute a default for a value the\n' >&2
            printf 'configuration was supposed to carry.\n' >&2
            return 1
            ;;
        BADFILE*)
            printf 'prism: %s could not be parsed\n' "$prism_config_file" >&2
            printf '  %s\n' "${prism_config_out#BADFILE}" >&2
            return 1
            ;;
        *)
            printf 'prism: unexpected output reading %s\n' "$prism_config_file" >&2
            return 1
            ;;
    esac
}
