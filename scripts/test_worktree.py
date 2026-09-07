#!/usr/bin/env python3
"""
Unit tests for scripts/worktree.sh.
"""

import os
import subprocess
import tempfile
import unittest

SCRIPT_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "worktree.sh")
)


class TestWorktreeCLI(unittest.TestCase):
    def test_help_exits_zero(self):
        for flag in ["-h", "--help"]:
            proc = subprocess.run(
                [SCRIPT_PATH, flag],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            self.assertEqual(proc.returncode, 0)
            self.assertIn("Usage:", proc.stdout)
            self.assertIn("add", proc.stdout)
            self.assertIn("remove", proc.stdout)

    def test_no_args_exits_one(self):
        proc = subprocess.run(
            [SCRIPT_PATH],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Usage:", proc.stderr)

    def test_unknown_command_exits_one(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "foobar"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Unknown command 'foobar'", proc.stderr)

    def test_add_missing_branch(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "add"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Branch name is required", proc.stderr)

    def test_add_master_disallowed(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "add", "master"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Cannot create a worktree for 'master'", proc.stderr)

    def test_remove_missing_target(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "remove"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 1)
        self.assertIn("Worktree branch or path is required", proc.stderr)


class TestWorktreeLifecycle(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.repo_dir = os.path.join(self.temp_dir.name, "repo")
        os.makedirs(self.repo_dir)

        # Initialize mock git repository
        subprocess.run(["git", "init", "-b", "master", self.repo_dir], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", self.repo_dir, "config", "user.name", "Test Agent"], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "config", "user.email", "agent@example.com"], check=True)

        # Create an initial commit
        test_file = os.path.join(self.repo_dir, "README.md")
        with open(test_file, "w") as f:
            f.write("# Mock Repo\n")
        subprocess.run(["git", "-C", self.repo_dir, "add", "."], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", "initial commit"], check=True, stdout=subprocess.DEVNULL)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_add_list_remove_worktree(self):
        # 1. Add a worktree
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/vad-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        self.assertIn("WORKTREE READY!", proc_add.stdout)

        expected_wt = os.path.join(self.repo_dir, ".worktrees", "feature", "vad-test")
        self.assertTrue(os.path.isdir(expected_wt))
        self.assertTrue(os.path.isfile(os.path.join(expected_wt, ".git")))

        # 2. List worktrees
        proc_list = subprocess.run(
            [SCRIPT_PATH, "list"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_list.returncode, 0)
        self.assertIn("feature/vad-test", proc_list.stdout)

        # 3. Refuse removal when dirty without force
        dirty_file = os.path.join(expected_wt, "dirty.txt")
        with open(dirty_file, "w") as f:
            f.write("untracked work\n")

        proc_rm_fail = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/vad-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm_fail.returncode, 1)
        self.assertIn("contains uncommitted changes", proc_rm_fail.stderr)
        self.assertTrue(os.path.isdir(expected_wt))

        # 4. Remove with --force
        proc_rm_force = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/vad-test", "--force"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm_force.returncode, 0, msg=proc_rm_force.stderr)
        self.assertFalse(os.path.isdir(expected_wt))

        # 5. Verify branch was NOT deleted
        branch_check = subprocess.run(
            ["git", "-C", self.repo_dir, "show-ref", "--verify", "refs/heads/feature/vad-test"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(branch_check.returncode, 0, "Branch should be preserved")


if __name__ == "__main__":
    unittest.main()
