#!/usr/bin/env python3
"""Detects a real `git push` invocation inside a Bash tool_input command.

Replaces a line-anchored `grep -E` that had two false-positive holes (see
docs/superpowers/specs/2026-08-15-verification-gate-hooks-design.md fix log,
2026-08-16 "FIX 7"):

  1. `grep -E` matches per LINE, so a `^`-anchored pattern matches the start
     of every line of a multi-line command, not just the start of the
     command — so a heredoc body writing documentation could be read as a
     fresh command.
  2. `(` was in the "this starts a new command" delimiter class, so a bare
     parenthesis inside ordinary prose (e.g. a commit message mentioning a
     push) read as a command separator.

This module fixes both by (a) stripping heredoc bodies — they are DATA, not
shell syntax — before looking at anything, and (b) tokenizing what remains
with `shlex` in POSIX mode, so a quoted argument (a commit message, a `-m`
value, an echo argument) becomes ONE token and can never be mistaken for
separate `git` and `push` words.

It also has to recognise "command position" after more than just a
punctuation boundary (`;`, `&`, `|`, `(`, `)`): a command can equally start
right after a shell keyword or a wrapper word — `if git push; then`, `{ git
push; }`, `time git push`, `nohup git push &`, `sudo git push`,
`env git push`, `timeout 5 git push`, `stdbuf -oL git push`.
`_LEADING_KEYWORDS` lists the words this module treats as "the next token
is a fresh command", so `git` immediately after one of them is still
checked.

Two more holes were found and closed by a follow-up review (2026-08-16,
review round after "FIX 7"), both in the direction that matters most for a
gate — a false NEGATIVE, a real push slipping through ungated:

  - `shlex` defaults to treating `#` as a comment starter outside quotes.
    Since a bare newline is swapped for `;` before tokenizing (see
    `_tokenize`), a `#` anywhere — even on an unrelated earlier line, e.g. a
    leading `# deploy the branch` comment — would swallow every token after
    it, including a real `git push` later on. `_tokenize` now sets
    `lexer.commenters = ""` so `#` is just an ordinary character; it is
    still inert inside quotes, same as any other character.
  - An unbalanced quote (e.g. an apostrophe in ordinary prose, `echo it's a
    plan; git push`) makes `_tokenize` unable to tokenize at all. The
    previous version treated that the same as "no push found" — safe for a
    false positive, but wrong for a gate, where a false negative (unverified
    code reaching the remote) is the failure that must not happen. On a
    tokenize failure, `is_push_command` now falls back to `_coarse_scan_gates`,
    a deliberately blunt case-insensitive "does `git` appear before `push`
    on some line" scan that GATES on a hit. Over-gating a malformed command
    is correct; silently allowing one is not.

Deliberately NOT a full shell parser. Known imperfect cases, left as-is
because they are rarer than the holes above and — for these remaining ones
specifically — the fallback direction (fail toward "not a push") is the
safer one to be wrong in for a false positive, not a false negative, because
tokenizing itself still succeeds for them (so the coarse-scan fallback above
does not apply):

  - A herestring (`<<<'...'`) whose quoted body is not a heredoc and is not
    stripped by strip_heredocs; relies on shlex's normal quote handling
    instead, which is usually but not provably correct for it.
  - `git push` written only inside a `$(...)`/backtick command substitution
    is not specially unwrapped, but is still gated as an ordinary word
    sequence (arguably correctly, since it would still execute).

Shell indirection — `sh -c '...'`, `bash -lc '...'`, `eval "..."` — WAS out
of scope and was a one-token bypass of the whole gate. `_unwrapped_shell_
payloads` now hands the payload string back to `is_push_command` (bounded
recursion), which is the right tool for it. Likewise, any unrecognised
dash-led token after `git` is now skipped as a global option rather than
read as the subcommand: `git --git-dir=.git push` and
`git --literal-pathspecs push` used to sail through ungated.
"""
import re
import shlex
import sys

# "<<[-]?['\"]?DELIM['\"]?" — the heredoc opener. `-` allows `<<-` (leading
# tabs stripped); a matching quote character, if any, must appear on both
# sides of the delimiter name.
_HEREDOC_RE = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")

_GIT_OPTION_WITH_VALUE = {"-C", "-c", "--git-dir", "--work-tree"}
_GIT_OPTION_FLAG = {"--no-pager", "-p", "--paginate"}
# shlex's punctuation_chars mode groups a run of the same punctuation char
# into one token (so `&&` and `||` arrive whole, not as two single-char
# tokens) — a boundary token is any token made up only of these chars.
_BOUNDARY_CHARS = set(";&|()")
_ENV_PREFIX_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
# Words after which the NEXT token is still "a fresh command", exactly like
# right after a boundary token — shell keywords that open a command (`if`,
# `{`, ...) and wrapper commands whose first argument is the command they
# run (`time`, `nohup`, `command`, `exec`, `xargs`, `sudo`, `env`,
# `timeout`, `stdbuf`).
_LEADING_KEYWORDS = {
    "if", "then", "elif", "else", "do", "while", "until", "{", "!",
    "time", "nohup", "command", "exec", "xargs",
    "sudo", "env", "timeout", "stdbuf",
}


def _is_boundary(token):
    return len(token) > 0 and set(token) <= _BOUNDARY_CHARS


def strip_heredocs(text):
    """Removes heredoc bodies from `text`, keeping every other line as-is.

    A heredoc body runs from the line after the opener to (and including)
    the line that is exactly the delimiter, so its content can never be
    mistaken for shell syntax by the tokenizer below.
    """
    lines = text.split("\n")
    kept = []
    index = 0
    while index < len(lines):
        line = lines[index]
        kept.append(line)
        index += 1
        match = _HEREDOC_RE.search(line)
        if not match:
            continue
        delimiter = match.group(2)
        while index < len(lines) and lines[index].strip() != delimiter:
            index += 1
        if index < len(lines):
            index += 1  # skip the terminator line itself
    return "\n".join(kept)


def _tokenize(text):
    """Shell-ish tokens, with quoted arguments collapsed to one token each.

    A bare newline is a command separator exactly like `;` (a multi-line
    Bash tool_input command is just several commands stacked on separate
    lines). It is swapped for a literal `;` before tokenizing so shlex
    treats it as a boundary — safely: shlex only honours punctuation_chars
    outside quotes, so a newline that was actually inside a quoted, multi-
    line argument stays inert once swapped, exactly as a literal `;` there
    would.

    Returns None when the text has unbalanced quotes and cannot be
    tokenized — callers must NOT treat that as "no push found" (see
    _coarse_scan_gates and its call site in is_push_command).
    """
    lexer = shlex.shlex(text.replace("\n", ";"), posix=True, punctuation_chars="();&|")
    lexer.whitespace_split = True
    # shlex defaults to treating "#" as a comment starter outside quotes.
    # With every newline already collapsed to ";" above, an UNRELATED "#"
    # anywhere earlier in the command — a leading comment line is ordinary
    # multi-line shell — would silently swallow every token after it,
    # including a real `git push` later on. Disabling comments entirely is
    # safe: a "#" inside quotes was already inert, and outside quotes it is
    # just an ordinary word character to every command this hook cares about.
    lexer.commenters = ""
    try:
        return list(lexer)
    except ValueError:
        return None


_COARSE_GIT_PUSH_RE = re.compile(r"\bgit\b.*\bpush\b", re.IGNORECASE)


def _coarse_scan_gates(text):
    """Best-effort fallback used ONLY when _tokenize returns None.

    Deliberately blunt: a case-insensitive "git appears before push on the
    same line" scan, with no understanding of quoting, options, or command
    position at all. That is the correct trade for this call site — the
    input already defeated real tokenizing (e.g. an unbalanced quote from
    an apostrophe in prose), so precision is not available here, and for a
    push gate the failure that must never happen is a false negative. A
    false positive just means an unrelated command gets (harmlessly)
    treated as a push and run through the real verification gates.
    """
    return any(_COARSE_GIT_PUSH_RE.search(line) for line in text.split("\n"))


_LINE_CONTINUATION_RE = re.compile(r"\\\n")


def _is_git_push_at(tokens, index):
    """True when tokens[index] is `git` (or a path-qualified git) and the
    next non-global-option word is `push`."""
    program = tokens[index]
    if program != "git" and not program.endswith("/git"):
        return False
    cursor = index + 1
    while cursor < len(tokens):
        word = tokens[cursor]
        if word in _GIT_OPTION_WITH_VALUE:
            cursor += 2
        elif word in _GIT_OPTION_FLAG or word.startswith("-"):
            # Any other dash-led token is a global option this module does
            # not know by name. Skipping it is the safe reading: treating it
            # as the subcommand made `git --git-dir=.git push`,
            # `git --literal-pathspecs push` and every future git option read
            # as "not a push" and sail through ungated.
            cursor += 1
        else:
            break
    return cursor < len(tokens) and tokens[cursor] == "push"


_SHELL_WRAPPERS = {"sh", "bash", "zsh", "dash", "ksh"}


def _unwrapped_shell_payloads(tokens):
    """Command strings handed to `sh -c`, `bash -lc`, `eval`, and friends.

    The push is one level of interpretation away in these, so the outer
    tokenization sees `sh`, `-c`, and one opaque string. That read as "not a
    push" and sailed straight through the gate. Rather than try to interpret
    arbitrary shell, this hands the payload back to `is_push_command`, which
    is exactly the right tool for it.
    """
    payloads = []
    for index, token in enumerate(tokens):
        program = token.rsplit("/", 1)[-1]
        if program not in _SHELL_WRAPPERS and program != "eval":
            continue
        for candidate in tokens[index + 1:]:
            if candidate.startswith("-"):
                continue  # -c, -lc, -e ... the payload is the next word
            payloads.append(candidate)
            break
    return payloads


def is_push_command(text, _depth=0):
    """True when `text` contains a real, executable `git push` invocation."""
    # A trailing backslash-newline is Bash line continuation — it joins two
    # physical lines into one logical command (e.g. `git \` / `    push`),
    # so it must be collapsed BEFORE a bare newline is treated as a command
    # separator, or a continued `git push` reads as two harmless commands.
    joined = _LINE_CONTINUATION_RE.sub(" ", text)
    stripped = strip_heredocs(joined)
    tokens = _tokenize(stripped)
    if tokens is None:
        return _coarse_scan_gates(stripped)

    # Bounded so a pathological `sh -c "sh -c ..."` nest cannot recurse away.
    if _depth < 3:
        for payload in _unwrapped_shell_payloads(tokens):
            if is_push_command(payload, _depth + 1):
                return True

    # A single left-to-right scan tracking whether we are in "command
    # position" — true at the start, right after a boundary token, right
    # after an env-var prefix, and right after a leading keyword — is
    # simpler and more correct than splitting into fixed "commands" first,
    # since a keyword can open a fresh command without any punctuation
    # boundary at all (`if git push; then ...`).
    at_command_start = True
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if _is_boundary(token):
            at_command_start = True
            index += 1
            continue
        if not at_command_start:
            index += 1
            continue
        if _ENV_PREFIX_RE.match(token) or token in _LEADING_KEYWORDS:
            index += 1  # still command position for the next token
            continue
        if _is_git_push_at(tokens, index):
            return True
        at_command_start = False
        index += 1
    return False


_SKIP_FLAGS = {"--delete", "-d", "--tags", "--dry-run", "-n"}


def classify(text):
    """"no" | "skip <reason>" | "verify <src-ref>".

    The gate used to run the full pipeline for anything containing `push`,
    and always verified `@{upstream}..HEAD` regardless of what was actually
    being pushed. So `git push --delete origin old-branch` paid for a full
    instrumentation run, and `git push origin master` from a feature branch
    verified the feature branch's commits rather than master's.
    """
    if not is_push_command(text):
        return "no"

    joined = _LINE_CONTINUATION_RE.sub(" ", text)
    tokens = _tokenize(strip_heredocs(joined))
    if tokens is None:
        return "verify "  # unparseable: verify the current branch, the safe default

    try:
        start = tokens.index("push")
    except ValueError:
        return "verify "

    positional = []
    for token in tokens[start + 1:]:
        if _is_boundary(token):
            break
        if token in _SKIP_FLAGS:
            return "skip %s ships no new commits from this branch" % token
        if token.startswith("-"):
            continue
        positional.append(token)

    # `git push [remote] [refspec]`. A refspec whose source side is empty is
    # the delete form (`origin :refs/tags/v1`).
    refspec = positional[1] if len(positional) > 1 else ""
    if refspec.startswith(":"):
        return "skip a delete refspec ships no new commits"
    source_ref = refspec.split(":", 1)[0] if refspec else ""
    return "verify %s" % source_ref


def main():
    text = sys.stdin.read()
    if len(sys.argv) > 1 and sys.argv[1] == "classify":
        print(classify(text))
        return 0
    print("yes" if is_push_command(text) else "no")
    return 0


if __name__ == "__main__":
    sys.exit(main())
