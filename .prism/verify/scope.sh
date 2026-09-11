#!/bin/sh
# prism-verify scope — which modules does the current chunk of work touch?
#
# READ-ONLY BY CONSTRUCTION, on the same terms as status.sh:
#
#   * it reports and exits 0. It is not a gate, it has no allow path, and
#     there is nothing here to weaken.
#   * NO HOOK MAY CALL IT. A gate that derives its scope from a reporting
#     script is a gate whose scope can be changed by editing that script.
#     stop-gate.sh and push-gate.sh each derive their own, from the same
#     library functions this calls.
#   * it runs no Gradle task. It reads git and settings.gradle.kts.
#
# Why it exists: /finalize needs the same two lists the gates derive, and the
# only way to get them was to source lib/affected.sh and call the helpers by
# hand. That library is POSIX-sh code — `prism_with_dependents $modules`
# needs the caller to word-split an unquoted expansion, and zsh does not,
# so every caller outside `sh` had to wrap itself in a heredoc to work at
# all. Boilerplate in a skill is boilerplate that drifts. status.sh already
# solved the same problem for coverage by being a script you call with plain
# arguments; this is that, for scope.
#
# Output is two labelled lines, space-separated, stable enough to grep:
#
#   MODULES :core:database :feature:login:data
#   SCOPE :app :core:database :feature:login:data :feature:login:presentation
#
# MODULES is what CHANGED — the right scope for coverage, since a module's
# number is a property of its own code. SCOPE is what the change REACHES:
# Gradle builds a module's dependencies, never its dependents, so compiling
# only the changed modules proves nothing about the consumers a signature
# change just broke.
#
# Usage: sh .prism/verify/scope.sh [--base=<ref>]
set -u

HOOK_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$HOOK_DIR/lib/affected.sh"

for arg in "$@"; do
    case "$arg" in
        --base=*) PRISM_BASE_BRANCH=${arg#--base=} ;;
        *)
            printf 'scope.sh: unrecognized argument [%s]\n' "$arg" >&2
            printf 'usage: sh scope.sh [--base=<ref>]\n' >&2
            exit 0
            ;;
    esac
done

prism_require_root
if [ "$?" -ne 0 ]; then
    printf 'ERROR no usable repository root (PRISM_ROOT=[%s])\n' "$PRISM_ROOT"
    exit 0
fi

if ! prism_require_base_branch 2>/dev/null; then
    printf 'ERROR no base branch configured [%s], and --base= was not given\n' \
        "$(prism_config_path)"
    exit 0
fi

if ! changed=$(prism_changed_files_stop); then
    printf 'ERROR could not resolve base branch [%s] or origin/%s\n' \
        "$PRISM_BASE_BRANCH" "$PRISM_BASE_BRANCH"
    exit 0
fi

# A convention plugin, the version catalog or a root build file belongs to no
# module while changing how every module builds. Deriving an empty list there
# is how a caller ends up verifying nothing on the widest-blast-radius change.
if printf '%s\n' "$changed" | prism_has_global_build_change; then
    modules=$(prism_all_modules)
else
    modules=$(printf '%s\n' "$changed" | prism_modules_for_files)
fi

if [ -z "$modules" ]; then
    printf 'MODULES\n'
    printf 'SCOPE\n'
    exit 0
fi

# shellcheck disable=SC2086
if ! scope=$(prism_with_dependents $modules); then
    printf 'MODULES %s\n' "$(printf '%s' "$modules" | tr '\n' ' ')"
    printf 'ERROR could not read the module dependency graph (lib/deps.py)\n'
    exit 0
fi

printf 'MODULES %s\n' "$(printf '%s' "$modules" | tr '\n' ' ')"
printf 'SCOPE %s\n' "$(printf '%s' "$scope" | tr '\n' ' ')"

# Always. This script has no verdict to express through an exit status, and
# giving it one would be the first step towards something calling it as a gate.
exit 0
