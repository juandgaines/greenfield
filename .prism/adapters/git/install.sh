#!/bin/sh
# Installs the enforcement floor at .git/hooks/pre-push.
#
# Usage: sh install.sh [<repository-root>]
#
# Three properties, and each of them is a decision rather than a nicety:
#
#   It never overwrites a hook it did not write. A pre-push hook is somebody
#   else's enforcement — a secret scanner, a commit-message check, a CI
#   trigger — and silently replacing it would make prism-verify the reason
#   their protection stopped running. The collision is reported and the
#   installer fails; composing the two is the repository owner's call, not
#   an installer's guess.
#
#   It is idempotent. Running it twice reports no changes rather than
#   reinstalling, because a setup that cannot be re-run is a setup nobody
#   re-runs after an upgrade.
#
#   It refuses rather than half-installs. Every failure below exits non-zero
#   with the reason, and a caller that treats a non-zero status as "already
#   fine" gets an unprotected repository, which is the one outcome this
#   whole framework exists to prevent.
set -u

MARKER='PRISM_ADAPTER_MARKER: prism-verify/adapters/git/pre-push'

adapter_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source_hook="$adapter_dir/pre-push"

root="${1:-}"
if [ -z "$root" ]; then
    root=$(git rev-parse --show-toplevel 2>/dev/null) || root=""
fi
if [ -z "$root" ] || [ ! -d "$root" ]; then
    printf 'prism-verify: not a git repository, and no root was given.\n' >&2
    printf 'usage: sh install.sh [<repository-root>]\n' >&2
    exit 1
fi

if [ ! -f "$source_hook" ]; then
    printf 'prism-verify: no pre-push adapter at %s\n' "$source_hook" >&2
    exit 1
fi

# --git-path, not .git/hooks. A worktree's .git is a FILE pointing elsewhere,
# and a repository can set core.hooksPath to somewhere else entirely; building
# the path by hand installs the floor where git will never look for it, which
# looks exactly like a successful install.
hooks_dir=$(git -C "$root" rev-parse --git-path hooks 2>/dev/null) || hooks_dir=""
if [ -z "$hooks_dir" ]; then
    printf 'prism-verify: git could not say where hooks live in %s\n' "$root" >&2
    exit 1
fi
case "$hooks_dir" in
    /*) ;;
    *) hooks_dir="$root/$hooks_dir" ;;
esac

target="$hooks_dir/pre-push"

if [ -e "$target" ] || [ -L "$target" ]; then
    if grep -q "$MARKER" "$target" 2>/dev/null; then
        if cmp -s "$source_hook" "$target"; then
            printf 'prism-verify: pre-push already installed at %s — no changes.\n' \
                "$target"
            exit 0
        fi
        cp "$source_hook" "$target" || exit 1
        chmod +x "$target" || exit 1
        printf 'prism-verify: pre-push updated at %s\n' "$target"
        exit 0
    fi

    printf 'prism-verify: a pre-push hook is already installed and was NOT replaced.\n\n' >&2
    printf '  %s\n\n' "$target" >&2
    printf 'It was not written by prism-verify, so something else is enforcing\n' >&2
    printf 'something on push here. Overwriting it would make this framework the\n' >&2
    printf 'reason that protection stopped running.\n\n' >&2
    printf 'Chain them yourself — call\n' >&2
    printf '  %s\n' "$source_hook" >&2
    printf 'from the existing hook, passing its arguments and stdin through — or\n' >&2
    printf 'move the existing hook aside and run this again.\n' >&2
    exit 1
fi

mkdir -p "$hooks_dir" || exit 1
cp "$source_hook" "$target" || exit 1
chmod +x "$target" || exit 1
printf 'prism-verify: pre-push installed at %s\n' "$target"
