"""Tests for lib/push_detect.py. Run: python3 tests/test_push_detect.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
import push_detect  # noqa: E402


class MustStayGatedTests(unittest.TestCase):
    """Every form Task 4 already proved must still deny the push."""

    def test_bare_push(self):
        self.assertTrue(push_detect.is_push_command("git push"))

    def test_push_with_remote_and_branch(self):
        self.assertTrue(push_detect.is_push_command("git push origin main"))

    def test_dash_capital_c_global_option(self):
        self.assertTrue(push_detect.is_push_command("git -C /tmp/repo push"))

    def test_no_pager_global_option(self):
        self.assertTrue(push_detect.is_push_command("git --no-pager push"))

    def test_env_var_prefix(self):
        self.assertTrue(push_detect.is_push_command("GIT_DIR=/foo git push"))

    def test_chained_after_cd(self):
        self.assertTrue(push_detect.is_push_command("cd /x && git push"))

    def test_followed_by_another_command(self):
        self.assertTrue(push_detect.is_push_command("git push; echo hi"))


class SubshellAndOperatorTests(unittest.TestCase):
    """Every subshell/operator form a reviewer independently verified gated."""

    def test_bare_parenthesised_push(self):
        self.assertTrue(push_detect.is_push_command("(git push)"))

    def test_spaced_parenthesised_push(self):
        self.assertTrue(push_detect.is_push_command("( git push )"))

    def test_subshell_after_cd(self):
        self.assertTrue(push_detect.is_push_command("(cd /x && git push)"))

    def test_subshell_after_and_operator(self):
        self.assertTrue(push_detect.is_push_command("x && (git push)"))

    def test_command_substitution(self):
        self.assertTrue(push_detect.is_push_command("$(git push)"))

    def test_backgrounded_push(self):
        self.assertTrue(push_detect.is_push_command("git push &"))

    def test_push_before_or_operator(self):
        self.assertTrue(push_detect.is_push_command("git push || echo failed"))


class KeywordCommandPositionTests(unittest.TestCase):
    """FIX 7 follow-up: a command can start right after a shell keyword or
    wrapper word too, not only after punctuation. All four were confirmed
    false negatives against the version of push_detect.py that only looked
    at command[0] after a punctuation boundary."""

    def test_brace_group(self):
        self.assertTrue(push_detect.is_push_command("{ git push; }"))

    def test_if_then(self):
        self.assertTrue(push_detect.is_push_command("if git push; then echo ok; fi"))

    def test_for_do_loop_body(self):
        self.assertTrue(push_detect.is_push_command("for i in 1; do git push; done"))

    def test_time_prefix(self):
        self.assertTrue(push_detect.is_push_command("time git push"))

    def test_nohup_prefix(self):
        self.assertTrue(push_detect.is_push_command("nohup git push &"))

    def test_keyword_handling_does_not_create_new_false_positives(self):
        # None of "about"/"to"/"later" are leading keywords, so this must
        # stay exactly as safe as it was before keyword handling existed.
        self.assertFalse(push_detect.is_push_command("echo about to git push later"))
        # A prose sentence that happens to contain a keyword-ish word must
        # still not fire: "do" here is a plain English word inside a quoted
        # argument, not a shell keyword in command position.
        self.assertFalse(push_detect.is_push_command(
            'echo "what to do about a future git push"'))


class MustNeverBeGatedTests(unittest.TestCase):
    """Every prose/lookalike form Task 4 already proved must not fire."""

    def test_mention_in_prose(self):
        self.assertFalse(push_detect.is_push_command("echo about to git push later"))

    def test_lookalike_subcommand(self):
        self.assertFalse(push_detect.is_push_command("git pushx --weird"))

    def test_unrelated_npm_script(self):
        self.assertFalse(push_detect.is_push_command("npm run pushish"))

    def test_unrelated_command(self):
        self.assertFalse(push_detect.is_push_command("ls -la"))

    def test_git_status(self):
        self.assertFalse(push_detect.is_push_command("git status"))


class FalseNegativeRegressionTests(unittest.TestCase):
    """Second review round (2026-08-16, after FIX 7 and the keyword fix):
    five confirmed false negatives, all in the dangerous direction — a real
    push reaching the remote ungated. N1-N4 below match the review's
    numbering."""

    # N1: an unquoted "#" must not disable detection for the rest of the
    # command. shlex defaults to treating "#" as a comment starter, and
    # since a bare newline is swapped for ";" before tokenizing, a comment
    # on ANY earlier line used to swallow everything after it, including a
    # real `git push` further down.
    def test_leading_comment_line_does_not_hide_a_later_push(self):
        self.assertTrue(push_detect.is_push_command("# deploy the branch\ngit push"))

    def test_trailing_comment_on_an_earlier_line_does_not_hide_a_later_push(self):
        self.assertTrue(push_detect.is_push_command("git status # later\ngit push"))

    def test_trailing_comment_with_no_space_does_not_hide_a_later_push(self):
        self.assertTrue(push_detect.is_push_command(
            "echo hi #note\ngit push origin master"))

    def test_hash_inside_a_quoted_argument_is_still_inert(self):
        # "#" was already safe inside quotes before this fix; pin it stays
        # that way now that lexer.commenters is disabled globally.
        command = 'echo "release #42 notes"; git push'
        self.assertTrue(push_detect.is_push_command(command))

    # N2: an unbalanced quote (e.g. an apostrophe in prose) defeats real
    # tokenizing entirely. The old behaviour treated that as "no push
    # found" — safe for a false positive, wrong for a gate. It must now
    # fall back to a coarse scan that GATES on a hit.
    def test_unbalanced_apostrophe_does_not_hide_a_push(self):
        self.assertTrue(push_detect.is_push_command("echo it's a plan; git push"))

    def test_unbalanced_quote_fallback_only_gates_when_push_is_present(self):
        # The coarse fallback must not gate EVERY malformed command — only
        # ones that actually mention git ... push.
        self.assertFalse(push_detect.is_push_command("echo it's just a note"))

    def test_unbalanced_quote_fallback_requires_git_before_push(self):
        # "push" appearing before "git" on the coarse scan must not match
        # (mirrors the tokenizer's own ordering requirement).
        self.assertFalse(push_detect.is_push_command("echo push it's fine; git status"))

    # N4: sudo/env/timeout/stdbuf are wrapper commands whose first argument
    # is the command they actually run, exactly like nohup/time/exec.
    def test_sudo_prefix(self):
        self.assertTrue(push_detect.is_push_command("sudo git push"))

    def test_env_prefix(self):
        self.assertTrue(push_detect.is_push_command("env git push"))

    def test_timeout_prefix(self):
        self.assertTrue(push_detect.is_push_command("timeout git push"))

    def test_stdbuf_prefix(self):
        self.assertTrue(push_detect.is_push_command("stdbuf git push"))

    # Guard: none of this must reopen the FIX 7 false positives.
    def test_heredoc_prose_regression_guard_still_safe(self):
        command = (
            "cat >> notes.md <<'EOF'\n"
            "Remember: after review (git push) to the remote once approved.\n"
            "EOF\n"
            "echo done"
        )
        self.assertFalse(push_detect.is_push_command(command))

    def test_commit_message_regression_guard_still_safe(self):
        command = 'git notarealcommit -m "please (git push) after merge"'
        self.assertFalse(push_detect.is_push_command(command))

    def test_prose_mention_regression_guard_still_safe(self):
        self.assertFalse(push_detect.is_push_command("echo about to git push later"))


class FalsePositiveRegressionTests(unittest.TestCase):
    """FIX 7: the specific cases that fired in production."""

    def test_heredoc_body_mentioning_a_push_in_prose(self):
        command = (
            "cat >> notes.md <<'EOF'\n"
            "Remember: after review (git push) to the remote once approved.\n"
            "More docs live here.\n"
            "EOF\n"
            "echo done"
        )
        self.assertFalse(push_detect.is_push_command(command))

    def test_heredoc_prose_does_not_hide_a_real_push_after_it(self):
        command = (
            "cat >> notes.md <<'EOF'\n"
            "(git push) mentioned in prose\n"
            "EOF\n"
            "git push"
        )
        self.assertTrue(push_detect.is_push_command(command))

    def test_commit_message_mentioning_a_push_in_parentheses(self):
        command = 'git commit -m "please (git push) after merge"'
        self.assertFalse(push_detect.is_push_command(command))

    def test_double_quoted_heredoc_delimiter(self):
        command = 'cat <<"DONE"\n(git push) prose\nDONE'
        self.assertFalse(push_detect.is_push_command(command))

    def test_dash_heredoc_variant_with_leading_tabs(self):
        command = "cat <<-EOF\n\t(git push) prose\n\tEOF"
        self.assertFalse(push_detect.is_push_command(command))


class OtherEdgeCaseTests(unittest.TestCase):
    def test_line_continuation_still_gates_a_split_push(self):
        # `git \` / `push` is ONE logical command in real Bash.
        self.assertTrue(push_detect.is_push_command("git \\\npush"))

    def test_multiline_command_with_push_on_its_own_line(self):
        command = "echo hi\ngit push"
        self.assertTrue(push_detect.is_push_command(command))

    def test_unbalanced_quotes_do_not_crash_and_default_to_no_match(self):
        self.assertFalse(push_detect.is_push_command('echo "unterminated'))

    def test_path_qualified_git_binary(self):
        self.assertTrue(push_detect.is_push_command("/usr/bin/git push"))

    def test_empty_string(self):
        self.assertFalse(push_detect.is_push_command(""))


class StripHeredocsTests(unittest.TestCase):
    def test_body_removed_terminator_removed(self):
        command = "cat <<'EOF'\nbody line\nEOF\necho after"
        self.assertEqual(
            "cat <<'EOF'\necho after",
            push_detect.strip_heredocs(command),
        )

    def test_no_heredoc_is_a_no_op(self):
        command = "git push origin main"
        self.assertEqual(command, push_detect.strip_heredocs(command))


class MigratedFromTheGateSuiteTests(unittest.TestCase):
    """Every command form tests/test_push_gate.sh used to assert end to end.

    Those assertions drove push-gate.sh with PRISM_SKIP_GATE3=1, which is
    why that switch existed in the shipped hook at all: they only ever asked
    "is this a push?", but the only way to ask push-gate.sh that question
    without paying for a Gradle build was to turn the coverage gate off in
    production code.

    Detection already lives in an importable module that takes a command
    string, so the question is asked here instead, and the switch is gone.
    Every form is restated explicitly rather than assumed covered by a
    similar case above — the point of the migration is that no form is lost.
    """

    GATED = [
        "git push",
        "git push origin HEAD",
        "git push -u origin my-branch",
        "git -C /tmp/repo push",
        "git --no-pager push",
        "GIT_DIR=/foo git push",
        "{ git push; }",
        "if git push; then echo ok; fi",
        "for i in 1; do git push; done",
        "time git push",
        "# deploy the branch\ngit push",
        "git status # later\ngit push",
        "echo hi #note\ngit push origin master",
        "echo it's a plan; git push",
        "sudo git push",
        "env git push",
        "timeout git push",
        "stdbuf git push",
        # A real push after a heredoc whose body only mentions one.
        "cat >> notes.md <<'EOF'\n(git push) mentioned in prose\nEOF\ngit push",
    ]

    SAFE = [
        "ls -la",
        "git status",
        "./gradlew build",
        "npm run pushish",
        "echo about to git push later",
        "git pushx --weird",
        "cat notes-git-push.txt",
        # A heredoc writing prose that merely mentions a push.
        "cat >> notes.md <<'EOF'\n"
        "Remember: after review (git push) to the remote once approved.\n"
        "EOF\necho done",
        # A parenthesised mention inside a quoted argument must not read as a
        # command boundary.
        'git notarealcommit -m "please (git push) after merge"',
    ]


def _form_test(command, expected):
    def test(self):
        self.assertEqual(expected, push_detect.is_push_command(command))
    return test


def _attach_form_tests():
    """One test method per migrated form, not one per list.

    A single method looping with subTest would report the whole migration as
    two tests, which would make the suite's assertion count go DOWN even
    though nothing was dropped — and that count is what this change promises
    not to lower.
    """
    cases = [("gated", command, True) for command in MigratedFromTheGateSuiteTests.GATED]
    cases += [("safe", command, False) for command in MigratedFromTheGateSuiteTests.SAFE]
    for index, (group, command, expected) in enumerate(cases):
        name = "test_%s_form_%02d" % (group, index)
        method = _form_test(command, expected)
        method.__doc__ = "%s: %r" % (group, command)
        setattr(MigratedFromTheGateSuiteTests, name, method)


_attach_form_tests()



class ShellWrapperTests(unittest.TestCase):
    """`sh -c 'git push'` was a one-token bypass of the entire gate."""

    def test_sh_dash_c(self):
        self.assertTrue(push_detect.is_push_command("sh -c 'git push'"))

    def test_bash_login_shell(self):
        self.assertTrue(push_detect.is_push_command('bash -lc "git push"'))

    def test_eval(self):
        self.assertTrue(push_detect.is_push_command('eval "git push"'))

    def test_absolute_path_shell(self):
        self.assertTrue(push_detect.is_push_command("/bin/sh -c 'git push'"))

    def test_wrapper_without_a_push_is_still_no(self):
        self.assertFalse(push_detect.is_push_command('sh -c "echo hello"'))

    def test_nested_wrappers_terminate(self):
        nested = 'sh -c ' + repr('bash -c "git push"')
        self.assertTrue(push_detect.is_push_command(nested))


class GitGlobalOptionTests(unittest.TestCase):
    """Any unrecognised dash-led token is a global option, not the subcommand."""

    def test_git_dir_equals_form(self):
        self.assertTrue(push_detect.is_push_command("git --git-dir=.git push"))

    def test_work_tree_equals_form(self):
        self.assertTrue(push_detect.is_push_command(
            "git --work-tree=. --git-dir=.git push"))

    def test_unknown_global_option(self):
        self.assertTrue(push_detect.is_push_command("git --literal-pathspecs push"))

    def test_separate_value_form_still_works(self):
        self.assertTrue(push_detect.is_push_command("git -C /repo push"))

    def test_a_non_push_subcommand_is_still_no(self):
        self.assertFalse(push_detect.is_push_command("git --literal-pathspecs status"))


class ClassifyTests(unittest.TestCase):
    """Refspec awareness: what is actually being pushed, and is it worth it."""

    def test_plain_push_verifies_current_branch(self):
        self.assertEqual(push_detect.classify("git push"), "verify ")

    def test_named_source_ref_is_reported(self):
        self.assertEqual(push_detect.classify("git push origin master"),
                         "verify master")

    def test_colon_refspec_uses_the_source_side(self):
        self.assertEqual(push_detect.classify("git push origin HEAD:other"),
                         "verify HEAD")

    def test_delete_is_skipped(self):
        self.assertTrue(push_detect.classify(
            "git push --delete origin old").startswith("skip"))

    def test_tags_only_is_skipped(self):
        self.assertTrue(push_detect.classify("git push --tags").startswith("skip"))

    def test_dry_run_is_skipped(self):
        self.assertTrue(push_detect.classify("git push --dry-run").startswith("skip"))

    def test_delete_refspec_is_skipped(self):
        self.assertTrue(push_detect.classify(
            "git push origin :refs/tags/v1").startswith("skip"))

    def test_non_push_is_no(self):
        self.assertEqual(push_detect.classify("git status"), "no")

    def test_skip_only_applies_to_the_push_command(self):
        # A --tags belonging to a DIFFERENT command must not exempt the push.
        self.assertTrue(push_detect.classify(
            "git tag --list; git push origin master").startswith("verify"))

if __name__ == "__main__":
    unittest.main()
