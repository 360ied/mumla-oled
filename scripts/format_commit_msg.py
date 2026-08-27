#!/usr/bin/env python3
"""
Git 50/72 Commit Message Formatter and Linter.

Enforces the standard Git 50/72 rule:
  - Subject line: <= 50 characters (concise, imperative, no trailing period).
  - Line 2: Blank line separating subject and body.
  - Body lines: <= 72 characters (wrapped paragraphs, hanging indents for lists,
    verbatim code blocks, URLs, and git trailers).

Usage:
  python3 scripts/format_commit_msg.py -m "docs: short summary\n\nDetailed explanation..."
  python3 scripts/format_commit_msg.py --check -f .git/COMMIT_EDITMSG
  cat message.txt | python3 scripts/format_commit_msg.py
  python3 scripts/format_commit_msg.py --json -m "..."
"""

import argparse
import json
import os
import re
import sys
import textwrap
from typing import Any, Dict, List, Optional, Tuple

DEFAULT_MAX_SUBJECT = 50
DEFAULT_MAX_BODY = 72

# Regex patterns
RE_GIT_TRAILER = re.compile(
    r"^(Signed-off-by|Co-authored-by|Acked-by|Reviewed-by|Tested-by|"
    r"Reported-by|Suggested-by|Fixes|Closes|Resolves|Refs|See-also|"
    r"Change-Id|Bug|CC|Cc):\s+.+$",
    re.IGNORECASE,
)
RE_URL = re.compile(r"^(?:https?|ftp|file)://\S+$")
RE_CONTAINS_LONG_URL = re.compile(r"https?://\S{40,}")
RE_BULLET = re.compile(r"^(\s*)([-*+•]|\d+[.)]|\([0-9a-zA-Z]+\))\s+(.*)$")
RE_CODE_FENCE = re.compile(r"^\s*```")
RE_DIFF_HEADER = re.compile(r"^(?:diff --git|index [0-9a-f]+\.\.[0-9a-f]+|--- |\+\+\+ |@@ )")
RE_MARKDOWN_TABLE = re.compile(r"^\s*\|.*\|\s*$")


class CommitIssue:
    """Represents a lint error or warning in a commit message."""

    def __init__(
        self,
        line_num: int,
        rule: str,
        message: str,
        severity: str = "error",
        text: str = "",
    ):
        self.line_num = line_num
        self.rule = rule
        self.message = message
        self.severity = severity
        self.text = text

    def to_dict(self) -> Dict[str, Any]:
        return {
            "line": self.line_num,
            "rule": self.rule,
            "severity": self.severity,
            "message": self.message,
            "text": self.text,
        }

    def __repr__(self) -> str:
        prefix = "ERROR" if self.severity == "error" else "WARNING"
        location = f"line {self.line_num}" if self.line_num > 0 else "message"
        res = f"{prefix} [{self.rule}] ({location}): {self.message}"
        if self.text:
            res += f"\n  > {self.text}"
        return res


class CommitMessageFormatter:
    """Formats commit messages to comply with Git 50/72 conventions."""

    def __init__(
        self,
        max_subject: int = DEFAULT_MAX_SUBJECT,
        max_body: int = DEFAULT_MAX_BODY,
        strip_comments: bool = True,
    ):
        self.max_subject = max_subject
        self.max_body = max_body
        self.strip_comments = strip_comments

    def clean_raw_text(self, raw_text: str) -> List[str]:
        """Normalize line endings and optionally remove Git comment lines."""
        lines = raw_text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        if self.strip_comments:
            lines = [l for l in lines if not l.startswith("#")]
        # Trim trailing empty lines
        while lines and not lines[-1].strip():
            lines.pop()
        return lines

    def is_verbatim(self, line: str) -> bool:
        """Check if a line should be preserved without line wrapping."""
        stripped = line.strip()
        if not stripped:
            return True
        if RE_CODE_FENCE.match(line):
            return True
        if RE_GIT_TRAILER.match(stripped):
            return True
        if RE_URL.match(stripped) or RE_CONTAINS_LONG_URL.search(stripped):
            return True
        if RE_DIFF_HEADER.match(stripped):
            return True
        if RE_MARKDOWN_TABLE.match(stripped):
            return True
        if line.startswith("    ") or line.startswith("\t"):
            return True
        return False

    def wrap_bullet_item(self, indent: str, marker: str, content: str) -> List[str]:
        """Wrap a bullet list item with hanging indentation."""
        first_line_prefix = f"{indent}{marker} "
        subsequent_indent = " " * len(first_line_prefix)

        # Reflow the content words
        words = content.split()
        if not words:
            return [first_line_prefix.rstrip()]

        wrapped = textwrap.wrap(
            " ".join(words),
            width=self.max_body,
            initial_indent=first_line_prefix,
            subsequent_indent=subsequent_indent,
            break_long_words=False,
            break_on_hyphens=False,
        )
        return wrapped if wrapped else [first_line_prefix + content]

    def wrap_paragraph(self, text: str, initial_indent: str = "", subsequent_indent: str = "") -> List[str]:
        """Wrap a continuous text paragraph to max_body width."""
        words = text.split()
        if not words:
            return []
        wrapped = textwrap.wrap(
            " ".join(words),
            width=self.max_body,
            initial_indent=initial_indent,
            subsequent_indent=subsequent_indent,
            break_long_words=False,
            break_on_hyphens=False,
        )
        return wrapped

    def format(self, raw_text: str) -> Tuple[str, List[CommitIssue]]:
        """Format the commit message and return (formatted_string, issues)."""
        issues: List[CommitIssue] = []
        lines = self.clean_raw_text(raw_text)

        if not lines:
            issues.append(CommitIssue(0, "W003", "Commit message is empty.", severity="warning"))
            return "", issues

        # 1. Subject extraction (first non-empty line)
        subject_idx = 0
        while subject_idx < len(lines) and not lines[subject_idx].strip():
            subject_idx += 1

        if subject_idx >= len(lines):
            issues.append(CommitIssue(0, "W003", "Commit message is empty.", severity="warning"))
            return "", issues

        raw_subject = lines[subject_idx].strip()
        # Remove trailing period if present
        cleaned_subject = re.sub(r"\.+$", "", raw_subject).strip()

        if len(cleaned_subject) > self.max_subject:
            issues.append(
                CommitIssue(
                    1,
                    "E001",
                    f"Subject line exceeds {self.max_subject} characters ({len(cleaned_subject)} chars).",
                    severity="warning",
                    text=cleaned_subject,
                )
            )

        body_raw_lines = lines[subject_idx + 1 :]

        # Strip initial blank lines in body
        while body_raw_lines and not body_raw_lines[0].strip():
            body_raw_lines.pop(0)

        if not body_raw_lines:
            return cleaned_subject, issues

        # 2. Process body into formatted blocks
        formatted_body_blocks: List[List[str]] = []
        current_paragraph: List[str] = []

        def flush_paragraph():
            nonlocal current_paragraph
            if current_paragraph:
                p_text = " ".join(current_paragraph)
                wrapped = self.wrap_paragraph(p_text)
                if wrapped:
                    formatted_body_blocks.append(wrapped)
                current_paragraph = []

        i = 0
        while i < len(body_raw_lines):
            line = body_raw_lines[i]
            stripped = line.strip()

            # Handle code block fences
            if RE_CODE_FENCE.match(line):
                flush_paragraph()
                code_block_lines = [line]
                i += 1
                while i < len(body_raw_lines):
                    c_line = body_raw_lines[i]
                    code_block_lines.append(c_line)
                    i += 1
                    if RE_CODE_FENCE.match(c_line):
                        break
                formatted_body_blocks.append(code_block_lines)
                continue

            # Blank line separates paragraphs/blocks
            if not stripped:
                flush_paragraph()
                i += 1
                continue

            # Bullet points
            bullet_match = RE_BULLET.match(line)
            if bullet_match:
                flush_paragraph()
                indent, marker, content = bullet_match.groups()
                bullet_lines_content = [content]
                i += 1
                while i < len(body_raw_lines):
                    next_line = body_raw_lines[i]
                    next_stripped = next_line.strip()
                    if not next_stripped:
                        break
                    if RE_BULLET.match(next_line) or self.is_verbatim(next_line):
                        break
                    # If it's an indented continuation line or sub-text
                    if next_line.startswith("  ") or not next_line.startswith("-"):
                        bullet_lines_content.append(next_stripped)
                        i += 1
                    else:
                        break
                full_bullet_content = " ".join(bullet_lines_content)
                wrapped_bullet = self.wrap_bullet_item(indent, marker, full_bullet_content)
                formatted_body_blocks.append(wrapped_bullet)
                continue

            # Git trailers (collect consecutive trailers together)
            if RE_GIT_TRAILER.match(stripped):
                flush_paragraph()
                trailers = [stripped]
                i += 1
                while i < len(body_raw_lines):
                    next_stripped = body_raw_lines[i].strip()
                    if RE_GIT_TRAILER.match(next_stripped):
                        trailers.append(next_stripped)
                        i += 1
                    else:
                        break
                formatted_body_blocks.append(trailers)
                continue

            # Markdown tables (collect consecutive table rows together)
            if RE_MARKDOWN_TABLE.match(stripped):
                flush_paragraph()
                table_lines = [line]
                i += 1
                while i < len(body_raw_lines):
                    next_line = body_raw_lines[i]
                    if RE_MARKDOWN_TABLE.match(next_line.strip()):
                        table_lines.append(next_line)
                        i += 1
                    else:
                        break
                formatted_body_blocks.append(table_lines)
                continue

            # 4-space indented or tab-indented verbatim blocks
            if line.startswith("    ") or line.startswith("\t"):
                flush_paragraph()
                indented_lines = [line]
                i += 1
                while i < len(body_raw_lines):
                    next_line = body_raw_lines[i]
                    if next_line.startswith("    ") or next_line.startswith("\t"):
                        indented_lines.append(next_line)
                        i += 1
                    else:
                        break
                formatted_body_blocks.append(indented_lines)
                continue

            # Other verbatim lines (URLs, diffs)
            if self.is_verbatim(line):
                flush_paragraph()
                formatted_body_blocks.append([line])
                i += 1
                continue

            # Standard paragraph line
            current_paragraph.append(stripped)
            i += 1

        flush_paragraph()

        # Combine blocks with proper single blank lines
        result_lines = [cleaned_subject, ""]
        for block_idx, block in enumerate(formatted_body_blocks):
            for l in block:
                result_lines.append(l)
            if block_idx < len(formatted_body_blocks) - 1:
                is_curr_bullet = len(block) > 0 and RE_BULLET.match(block[0])
                next_block = formatted_body_blocks[block_idx + 1]
                is_next_bullet = len(next_block) > 0 and RE_BULLET.match(next_block[0])

                if is_curr_bullet and is_next_bullet:
                    pass  # Keep list items adjacent
                else:
                    result_lines.append("")

        formatted_msg = "\n".join(result_lines)
        return formatted_msg, issues


class CommitMessageLinter:
    """Lints a commit message strictly against 50/72 rules."""

    def __init__(
        self,
        max_subject: int = DEFAULT_MAX_SUBJECT,
        max_body: int = DEFAULT_MAX_BODY,
        strip_comments: bool = True,
    ):
        self.max_subject = max_subject
        self.max_body = max_body
        self.strip_comments = strip_comments

    def lint(self, raw_text: str) -> List[CommitIssue]:
        issues: List[CommitIssue] = []
        lines = raw_text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        if self.strip_comments:
            lines = ["" if l.startswith("#") else l for l in lines]

        # Find first non-empty line (subject)
        subject_line_idx = -1
        for idx, line in enumerate(lines):
            if line.strip():
                subject_line_idx = idx
                break

        if subject_line_idx == -1:
            issues.append(CommitIssue(1, "E000", "Commit message is empty.", severity="error"))
            return issues

        subject_line = lines[subject_line_idx]
        subject_len = len(subject_line)

        # Check Subject Length (<= 50)
        if subject_len > self.max_subject:
            issues.append(
                CommitIssue(
                    subject_line_idx + 1,
                    "E001",
                    f"Subject line exceeds {self.max_subject} characters ({subject_len} chars).",
                    severity="error",
                    text=subject_line,
                )
            )

        # Check Trailing Period in Subject
        if subject_line.rstrip().endswith("."):
            issues.append(
                CommitIssue(
                    subject_line_idx + 1,
                    "W001",
                    "Subject line should not end with a period.",
                    severity="warning",
                    text=subject_line,
                )
            )

        # Check if there is body content
        body_lines = lines[subject_line_idx + 1 :]
        has_body = any(l.strip() for l in body_lines)

        if has_body:
            # Check Blank Line on Line 2 (immediately after subject)
            sep_idx = subject_line_idx + 1
            if sep_idx < len(lines) and lines[sep_idx].strip() != "":
                issues.append(
                    CommitIssue(
                        sep_idx + 1,
                        "E002",
                        "Expected blank line between subject and body.",
                        severity="error",
                        text=lines[sep_idx],
                    )
                )

            # Check Body Lines (<= 72)
            in_code_fence = False
            for b_idx, line in enumerate(body_lines, start=subject_line_idx + 2):
                if RE_CODE_FENCE.match(line):
                    in_code_fence = not in_code_fence
                    continue

                if in_code_fence:
                    continue

                stripped = line.strip()
                if not stripped:
                    continue

                # Exemptions: single long URLs, git trailers, diff headers
                if RE_URL.match(stripped) or RE_CONTAINS_LONG_URL.search(stripped):
                    continue
                if RE_GIT_TRAILER.match(stripped):
                    continue
                if RE_DIFF_HEADER.match(stripped):
                    continue

                if len(line) > self.max_body:
                    issues.append(
                        CommitIssue(
                            b_idx,
                            "E003",
                            f"Body line exceeds {self.max_body} characters ({len(line)} chars).",
                            severity="error",
                            text=line,
                        )
                    )

        return issues


def install_git_hook(hook_path: str = ".git/hooks/commit-msg") -> bool:
    """Installs this script as a Git commit-msg hook."""
    hook_dir = os.path.dirname(hook_path)
    if not os.path.exists(hook_dir):
        print(f"Error: Git hook directory '{hook_dir}' does not exist.", file=sys.stderr)
        return False

    script_path = os.path.abspath(__file__)
    hook_content = f"""#!/usr/bin/env sh
# Git 50/72 commit-msg hook
python3 "{script_path}" --hook "$1"
"""
    try:
        with open(hook_path, "w", encoding="utf-8") as f:
            f.write(hook_content)
        os.chmod(hook_path, 0o755)
        print(f"Successfully installed commit-msg hook at '{hook_path}'.")
        return True
    except Exception as e:
        print(f"Failed to install hook: {e}", file=sys.stderr)
        return False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Enforce or format Git commit messages to conform to the 50/72 rule.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "file",
        nargs="?",
        default=None,
        help="Input file containing commit message (or reads stdin if not specified).",
    )
    parser.add_argument(
        "-m",
        "--message",
        type=str,
        default=None,
        help="Commit message text directly.",
    )
    parser.add_argument(
        "-s",
        "--subject",
        type=str,
        default=None,
        help="Subject line (used with --body).",
    )
    parser.add_argument(
        "-b",
        "--body",
        type=str,
        default=None,
        help="Body text (used with --subject).",
    )
    parser.add_argument(
        "-c",
        "--check",
        "--lint",
        action="store_true",
        help="Lint mode: validate commit message and exit with non-zero code on violations.",
    )
    parser.add_argument(
        "-i",
        "--in-place",
        action="store_true",
        help="Modify the input file in-place with formatted output.",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=str,
        default=None,
        help="Write formatted message to output file path.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output structured JSON results for AI agents and automated tools.",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Fail (exit 1) if any warnings (e.g. subject > 50 chars) occur during formatting.",
    )
    parser.add_argument(
        "--max-subject",
        type=int,
        default=DEFAULT_MAX_SUBJECT,
        help=f"Maximum allowed subject line length (default: {DEFAULT_MAX_SUBJECT}).",
    )
    parser.add_argument(
        "--max-body",
        type=int,
        default=DEFAULT_MAX_BODY,
        help=f"Maximum allowed body line length (default: {DEFAULT_MAX_BODY}).",
    )
    parser.add_argument(
        "--keep-comments",
        action="store_true",
        help="Do not strip '#' comment lines from input.",
    )
    parser.add_argument(
        "--hook",
        type=str,
        metavar="COMMIT_EDITMSG",
        help="Run in Git commit-msg hook mode on given file.",
    )
    parser.add_argument(
        "--install-hook",
        action="store_true",
        help="Install commit-msg hook into .git/hooks/commit-msg.",
    )

    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if args.install_hook:
        return 0 if install_git_hook() else 1

    # Git hook mode
    if args.hook:
        hook_file = args.hook
        if not os.path.exists(hook_file):
            print(f"Error: Hook file '{hook_file}' not found.", file=sys.stderr)
            return 1
        with open(hook_file, "r", encoding="utf-8") as f:
            content = f.read()
        formatter = CommitMessageFormatter(
            max_subject=args.max_subject,
            max_body=args.max_body,
            strip_comments=not args.keep_comments,
        )
        formatted_msg, issues = formatter.format(content)
        linter = CommitMessageLinter(
            max_subject=args.max_subject,
            max_body=args.max_body,
            strip_comments=not args.keep_comments,
        )
        lint_issues = linter.lint(formatted_msg)
        errors = [i for i in lint_issues if i.severity == "error"]
        if errors:
            print("Git 50/72 commit-msg check failed:", file=sys.stderr)
            for err in errors:
                print(f"  {err}", file=sys.stderr)
            return 1
        # Rewrite hook file with clean formatted message
        with open(hook_file, "w", encoding="utf-8") as f:
            f.write(formatted_msg + "\n")
        return 0

    # Obtain input raw text
    raw_text = ""
    input_file_path: Optional[str] = None

    if args.subject is not None:
        raw_text = args.subject
        if args.body:
            raw_text += "\n\n" + args.body
    elif args.message is not None:
        raw_text = args.message
    elif args.file:
        input_file_path = args.file
        if not os.path.exists(input_file_path):
            print(f"Error: File '{input_file_path}' not found.", file=sys.stderr)
            return 1
        with open(input_file_path, "r", encoding="utf-8") as f:
            raw_text = f.read()
    else:
        if not sys.stdin.isatty():
            raw_text = sys.stdin.read()
        else:
            print("Error: No commit message provided. Pass via -m, -f, --subject, or stdin.", file=sys.stderr)
            return 1

    formatter = CommitMessageFormatter(
        max_subject=args.max_subject,
        max_body=args.max_body,
        strip_comments=not args.keep_comments,
    )
    linter = CommitMessageLinter(
        max_subject=args.max_subject,
        max_body=args.max_body,
        strip_comments=not args.keep_comments,
    )

    # 1. Lint / Check Mode
    if args.check:
        issues = linter.lint(raw_text)
        errors = [i for i in issues if i.severity == "error"]
        warnings = [i for i in issues if i.severity == "warning"]

        if args.json:
            formatted_text, _ = formatter.format(raw_text)
            first_line = raw_text.strip().split("\n")[0] if raw_text.strip() else ""
            res = {
                "valid": len(errors) == 0,
                "subject": first_line,
                "subject_length": len(first_line),
                "errors": [e.to_dict() for e in errors],
                "warnings": [w.to_dict() for w in warnings],
                "formatted": formatted_text,
            }
            print(json.dumps(res, indent=2))
        else:
            if not issues:
                print(f"OK: Commit message complies with {args.max_subject}/{args.max_body} rule.")
            else:
                for issue in issues:
                    print(issue, file=sys.stderr)

        return 1 if (errors or (args.strict and warnings)) else 0

    # 2. Format Mode
    formatted_msg, format_issues = formatter.format(raw_text)
    lint_issues = linter.lint(formatted_msg)
    all_issues = format_issues + [i for i in lint_issues if i not in format_issues]
    errors = [i for i in all_issues if i.severity == "error"]
    warnings = [i for i in all_issues if i.severity == "warning"]

    if args.json:
        first_line = formatted_msg.split("\n")[0] if formatted_msg else ""
        body_part = "\n".join(formatted_msg.split("\n")[2:]) if len(formatted_msg.split("\n")) > 2 else ""
        res = {
            "valid": len(errors) == 0 and (not args.strict or len(warnings) == 0),
            "subject": first_line,
            "subject_length": len(first_line),
            "body": body_part,
            "errors": [e.to_dict() for e in errors],
            "warnings": [w.to_dict() for w in warnings],
            "formatted": formatted_msg,
        }
        print(json.dumps(res, indent=2))
        return 1 if (errors or (args.strict and warnings)) else 0

    # Handle file outputs
    if args.in_place:
        if not input_file_path:
            print("Error: --in-place requires a file path argument.", file=sys.stderr)
            return 1
        with open(input_file_path, "w", encoding="utf-8") as f:
            f.write(formatted_msg + "\n")
    elif args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(formatted_msg + "\n")
    else:
        print(formatted_msg)

    if warnings and args.strict:
        for w in warnings:
            print(w, file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
