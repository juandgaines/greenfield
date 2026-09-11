# Tests for lib/config.sh -- the runtime-configuration loader.
# Sourced by run-tests.sh.
#
# The loader sits on the production decision path, so what is asserted here is
# mostly what it REFUSES to do. A loader that silently substituted a default
# for an unreadable config would gate a consumer's repository against PRISM's
# guesses while reporting success.

CONFIG_LIB="$HOOK_DIR/lib/config.sh"

config_tmp=$(mktemp -d)

# Each case runs in its own subshell: config.sh is sourced, and a sourced file
# that sets variables must not leak between assertions.
config_run() {
    (
        PRISM_CONFIG="$1"
        export PRISM_CONFIG
        . "$CONFIG_LIB"
        prism_config_get "$2" "${3:-}"
    ) 2>/dev/null
}

config_status() {
    (
        PRISM_CONFIG="$1"
        export PRISM_CONFIG
        . "$CONFIG_LIB"
        prism_config_get "$2" "${3:-}" >/dev/null 2>&1
    )
    printf '%s' "$?"
}

config_stderr() {
    (
        PRISM_CONFIG="$1"
        export PRISM_CONFIG
        . "$CONFIG_LIB"
        prism_config_get "$2" "${3:-}" 2>&1 >/dev/null
    )
}

printf '{"baseBranch":"trunk","coverage":{"default":80},"enabled":true}\n' \
    > "$config_tmp/good.json"
printf 'not json at all\n' > "$config_tmp/broken.json"
printf '{"baseBranch":"trunk"}\n' > "$config_tmp/partial.json"

# --- the happy path, so the refusals below mean something ------------------

assert_eq "trunk" "$(config_run "$config_tmp/good.json" baseBranch)" \
    "a present key is returned"

assert_eq "0" "$(config_status "$config_tmp/good.json" baseBranch)" \
    "a present key exits 0"

assert_eq "80" "$(config_run "$config_tmp/good.json" coverage default)" \
    "a nested key is returned"

assert_eq "true" "$(config_run "$config_tmp/good.json" enabled)" \
    "a boolean is a shell-comparable string, not Python's True"

# --- absent file: deny, do not default -------------------------------------

assert_eq "1" "$(config_status "$config_tmp/absent.json" baseBranch)" \
    "a missing config file fails rather than returning a default"

assert_contains "$(config_stderr "$config_tmp/absent.json" baseBranch)" \
    "no configuration at" \
    "a missing config file says so"

assert_eq "" "$(config_run "$config_tmp/absent.json" baseBranch)" \
    "a missing config file prints nothing a caller could mistake for a value"

# --- malformed file: deny ---------------------------------------------------

assert_eq "1" "$(config_status "$config_tmp/broken.json" baseBranch)" \
    "an unparseable config file fails"

assert_contains "$(config_stderr "$config_tmp/broken.json" baseBranch)" \
    "could not be parsed" \
    "an unparseable config file names the parse failure"

# --- missing key: deny, and do NOT fall back to a built-in ------------------

assert_eq "1" "$(config_status "$config_tmp/partial.json" coverage)" \
    "a key the config does not carry fails"

assert_contains "$(config_stderr "$config_tmp/partial.json" coverage)" \
    "is not set in" \
    "a missing key names the key and the file"

assert_eq "1" "$(config_status "$config_tmp/good.json" coverage missing)" \
    "a missing nested key fails"

# --- the override is a redirect, never a disable ----------------------------

assert_contains "$(cat "$CONFIG_LIB")" "PRISM_CONFIG" \
    "the only seam is a path override"

assert_not_contains "$(cat "$CONFIG_LIB")" "SKIP" \
    "config.sh carries no skip switch"

rm -rf "$config_tmp"
