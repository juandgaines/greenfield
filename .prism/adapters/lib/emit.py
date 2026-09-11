#!/usr/bin/env python3
"""Translate the gate's verdict into the shape this harness reads.

`normalise.py` is the inbound half of the seam; this is the outbound half. The
gates answer in one vocabulary -- an exit status and a reason on stderr -- and
every harness listens in a different one.

WHY THIS IS A SIBLING OF normalise.py RATHER THAN A BRANCH IN prism-hook.
The dispatcher's header argues for one translation table in one testable place.
The inbound half already lives here; the outbound half has strictly MORE
per-harness vocabulary than the inbound half does, and until now it was a single
`if [ "$HARNESS" = "antigravity" ]` at the end of the shell script with four
other harnesses falling past it. Reason text is up to a hundred lines of Gradle
output containing quotes and newlines, so the escaping belongs in json.dumps
rather than in shell quoting.

TWO PROTOCOL FAMILIES.

    exit code   claude-code, codex, copilot -- read the process status and the
                stderr they were handed. Nothing to translate: pass the status
                through VERBATIM, because a gate that died with 127 is not a
                denial and the harness distinguishes them.

    document    cursor, antigravity -- decide from a JSON document on stdout.
                For these an empty stdout is not "allow", it is a FAILED HOOK,
                and Cursor's adapter correctly sets `failClosed: true`, which
                turns a failed hook into a block. That is the whole defect this
                file exists to close: the gates were happy and every command was
                blocked anyway.

WHAT "DENY" MEANS PER EVENT. Two of the four gates cannot block on either
document harness, and the exit status the gate produced does not say so:

    stop        Cursor: `followup_message`, auto-submitted, bounded by
                loop_limit.  Antigravity: `decision: "continue"`, whose `reason`
                is injected as a system message. NEITHER CAN DENY.
    subagent    Cursor: `subagentStop` carries `followup_message`; only
                `subagentStart` can deny, and PRISM does not register it.

So `stop-gate.sh` exiting 2 is a REASON-DELIVERY mechanism, not a verdict: exit
2 is simply the only status that puts a hook's stderr in front of Claude Code.
Returning that 2 to Cursor makes every finding a failed hook on an event that
could not have blocked anyway. Here it becomes a continuation carrying the same
text, which is the `turn_end: "continue"` the manifest has advertised all along
with nothing behind it.

Python 3 standard library only, and no imports from the engine: this runs in a
process the harness started, beside the gate rather than inside it.
"""

import json
import sys

GATES = ("push", "ktlint", "stop", "subagent")
HARNESSES = ("claude-code", "cursor", "codex", "copilot", "antigravity")

# Harnesses that read the process exit status. A harness absent from the
# document translators below is one of these by construction.
EXIT_CODE_HARNESSES = ("claude-code", "codex", "copilot")

# stdout policies, line 1 of this program's own output.
PASSTHROUGH = "passthrough"   # write the gate's stdout unchanged
DOCUMENT = "document"         # write line 2+ instead; the gate's stdout is
                              # Claude Code's vocabulary and means nothing here

DENIED = "prism-verify denied this action."
UNMET = (
    "prism-verify: verification did not pass, so this turn's work is NOT "
    "verified. Run ./gradlew staticAnalysis and the failing module's build to "
    "see why, then fix it."
)


def _first_line(text):
    """The user gets one line; the agent gets the whole report."""
    if not text:
        return ""
    return text.strip().splitlines()[0] if text.strip() else ""


def _exit_code(gate, status, message):
    """claude-code, codex, copilot -- the status IS the verdict."""
    return status, None


def _cursor(gate, status, message):
    denied = status != 0
    if gate == "push":
        # beforeShellExecution. The one Cursor event that can actually block.
        if denied:
            # Exit 2 is kept ALONGSIDE the document so both channels say the
            # same thing and cannot disagree, and so prism-doctor's existing
            # deny probe keeps passing byte-for-byte. The fix is additive here.
            return 2, {
                "permission": "deny",
                "agent_message": message or DENIED,
                "user_message": _first_line(message) or DENIED,
            }
        # Explicitly "allow" rather than {}: an empty document is exactly what
        # could not be told apart from a hook that produced nothing, which is
        # the confusion that caused this defect. It also gives prism-doctor's
        # allow probe something to assert.
        doc = {"permission": "allow"}
        if message:
            doc["agent_message"] = message
        return 0, doc
    if gate == "ktlint":
        # afterFileEdit is observational and cannot block. The gate's exit 2 is
        # a report, not a denial, so returning it would make every formatting
        # finding a failed hook on an event that has no verdict to give.
        return 0, ({"agent_message": message} if message else {})
    # stop / subagentStop. Cannot deny; carry the reason as the continuation.
    if denied:
        return 0, {"followup_message": message or UNMET}
    # An allow that still carried text is the give-up notice or a subagent
    # report. It rides on stderr: re-prompting the agent about a loop we have
    # just conceded would restart the loop we gave up on.
    return 0, {}


def _antigravity(gate, status, message):
    denied = status != 0
    if gate == "push":
        # PreToolUse. Vocabulary is allow/deny/ask/force_ask.
        if denied:
            return 0, {"decision": "deny", "reason": message or DENIED}
        return 0, {"decision": "allow"}
    if gate == "stop":
        # Stop re-enters the loop on `continue` and CANNOT deny. Emitting a
        # denial here -- which is what shipped -- is a verdict this harness has
        # no way to act on.
        if denied:
            return 0, {"decision": "continue", "reason": message or UNMET}
        return 0, {}
    # PostToolUse (ktlint) has no advisory channel: `injectSteps` belongs to
    # PreInvocation/PostInvocation, not to a tool-allow path. The report goes to
    # stderr only. Do not "fix" this by inventing a key the harness will reject.
    return 0, {}


TRANSLATORS = {
    "cursor": _cursor,
    "antigravity": _antigravity,
}


def emit(harness, gate, status, message):
    """Return (exit_status, document_or_None) for this harness and gate."""
    if harness not in HARNESSES or gate not in GATES:
        # Unknown pairing: hand back the gate's own status untouched rather
        # than inventing a verdict for a harness this file does not know.
        return status, None
    translator = TRANSLATORS.get(harness, _exit_code)
    return translator(gate, status, message)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) != 3:
        sys.stderr.write("usage: emit.py <harness> <gate> <gate-status>\n")
        return 2
    harness, gate, raw_status = argv
    try:
        status = int(raw_status)
    except ValueError:
        return 2
    message = sys.stdin.read()
    exit_status, document = emit(harness, gate, status, message)
    policy = PASSTHROUGH if document is None else DOCUMENT
    sys.stdout.write("%d %s\n" % (exit_status, policy))
    if document is not None:
        sys.stdout.write(json.dumps(document))
    # Always 0: this program succeeded in phrasing the verdict. The verdict
    # itself travels on line 1, so the caller can tell "could not run" (empty
    # output) from "the gate said deny" (a document that says so).
    return 0


if __name__ == "__main__":
    sys.exit(main())
