#!/usr/bin/env python3
"""
Unit tests for scripts/format_commit_msg.py.
"""

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest

from scripts.format_commit_msg import (
    CommitIssue,
    CommitMessageFormatter,
    CommitMessageLinter,
)


class TestCommitMessageFormatter(unittest.TestCase):
    def setUp(self):
        self.formatter = CommitMessageFormatter(max_subject=50, max_body=72)
        self.linter = CommitMessageLinter(max_subject=50, max_body=72)

    def test_clean_single_line(self):
        raw = "docs: update readme"
        formatted, issues = self.formatter.format(raw)
        self.assertEqual(formatted, "docs: update readme")
        self.assertEqual(len(issues), 0)

    def test_strip_trailing_period_from_subject(self):
        raw = "docs: update readme."
        formatted, issues = self.formatter.format(raw)
        self.assertEqual(formatted, "docs: update readme")

    def test_subject_length_warning(self):
        raw = "docs: this is a very long subject line that exceeds fifty characters by a lot"
        formatted, issues = self.formatter.format(raw)
        self.assertTrue(any(i.rule == "E001" for i in issues))

    def test_paragraph_wrapping(self):
        raw = """docs: update installation notes

This is a long paragraph that is going to be well over seventy-two characters in a single line and should be cleanly wrapped across multiple lines without splitting any individual words inappropriately."""
        formatted, issues = self.formatter.format(raw)
        lines = formatted.split("\n")
        self.assertEqual(lines[0], "docs: update installation notes")
        self.assertEqual(lines[1], "")
        for line in lines[2:]:
            self.assertLessEqual(len(line), 72)
        self.assertEqual(len(self.linter.lint(formatted)), 0)

    def test_bullet_points_with_hanging_indent(self):
        raw = """feat: add cool features

Here is what changed:
- First item: This is a relatively long bullet item that should wrap onto a second line with a nice hanging indent matching the start of the bullet text.
- Second item: Short text.
* Third item (asterisk): Another bullet point that contains enough text to require wrapping onto another line cleanly.
1. Numbered item: Explaining the first step in the process with enough detail to wrap around to the next line."""
        formatted, issues = self.formatter.format(raw)
        lines = formatted.split("\n")
        for line in lines:
            self.assertLessEqual(len(line), 72)

        # Check hanging indent of first bullet
        bullet_idx = [i for i, l in enumerate(lines) if l.startswith("- First item:")][0]
        continuation_line = lines[bullet_idx + 1]
        self.assertTrue(continuation_line.startswith("  "), f"Expected 2-space indent, got: {continuation_line}")

        # Check numbered bullet indent
        num_idx = [i for i, l in enumerate(lines) if l.startswith("1. Numbered item:")][0]
        num_continuation = lines[num_idx + 1]
        self.assertTrue(num_continuation.startswith("   "), f"Expected 3-space indent, got: {num_continuation}")

    def test_code_fence_verbatim_preservation(self):
        code_block = "```bash\nadb shell am start -n se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity --extra-long-flag-that-must-not-be-wrapped-at-seventy-two-characters\n```"
        raw = f"""feat: add adb instructions

Run the following command to test:

{code_block}

End of message."""
        formatted, issues = self.formatter.format(raw)
        self.assertIn(code_block, formatted)

    def test_long_url_preservation(self):
        url = "https://github.com/360ied/mumla-oled/commit/f84256ddf5ed51dc72fde8033b623ab3da7131fe?diff=split&context=10#diff-longanchor"
        raw = f"""docs: link to upstream commit

Reference URL:
{url}

Thanks."""
        formatted, issues = self.formatter.format(raw)
        self.assertIn(url, formatted)

    def test_git_trailers_preservation(self):
        raw = """core: improve connection handling

Refactor TCP reconnection logic when connection drops.

Signed-off-by: Developer Name <developer@example.com>
Co-authored-by: Another Dev <another@example.com>
Fixes: #1234"""
        formatted, issues = self.formatter.format(raw)
        self.assertIn("Signed-off-by: Developer Name <developer@example.com>", formatted)
        self.assertIn("Co-authored-by: Another Dev <another@example.com>", formatted)
        self.assertIn("Fixes: #1234", formatted)

    def test_markdown_table_preservation(self):
        table = "| Column 1 | Column 2 |\n|---|---|\n| Value A | Value B |"
        raw = f"""docs: add table summary

Overview:

{table}"""
        formatted, issues = self.formatter.format(raw)
        self.assertIn(table, formatted)

    def test_comment_stripping(self):
        raw = """docs: update AGENTS.md

# Please enter the commit message for your changes. Lines starting
# with '#' will be ignored.
Actual commit body explanation."""
        formatted, issues = self.formatter.format(raw)
        self.assertNotIn("Please enter the commit message", formatted)
        self.assertIn("Actual commit body explanation.", formatted)

    def test_missing_line_2_blank_fixed(self):
        raw = "docs: subject line\nBody starting immediately on line 2 without blank."
        formatted, issues = self.formatter.format(raw)
        lines = formatted.split("\n")
        self.assertEqual(lines[0], "docs: subject line")
        self.assertEqual(lines[1], "")
        self.assertEqual(lines[2], "Body starting immediately on line 2 without blank.")


class TestCommitMessageLinter(unittest.TestCase):
    def setUp(self):
        self.linter = CommitMessageLinter(max_subject=50, max_body=72)

    def test_valid_message(self):
        raw = """docs: update AGENTS.md

Explain the git 50/72 commit message rule.
Ensure agents format commit messages properly."""
        issues = self.linter.lint(raw)
        errors = [i for i in issues if i.severity == "error"]
        self.assertEqual(len(errors), 0)

    def test_subject_too_long(self):
        raw = "docs: this subject line is way longer than fifty characters limit in git rule"
        issues = self.linter.lint(raw)
        errors = [i for i in issues if i.severity == "error"]
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0].rule, "E001")

    def test_missing_blank_line(self):
        raw = "docs: short subject\nBody starts right on second line."
        issues = self.linter.lint(raw)
        errors = [i for i in issues if i.severity == "error"]
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0].rule, "E002")

    def test_body_line_too_long(self):
        long_line = "x" * 75
        raw = f"docs: short subject\n\n{long_line}"
        issues = self.linter.lint(raw)
        errors = [i for i in issues if i.severity == "error"]
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0].rule, "E003")

    def test_trailing_period_warning(self):
        raw = "docs: short subject."
        issues = self.linter.lint(raw)
        warnings = [i for i in issues if i.severity == "warning"]
        self.assertEqual(len(warnings), 1)
        self.assertEqual(warnings[0].rule, "W001")

    def test_empty_message(self):
        issues = self.linter.lint("")
        errors = [i for i in issues if i.severity == "error"]
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0].rule, "E000")


class TestCLIIntegration(unittest.TestCase):
    def run_cli(self, args: list, input_data: str = None) -> subprocess.CompletedProcess:
        cmd = [sys.executable, "scripts/format_commit_msg.py"] + args
        return subprocess.run(
            cmd,
            input=input_data,
            capture_output=True,
            text=True,
        )

    def test_cli_message_flag(self):
        proc = self.run_cli(["-m", "docs: test subject\n\nLong body line that should be wrapped if it is long enough."])
        self.assertEqual(proc.returncode, 0)
        self.assertIn("docs: test subject", proc.stdout)

    def test_cli_subject_and_body_flags(self):
        proc = self.run_cli(["--subject", "docs: test flags", "--body", "Body text from flag."])
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(proc.stdout.strip(), "docs: test flags\n\nBody text from flag.")

    def test_cli_check_flag_valid(self):
        proc = self.run_cli(["--check", "-m", "docs: valid subject\n\nValid body."])
        self.assertEqual(proc.returncode, 0)
        self.assertIn("OK:", proc.stdout)

    def test_cli_check_flag_invalid(self):
        proc = self.run_cli(["--check", "-m", "docs: " + ("x" * 50)])
        self.assertEqual(proc.returncode, 1)
        self.assertIn("E001", proc.stderr)

    def test_cli_strict_flag(self):
        # Subject is > 50 characters, triggers warning in format mode
        proc = self.run_cli(["--strict", "-m", "docs: this is a very long subject line exceeding fifty characters limit"])
        self.assertEqual(proc.returncode, 1)

    def test_cli_json_mode(self):
        proc = self.run_cli(["--json", "-m", "docs: short subject\n\nBody explanation."])
        self.assertEqual(proc.returncode, 0)
        data = json.loads(proc.stdout)
        self.assertTrue(data["valid"])
        self.assertEqual(data["subject"], "docs: short subject")
        self.assertEqual(data["body"], "Body explanation.")
        self.assertEqual(data["errors"], [])

    def test_cli_stdin(self):
        raw = "docs: from stdin\n\nBody from stdin."
        proc = self.run_cli([], input_data=raw)
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(proc.stdout.strip(), raw)

    def test_cli_inplace_file(self):
        with tempfile.NamedTemporaryFile("w+", delete=False) as f:
            f.write("docs: test in-place\nThis line was on line 2 without blank.")
            tmp_name = f.name

        try:
            proc = self.run_cli(["-i", tmp_name])
            self.assertEqual(proc.returncode, 0)
            with open(tmp_name, "r") as f:
                content = f.read()
            lines = content.split("\n")
            self.assertEqual(lines[0], "docs: test in-place")
            self.assertEqual(lines[1], "")
            self.assertEqual(lines[2], "This line was on line 2 without blank.")
        finally:
            if os.path.exists(tmp_name):
                os.remove(tmp_name)

    def test_cli_hook_mode(self):
        with tempfile.NamedTemporaryFile("w+", delete=False) as f:
            f.write("docs: hook test\nUnwrapped body paragraph that is long enough to be wrapped at 72 characters when hook executes.\n")
            tmp_name = f.name

        try:
            proc = self.run_cli(["--hook", tmp_name])
            self.assertEqual(proc.returncode, 0)
            with open(tmp_name, "r") as f:
                content = f.read()
            self.assertIn("docs: hook test", content)
            self.assertIn("\n\n", content)
        finally:
            if os.path.exists(tmp_name):
                os.remove(tmp_name)


if __name__ == "__main__":
    unittest.main()
