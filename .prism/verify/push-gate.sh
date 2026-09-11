#!/bin/sh
# PreToolUse(Bash) hook — the harness entry point to the push gate.
#
# This half answers one question: is the command in this event a `git push`, and
# which ref is it sending? Everything that follows from a yes lives in
# push-verify.sh, which takes a ref rather than an event and is reachable with
# nothing on stdin.
#
# The split is what makes .git/hooks/pre-push possible without a shim. Git
# already knows the command is a push and which refs it carries, so the floor
# calls the verification half directly and constructs no synthetic event to
# satisfy a parser written for a different caller. See push-verify.sh's header
# for why that path is the primary enforcement on two of the five supported
# harnesses rather than a fallback.
#
# Exit 0 = allow the push. Exit 2 = deny, reason on stderr.
set -u

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"

input=$(cat)

# The extraction is sentinel-tagged so that "python3 told us this event has
# no command" and "python3 could not run at all" stay distinguishable.
#
# This used to print "" for both and exit 0 on an empty result, which meant a
# broken or missing python3 allowed EVERY push through, ungated and silently
# — the same class of failure the push_detect guard below is written to
# prevent, on the line above it. An interpreter that cannot be asked the
# question must deny, exactly as it does there.
extraction=$(printf '%s' "$input" | python3 -c '
import sys, json
try:
    data = json.load(sys.stdin)
    command = data.get("tool_input", {}).get("command", "")
except Exception:
    # Learned by RUNNING python3 successfully: this event is not JSON we
    # understand, so there is no command to gate and allowing is correct.
    print("NOEVENT")
else:
    print("OK" + (command if isinstance(command, str) else ""))
' 2>/dev/null)
extraction_status=$?

if [ "$extraction_status" -ne 0 ] || [ -z "$extraction" ]; then
    printf 'PUSH DENIED — could not read the Bash event.\n\n' >&2
    printf 'python3 exited %s while extracting tool_input.command, so this hook\n' \
        "$extraction_status" >&2
    printf 'cannot tell whether the command is a push. It was NOT verified as safe.\n' >&2
    printf 'Fix python3, then retry.\n' >&2
    exit 2
fi

case "$extraction" in
    NOEVENT) exit 0 ;;
    OK*) command_line=${extraction#OK} ;;
    *)
        printf 'PUSH DENIED — event extraction returned unexpected output.\n\n' >&2
        printf 'This command was NOT verified as safe to push.\n' >&2
        exit 2
        ;;
esac

if [ -z "$command_line" ]; then
    exit 0
fi

# Only a real `git push` invocation is gated: lib/push_detect.py tokenizes
# the command (stripping heredoc bodies first) so a mere mention inside an
# echo, a commit message, or documentation written by a heredoc cannot be
# mistaken for one; and it accepts git's global options (-C, -c, --git-dir,
# --work-tree, --no-pager, -p) and env-var prefixes so those forms cannot
# slip past ungated. See push_detect.py's docstring for the cases this
# still cannot perfectly resolve.
#
# The exit status is checked separately from the "yes"/"no" output: if the
# detector itself cannot run (python3 missing or broken, the module raising)
# that must NEVER be read as "not a push" — this hook exists specifically to
# catch pushes, so an inability to even ask the question is denied loudly,
# not allowed silently.
if ! is_push=$(printf '%s' "$command_line" | python3 "$HOOK_DIR/lib/push_detect.py" classify 2>&1); then
    printf 'PUSH DENIED — push detection could not run.\n\n' >&2
    printf '%s\n' "$is_push" >&2
    printf '\nThis command was NOT verified as safe to push. Fix python3 or\n' >&2
    printf 'lib/push_detect.py, then retry.\n' >&2
    exit 2
fi
push_ref=""
case "$is_push" in
    no) exit 0 ;;
    skip\ *)
        # --delete / --tags / --dry-run / a delete refspec. These ship no new
        # commits from this branch, so an hour of instrumentation would verify
        # nothing relevant to what is actually being sent.
        exit 0
        ;;
    verify) ;;
    verify\ *) push_ref=${is_push#verify } ;;
    *)
        printf 'PUSH DENIED — push detection returned unexpected output: %s\n\n' "$is_push" >&2
        printf 'This command was NOT verified as safe to push. Fix\n' >&2
        printf 'lib/push_detect.py, then retry.\n' >&2
        exit 2
        ;;
esac

# Handing over. The verdict, the exit status and every denial message are the
# verification half's; nothing is re-interpreted here, so the harness path and
# the git-hook path cannot drift into disagreeing about the same repository.
exec sh "$HOOK_DIR/push-verify.sh" "$push_ref"
