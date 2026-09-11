#!/usr/bin/env python3
"""Tests for the one command that carries a human from artifact to install.

Everything here was verified by hand first, on a real terminal, against the real
artifact. This file exists because that is not a thing anyone will do again, and
the two defects it pins are exactly the kind that survive by never being checked:

  1. WITH SEVERAL AGENT CLIS INSTALLED, bootstrap refused to guess and EXITED 0
     having written nothing -- so `prism-verify setup` reported success over an
     empty install, on every machine that has more than one agent on it.
  2. THE LAST LINE OF THE INSTALL WAS HARDCODED `/prism-setup` FOR ALL FIVE
     HARNESSES, which is wrong for Codex ($name) and wrong for Copilot (no
     per-skill command exists at all). The hand-off dead-ended on its final
     instruction, on the harnesses nobody had walked by hand.

A real pty is used where a real pty is what is under test: whether it ASKS, and
whether `exec` hands the terminal to the agent. Neither can be observed through a
pipe, because both are gated on `[ -t 0 ]` -- which is itself the behaviour that
keeps this from hanging a CI job.

PATH is replaced with stubs in every test. Reading the machine's real CLIs would
make the result depend on what the person running the suite happens to have
installed, and the ambiguous case would not exist on a machine with one agent.
"""

import json
import os
import pty
import select
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
BOOTSTRAP = os.path.join(ROOT, "setup", "bootstrap.sh")
MANIFEST = os.path.join(ROOT, "core", "adapters", "harnesses.json")

with open(MANIFEST, encoding="utf-8") as handle:
    ROWS = json.load(handle)["harnesses"]


def resolved_invoke(harness, name="prism-setup"):
    return ROWS[harness]["invoke"].replace("{name}", name)


class BootstrapTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prism-bootstrap-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.repo = os.path.join(self.tmp, "repo")
        os.makedirs(self.repo)
        subprocess.run(["git", "init", "-q", self.repo], check=True)
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)

    def stub(self, *harnesses):
        """Put a fake CLI on PATH for each harness, echoing the argv it got."""
        for harness in harnesses:
            cli = ROWS[harness]["cli"]
            path = os.path.join(self.bin, cli)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(
                    "#!/bin/sh\nprintf 'STUBARGV'\n"
                    'for a in "$@"; do printf "|%s" "$a"; done\n'
                    "printf '\\n'\n")
            os.chmod(path, 0o755)

    def env(self):
        environment = dict(os.environ)
        # Only the stubs, plus what the script itself needs.
        environment["PATH"] = self.bin + ":/usr/bin:/bin:/usr/sbin:/sbin"
        return environment

    def run_piped(self, *args):
        """No terminal. stdin is /dev/null, so `[ -t 0 ]` is false."""
        proc = subprocess.run(
            ["sh", BOOTSTRAP, *args], cwd=self.repo, env=self.env(),
            stdin=subprocess.DEVNULL, capture_output=True, text=True)
        return proc.returncode, proc.stdout + proc.stderr

    def run_pty(self, *args, answers=()):
        """A real terminal, so it may ask and may exec."""
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(self.repo)
            os.environ.clear()
            os.environ.update(self.env())
            os.execvp("sh", ["sh", BOOTSTRAP, *args])
        out, pending = b"", list(answers)
        deadline = time.time() + 30
        while time.time() < deadline:
            ready, _, _ = select.select([fd], [], [], 0.3)
            if ready:
                try:
                    chunk = os.read(fd, 4096)
                except OSError:
                    break
                if not chunk:
                    break
                out += chunk
            if pending and b"[1-" in out.split(b"\n")[-1]:
                os.write(fd, pending.pop(0).encode())
        try:
            _, status = os.waitpid(pid, 0)
            code = os.waitstatus_to_exitcode(status)
        except ChildProcessError:
            code = None
        os.close(fd)
        return code, out.decode(errors="replace")

    def wrapper_path(self, harness):
        return os.path.join(self.repo, ROWS[harness]["wrapper_dest"])

    def handoff_invoke(self, out, harness):
        """The invocation as PRINTED, not merely present somewhere in the output.

        A substring check is not good enough here: `/prism-setup` occurs in the
        installed path `.agents/skills/prism-setup/SKILL.md` on three harnesses,
        so "does the output contain /prism-setup" is true even when the hand-off
        correctly printed something else.
        """
        marker = "Next, in %s:" % harness
        self.assertIn(marker, out, "no hand-off line was printed")
        tail = out.split(marker, 1)[1]
        for line in tail.splitlines():
            if line.strip():
                return line.strip()
        self.fail("the hand-off line was empty")

    def assertNothingInstalled(self):
        for harness in ROWS:
            self.assertFalse(
                os.path.exists(self.wrapper_path(harness)),
                "%s's wrapper was written by a run that reported failure"
                % harness)


class TestExitStatusIsAboutWhetherItInstalled(BootstrapTestCase):
    """THE REGRESSION. Exit 0 must mean a wrapper landed."""

    def test_several_agents_without_a_terminal_exits_two(self):
        self.stub("claude-code", "cursor", "codex")
        code, out = self.run_piped()
        self.assertEqual(2, code,
                         "refusing to guess must not report success")
        self.assertNothingInstalled()
        self.assertIn("no terminal", out)

    def test_no_agent_at_all_exits_two(self):
        code, out = self.run_piped()
        self.assertEqual(2, code)
        self.assertNothingInstalled()
        self.assertIn("No supported agent CLI", out)

    def test_an_unknown_harness_exits_two(self):
        self.stub("cursor")
        code, out = self.run_piped("nosuchagent")
        self.assertEqual(2, code)
        self.assertNothingInstalled()
        self.assertIn("not a supported harness", out)

    def test_a_named_harness_installs_and_exits_zero(self):
        self.stub("cursor")
        code, _ = self.run_piped("cursor", "--no-launch")
        self.assertEqual(0, code)
        self.assertTrue(os.path.isfile(self.wrapper_path("cursor")))

    def test_one_agent_needs_no_question_even_with_a_terminal(self):
        self.stub("codex")
        code, out = self.run_pty("--no-launch")
        self.assertEqual(0, code)
        self.assertNotIn("Which one will run your gates", out)
        self.assertTrue(os.path.isfile(self.wrapper_path("codex")))


class TestTheInvocationComesFromTheManifest(BootstrapTestCase):
    """A wrapper nobody can be told to run is a wrapper that does not exist."""

    def test_each_harness_is_told_its_own_invocation(self):
        for harness in ROWS:
            with self.subTest(harness=harness):
                self.setUp()
                self.stub(harness)
                code, out = self.run_piped(harness, "--no-launch")
                self.assertEqual(0, code)
                self.assertEqual(resolved_invoke(harness),
                                 self.handoff_invoke(out, harness))

    def test_codex_is_not_told_a_slash_command(self):
        """It uses $name. Printing /prism-setup here is the shipped defect."""
        self.stub("codex")
        _, out = self.run_piped("codex", "--no-launch")
        self.assertEqual("$prism-setup", self.handoff_invoke(out, "codex"))

    def test_copilot_is_told_a_sentence_not_a_prefix(self):
        """Copilot has no per-skill command; it selects one by description."""
        self.stub("copilot")
        _, out = self.run_piped("copilot", "--no-launch")
        invoke = self.handoff_invoke(out, "copilot")
        self.assertEqual(resolved_invoke("copilot"), invoke)
        self.assertFalse(invoke.startswith("/"),
                         "copilot has no per-skill slash command")

    def test_exactly_three_harnesses_use_a_slash_prefix(self):
        """Pins the SHAPE of the split, so a uniform value cannot creep back.

        The whole defect was one value asserted across five harnesses. A test
        that only checked each row against itself would pass just as happily on
        five identical rows.
        """
        slashed = sorted(h for h in ROWS if resolved_invoke(h).startswith("/"))
        self.assertEqual(["antigravity", "claude-code", "cursor"], slashed)
        self.assertEqual("$prism-setup", resolved_invoke("codex"))
        self.assertFalse(resolved_invoke("copilot").startswith(("/", "$")))


class TestItAsks(BootstrapTestCase):
    """Refusing to GUESS was right. Refusing to ASK was the bug."""

    def test_a_number_selects_that_harness(self):
        self.stub("claude-code", "cursor", "codex")
        code, out = self.run_pty("--no-launch", answers=["2\n"])
        self.assertEqual(0, code)
        self.assertIn("Which one will run your gates", out)
        chosen = [h for h in ("claude-code", "cursor", "codex")
                  if os.path.isfile(self.wrapper_path(h))]
        self.assertEqual(1, len(chosen), "exactly one wrapper should land")

    def test_a_name_is_accepted_too(self):
        self.stub("claude-code", "cursor", "codex")
        code, _ = self.run_pty("--no-launch", answers=["codex\n"])
        self.assertEqual(0, code)
        self.assertTrue(os.path.isfile(self.wrapper_path("codex")))

    def test_it_gives_up_after_three_bad_answers_and_installs_nothing(self):
        self.stub("claude-code", "cursor")
        code, out = self.run_pty(
            "--no-launch", answers=["9\n", "nope\n", "\n", "1\n"])
        self.assertEqual(2, code)
        self.assertNothingInstalled()
        self.assertIn("Nothing was installed", out)


class TestItLaunches(BootstrapTestCase):
    """`exec` hands this terminal to the agent, with the wrapper as its prompt."""

    def test_the_argv_is_the_manifest_row_resolved(self):
        for harness in ROWS:
            with self.subTest(harness=harness):
                self.setUp()
                self.stub(harness)
                code, out = self.run_pty(harness)
                self.assertEqual(0, code)
                expected = [p.replace("{invoke}", resolved_invoke(harness))
                            for p in ROWS[harness]["launch"]][1:]
                self.assertIn("STUBARGV|" + "|".join(expected), out,
                              "the agent did not receive the manifest's argv")

    def test_a_multi_word_prompt_stays_one_argument(self):
        """Copilot's invoke is a sentence. Split, it would be six arguments."""
        self.stub("copilot")
        _, out = self.run_pty("copilot")
        self.assertIn("STUBARGV|-i|" + resolved_invoke("copilot"), out)

    def test_no_launch_does_not_launch(self):
        self.stub("cursor")
        _, out = self.run_pty("cursor", "--no-launch")
        self.assertNotIn("STUBARGV", out)
        self.assertIn("Next, in cursor", out)

    def test_without_a_terminal_it_never_launches(self):
        """A launch into a pipe would hand an interactive agent no terminal."""
        self.stub("cursor")
        code, out = self.run_piped("cursor")
        self.assertEqual(0, code)
        self.assertNotIn("STUBARGV", out)
        self.assertIn("Next, in cursor", out)


class TestThePrintedLineIsRetypable(BootstrapTestCase):
    """The `Launching` line is shipped output, so it is a command or it is a lie.

    It exists for the reader: it says what is about to take the terminal, and it
    is the line somebody copies when the launch failed and they want to try it
    again by hand. `exec` passes argv directly and was never affected by how the
    line was printed -- which is exactly why the defect below survived: nothing
    that ran was wrong.

    Codex's invocation is `$prism-setup`. Printed in DOUBLE quotes, a shell
    expands `$prism` to nothing and passes `-setup`, which Codex reads as an
    unknown option. So the harness whose invocation 0.6.0 corrected was the one
    whose printed line could not be retyped, and the four whose invocation
    already worked printed fine.

    The assertion is not about quote characters. Each printed line is handed to a
    REAL shell with the stub on PATH, and the argv the stub reports must equal the
    argv the exec path gave it.
    """

    def launching_line(self, out):
        for line in out.splitlines():
            if line.startswith("Launching"):
                return line[len("Launching"):].strip()
        self.fail("no Launching line was printed")

    def stub_argv(self, out):
        for line in out.splitlines():
            if line.startswith("STUBARGV"):
                return line.strip().split("|")[1:]
        self.fail("the stub was never reached")

    def test_retyping_the_printed_line_reaches_the_same_argv(self):
        for harness in ROWS:
            with self.subTest(harness=harness):
                self.setUp()
                self.stub(harness)
                code, out = self.run_pty(harness)
                self.assertEqual(0, code)
                execed = self.stub_argv(out)
                printed = self.launching_line(out)

                # A real shell, the way a human's would read it.
                proc = subprocess.run(["sh", "-c", printed], cwd=self.repo,
                                      env=self.env(), capture_output=True,
                                      text=True)
                retyped = self.stub_argv(proc.stdout)
                self.assertEqual(
                    execed, retyped,
                    "%s: the printed line %r reaches %r, not %r"
                    % (harness, printed, retyped, execed))

    def test_codex_is_printed_so_the_shell_does_not_expand_it(self):
        """The specific case, named, so a regression says which harness broke."""
        self.stub("codex")
        _, out = self.run_pty("codex")
        printed = self.launching_line(out)
        self.assertIn("$prism-setup", printed,
                      "the token itself must be visible in the line")
        proc = subprocess.run(["sh", "-c", printed], cwd=self.repo,
                              env=self.env(), capture_output=True, text=True)
        self.assertIn("STUBARGV|$prism-setup", proc.stdout,
                      "a shell reading the printed line must pass $prism-setup "
                      "intact; double quotes send `-setup` instead")


if __name__ == "__main__":
    unittest.main(verbosity=0)
