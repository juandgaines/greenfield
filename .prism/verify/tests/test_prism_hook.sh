# Tests for adapters/lib/prism-hook -- the dispatcher's OUTPUT leg.
# Sourced by run-tests.sh.
#
# There was no test of this file at all, which is how a Cursor adapter shipped
# in which every ALLOW printed nothing on stdout. Cursor decides from a JSON
# document there, and the adapter correctly sets `failClosed: true`, so "no
# usable output" is a failed hook and a failed hook BLOCKS. The gates were
# fine; every shell command in a Cursor session was refused anyway.
#
# The seam that makes this cheap is PRISM_ENGINE_DIR: point it at stub gates
# that exit on demand and the whole matrix runs with no Gradle and no JVM.

HOOK_BIN="$HOOK_DIR/../adapters/lib/prism-hook"

ph_tmp=$(mktemp -d)
mkdir -p "$ph_tmp/verify"

# Stub gates. $PH_GATE_EXIT decides the verdict; the reason goes to stderr,
# exactly as the real gates write it.
for ph_gate in push-gate ktlint stop-gate subagent-gate; do
    cat > "$ph_tmp/verify/$ph_gate.sh" <<'STUB'
cat >/dev/null
printf 'prism-verify: stub reason line one\nstub reason line two\n' >&2
exit "${PH_GATE_EXIT:-0}"
STUB
done

# Run the dispatcher against the stubs. Echoes stdout, then `rc=<status>`.
ph_run() {
    ph_harness="$1"; ph_gate_name="$2"; ph_exit="$3"; ph_payload="$4"
    (
        PRISM_ENGINE_DIR="$ph_tmp/verify"
        PH_GATE_EXIT="$ph_exit"
        export PRISM_ENGINE_DIR PH_GATE_EXIT
        printf '%s' "$ph_payload" \
            | sh "$HOOK_BIN" --harness "$ph_harness" --gate "$ph_gate_name" 2>/dev/null
        printf 'rc=%s' "$?"
    )
}

# --- the defect itself ------------------------------------------------------
#
# Every one of these was an empty stdout before the output leg existed.

assert_contains "$(ph_run cursor push 0 '{"command":"echo hi"}')" '"permission": "allow"' \
    "cursor: an allowed command says so in the document cursor reads"

assert_contains "$(ph_run cursor push 0 '{"command":"echo hi"}')" 'rc=0' \
    "cursor: an allowed command exits 0"

assert_contains "$(ph_run cursor ktlint 0 '{"file_path":"/tmp/a.kt"}')" '{' \
    "cursor: the ktlint gate answers with a document rather than nothing"

# The most frequent allow on any harness: an event carrying nothing this gate
# looks at. It short-circuits before the gate runs and so never reached the
# output leg at all -- an empty stdout, which on cursor is a block.
assert_contains "$(ph_run cursor ktlint 0 '{}')" '{}' \
    "cursor: a no-event allow still answers"

assert_contains "$(ph_run cursor ktlint 0 '{}')" 'rc=0' \
    "cursor: a no-event allow exits 0"

# The gate ran and had something to say, on a path that cannot block.
assert_contains "$(ph_run cursor ktlint 2 '{"file_path":"/tmp/a.kt"}')" 'agent_message' \
    "cursor: a formatting report is advisory, not a refusal"

assert_contains "$(ph_run cursor ktlint 2 '{"file_path":"/tmp/a.kt"}')" 'rc=0' \
    "cursor: a formatting report never returns a blocking status"

assert_contains "$(ph_run cursor push 0 '{"tool":"Read"}')" '"permission": "allow"' \
    "cursor: a no-event push allow answers in cursor's vocabulary"

# --- deny still denies, by both routes --------------------------------------

assert_contains "$(ph_run cursor push 2 '{"command":"git push"}')" '"permission": "deny"' \
    "cursor: a denied command says deny in the document"

assert_contains "$(ph_run cursor push 2 '{"command":"git push"}')" 'rc=2' \
    "cursor: deny also keeps exit 2, so both channels agree"

assert_contains "$(ph_run cursor push 2 '{"command":"git push"}')" 'stub reason line one' \
    "cursor: the gate's reason reaches the agent"

# An unparseable payload is what prism-doctor probes with.
assert_contains "$(ph_run cursor push 0 'not a hook event')" 'rc=2' \
    "cursor: an unreadable event is refused"

assert_contains "$(ph_run cursor push 0 'not a hook event')" '"permission": "deny"' \
    "cursor: an unreadable event is refused in cursor's vocabulary too"

# --- turn end: continue, not deny -------------------------------------------
#
# The manifest advertises turn_end: "continue" for cursor. Cursor CANNOT deny
# at turn end -- it auto-submits a follow-up instead -- so returning the gate's
# exit 2 made every finding a failed hook on an event with no verdict to give.

assert_contains "$(ph_run cursor stop 2 '{"status":"completed","loop_count":0}')" 'followup_message' \
    "cursor: a failed turn-end check becomes a continuation"

assert_contains "$(ph_run cursor stop 2 '{"status":"completed","loop_count":0}')" 'rc=0' \
    "cursor: turn end never returns a blocking status"

assert_not_contains "$(ph_run cursor stop 2 '{"status":"completed","loop_count":0}')" '"permission"' \
    "cursor: turn end does not use the blocking vocabulary"

assert_contains "$(ph_run cursor subagent 2 '{"subagent_type":"x","status":"completed"}')" 'followup_message' \
    "cursor: subagentStop is advisory -- only subagentStart can deny"

# --- antigravity ------------------------------------------------------------

assert_contains "$(ph_run antigravity push 0 '{"toolCall":{"args":{"CommandLine":"echo hi"}}}')" '"decision": "allow"' \
    "antigravity: an allowed command says so rather than printing nothing"

assert_contains "$(ph_run antigravity push 2 '{"toolCall":{"args":{"CommandLine":"git push"}}}')" '"decision": "deny"' \
    "antigravity: deny keeps its vocabulary"

assert_contains "$(ph_run antigravity stop 2 '{"executionNum":1}')" '"decision": "continue"' \
    "antigravity: Stop cannot deny, so a failure continues with the reason"

assert_not_contains "$(ph_run antigravity stop 2 '{"executionNum":1}')" '"deny"' \
    "antigravity: Stop never emits a verdict the harness cannot act on"

# --- the exit-code harnesses must be untouched ------------------------------
#
# They share this dispatcher. A change made for the document harnesses that
# altered these would be a far worse defect than the one being fixed.

assert_contains "$(ph_run claude-code push 0 '{"tool_input":{"command":"echo hi"}}')" 'rc=0' \
    "claude-code: allow is still a bare exit 0"

assert_contains "$(ph_run claude-code push 2 '{"tool_input":{"command":"git push"}}')" 'rc=2' \
    "claude-code: deny is still exit 2"

assert_not_contains "$(ph_run claude-code push 0 '{"tool_input":{"command":"echo hi"}}')" 'permission' \
    "claude-code: no document is invented for a harness that reads the status"

assert_contains "$(ph_run codex push 2 '{"tool_input":{"command":"git push"}}')" 'rc=2' \
    "codex: deny is still exit 2"

assert_contains "$(ph_run copilot push 0 '{"toolArgs":{"command":"echo hi"}}')" 'rc=0' \
    "copilot: allow is still a bare exit 0"

# A gate that crashed is not a gate that refused, and the harness tells them
# apart. Normalising 127 to 2 would report a clean tree as blocked.
assert_contains "$(ph_run claude-code push 127 '{"tool_input":{"command":"echo hi"}}')" 'rc=127' \
    "claude-code: a crashed gate keeps its own status"

# --- a missing engine is still a denial on the blocking gate ----------------

assert_contains "$(
    (
        PRISM_ENGINE_DIR="$ph_tmp/nowhere"
        export PRISM_ENGINE_DIR
        printf '{"command":"git push"}' | sh "$HOOK_BIN" --harness cursor --gate push 2>/dev/null
        printf 'rc=%s' "$?"
    )
)" '"permission": "deny"' \
    "cursor: a missing engine denies in cursor's vocabulary rather than silently"

rm -rf "$ph_tmp"
