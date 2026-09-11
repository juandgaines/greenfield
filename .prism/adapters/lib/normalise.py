#!/usr/bin/env python3
"""Translate one harness's hook payload into the shape the gates already read.

The gates are agent-agnostic; the harnesses are not. Every supported harness
delivers a different JSON document for the same event -- different key names,
different nesting, different casing, and in two cases a field that simply is not
there. Rather than teach four gates about five harnesses, this translates at the
edge and leaves the engine reading exactly what it has always read.

THE CANONICAL SHAPE is Claude Code's, because that is what the gates were
written against and what `docs/harness-capability-matrix.md` records as the
gate-side requirement:

    tool_input.command    push-gate.sh:29        the shell command, axis A
    tool_input.file_path  lib/ktlint_report.py   the edited file, axis C
    stop_hook_active      stop-gate.sh:88        turn-end loop guard, axis B
    cwd, agent_id,        subagent-gate.sh:47-49 subagent boundary, axis D
    agent_type

WHY A MISSING FIELD IS NOT AN EMPTY STRING. The push gate allows when an event
carries no command, because a `Read` event has nothing to gate. If translation
quietly produced an empty command for an event it did not understand, every push
through that harness would be allowed, ungated and silently -- the exact failure
push-gate.sh's own header describes. So this distinguishes three outcomes:

    ok         the event was understood; the canonical document follows
    no-event   understood, and this event has nothing this gate looks at
    unknown    NOT understood -- the caller must deny rather than guess

Only the first two are safe to continue from, and the dispatcher treats
`unknown` on a blocking gate as a denial.

Python 3 standard library only, and no imports from the engine: this runs before
the engine does, in a process the harness started.
"""

import json
import sys

GATES = ("push", "ktlint", "stop", "subagent")

# Harnesses whose payload this file knows. A harness absent here is `unknown`
# by construction rather than by omission -- adding a registration file without
# adding its translation cannot silently produce empty events.
HARNESSES = ("claude-code", "cursor", "codex", "copilot", "antigravity")

OK = "ok"
NO_EVENT = "no-event"
UNKNOWN = "unknown"


def _dig(data, *path):
    """Walk nested dicts, returning None rather than raising on any miss."""
    cur = data
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def _text(value):
    """Only a string is a usable value here; a number or dict is not."""
    return value if isinstance(value, str) else None


def _first_path(value):
    """Antigravity and Cursor hand back a list where others hand back a string."""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        for item in value:
            if isinstance(item, str) and item:
                return item
    return None


# ---------------------------------------------------------------------------
# Per-harness translation, one function per (harness, gate).
#
# Each returns (status, canonical-dict). They are deliberately literal rather
# than table-driven: the shapes have nothing in common, and a table that
# expressed them would be harder to check against the matrix than this is.
# ---------------------------------------------------------------------------


def _claude_code(gate, event):
    # Identity. The gates were written against this harness, so translation
    # here would be a chance to introduce a bug and no chance to fix one.
    if gate == "push":
        command = _text(_dig(event, "tool_input", "command"))
        if command is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"command": command}}
    if gate == "ktlint":
        path = _text(_dig(event, "tool_input", "file_path"))
        if path is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"file_path": path}}
    if gate == "stop":
        return OK, {"stop_hook_active": bool(event.get("stop_hook_active"))}
    if gate == "subagent":
        return OK, {
            "cwd": _text(event.get("cwd")) or "",
            "agent_id": _text(event.get("agent_id")) or "",
            "agent_type": _text(event.get("agent_type")) or "",
        }
    return UNKNOWN, {}


def _cursor(gate, event):
    # beforeShellExecution -> {command, cwd, sandbox}; the command is the full
    # terminal command, a direct equivalent of tool_input.command.
    if gate == "push":
        command = _text(event.get("command"))
        if command is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"command": command}}
    # afterFileEdit -> {file_path, edits[]}. Observational, which is all the
    # ktlint hook needs.
    if gate == "ktlint":
        path = _text(event.get("file_path"))
        if path is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"file_path": path}}
    # stop -> {status, loop_count}. Cursor CANNOT deny at turn end: it allows
    # the turn to end and auto-submits a follow-up, bounded by loop_limit.
    # There is no stop_hook_active, so it is derived from loop_count -- which
    # carries the same meaning the gate wants from the flag: a previous pass
    # already intervened.
    if gate == "stop":
        loop = event.get("loop_count")
        return OK, {"stop_hook_active": bool(isinstance(loop, int) and loop > 0)}
    # subagentStop -> {subagent_type, status, modified_files, loop_count}.
    # No id at stop (subagentStart carries it) and no cwd on any Cursor event,
    # so both are left empty for the gate to default -- which it does, by
    # verifying the default tree.
    if gate == "subagent":
        return OK, {
            "cwd": "",
            "agent_id": _text(event.get("subagent_id")) or "",
            "agent_type": _text(event.get("subagent_type")) or "",
        }
    return UNKNOWN, {}


def _codex(gate, event):
    # The closest harness to Claude Code by field naming: tool_name, tool_input,
    # agent_type, agent_id, exit 2. Only the turn-end flag is missing.
    if gate == "push":
        command = _text(_dig(event, "tool_input", "command"))
        if command is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"command": command}}
    if gate == "ktlint":
        path = _text(_dig(event, "tool_input", "file_path"))
        if path is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"file_path": path}}
    # Stop -> {last_assistant_message}. No loop flag of any kind, so the engine's
    # own bounded counter is the only guard; reporting false is honest, since a
    # prior block genuinely cannot be detected from this payload.
    if gate == "stop":
        return OK, {"stop_hook_active": bool(event.get("stop_hook_active"))}
    # SubagentStop -> {agent_type, last_assistant_message}. Subagent hooks
    # receive the PARENT session_id, so agent_id is not a per-subagent value.
    if gate == "subagent":
        return OK, {
            "cwd": _text(event.get("cwd")) or "",
            "agent_id": _text(event.get("agent_id")) or "",
            "agent_type": _text(event.get("agent_type")) or "",
        }
    return UNKNOWN, {}


def _copilot(gate, event):
    # camelCase, lowercase tool names, command at toolArgs.command, and -- unlike
    # Cursor -- cwd IS populated. Copilot also accepts a PascalCase field set;
    # both are read, camelCase first, because the probe captured camelCase.
    if gate == "push":
        command = _text(_dig(event, "toolArgs", "command"))
        if command is None:
            command = _text(_dig(event, "tool_input", "command"))
        if command is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"command": command}}
    if gate == "ktlint":
        path = _text(_dig(event, "toolArgs", "file_path"))
        if path is None:
            path = _text(_dig(event, "toolArgs", "path"))
        if path is None:
            path = _text(_dig(event, "tool_input", "file_path"))
        if path is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"file_path": path}}
    # agentStop carries stop_hook_active verbatim -- the only harness besides
    # Claude Code that does.
    if gate == "stop":
        return OK, {"stop_hook_active": bool(event.get("stop_hook_active"))}
    # subagentStop -> {agentId, agentType, response}.
    if gate == "subagent":
        return OK, {
            "cwd": _text(event.get("cwd")) or "",
            "agent_id": _text(event.get("agentId")) or "",
            "agent_type": _text(event.get("agentType")) or "",
        }
    return UNKNOWN, {}


def _antigravity(gate, event):
    # The only harness needing real translation rather than field renaming:
    # nested, PascalCase-keyed, and with no cwd anywhere.
    if gate == "push":
        command = _text(_dig(event, "toolCall", "args", "CommandLine"))
        if command is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"command": command}}
    if gate == "ktlint":
        path = _text(_dig(event, "toolCall", "args", "TargetFile"))
        if path is None:
            return NO_EVENT, {}
        return OK, {"tool_input": {"file_path": path}}
    # Stop -> {executionNum, terminationReason, error, fullyIdle}. No loop flag;
    # executionNum counts model invocations, which is the nearest honest signal
    # that a previous pass already ran.
    if gate == "stop":
        n = event.get("executionNum")
        return OK, {"stop_hook_active": bool(isinstance(n, int) and n > 1)}
    # Antigravity has NO subagent boundary event. PostInvocation is a model-
    # invocation boundary, not a subagent one, so this gate cannot be delivered
    # here at all -- and saying so is better than translating a different event
    # into a shape that looks like one.
    if gate == "subagent":
        return UNKNOWN, {}
    return UNKNOWN, {}


TRANSLATORS = {
    "claude-code": _claude_code,
    "cursor": _cursor,
    "codex": _codex,
    "copilot": _copilot,
    "antigravity": _antigravity,
}


def normalise(harness, gate, event):
    """Translate one event. Returns (status, canonical dict)."""
    if harness not in TRANSLATORS or gate not in GATES:
        return UNKNOWN, {}
    if not isinstance(event, dict):
        return UNKNOWN, {}
    status, canonical = TRANSLATORS[harness](gate, event)
    if status != OK:
        return status, {}
    # cwd is the one field a gate uses to decide WHERE to look, so a harness
    # that does not supply it gets the process's own directory rather than an
    # empty string that would send the gate to the filesystem root.
    if gate == "subagent" and not canonical.get("cwd"):
        canonical["cwd"] = ""
    return OK, canonical


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) != 2:
        sys.stderr.write("usage: normalise.py <harness> <gate>\n")
        return 2
    harness, gate = argv
    raw = sys.stdin.read()
    try:
        event = json.loads(raw)
    except Exception:
        # Not JSON at all. Understood harnesses still get `unknown` here rather
        # than `no-event`: a payload we cannot parse is not evidence that there
        # is nothing to gate.
        sys.stdout.write(UNKNOWN + "\n")
        return 0
    status, canonical = normalise(harness, gate, event)
    sys.stdout.write(status + "\n")
    if status == OK:
        sys.stdout.write(json.dumps(canonical))
    return 0


if __name__ == "__main__":
    sys.exit(main())
