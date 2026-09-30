#!/usr/bin/env python3
"""
Unit tests for scripts/worktree.py.
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# Allow importing worktree directly for unit tests
sys.path.insert(0, os.path.dirname(__file__))
import worktree

SCRIPT_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "worktree.py")
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
            self.assertIn("cleanup", proc.stdout)

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


class TempRepoTestBase(unittest.TestCase):
    """Shared fixture: an isolated temp git repo on `master` with one commit.

    `addCleanup` is armed immediately after creating the TemporaryDirectory
    so the temp dir is removed even if the rest of setUp raises.
    """
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.repo_dir = Path(self.temp_dir.name) / "repo"
        self.repo_dir.mkdir()

        subprocess.run(["git", "init", "-b", "master", str(self.repo_dir)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", str(self.repo_dir), "config", "user.name", "Test Agent"], check=True)
        subprocess.run(["git", "-C", str(self.repo_dir), "config", "user.email", "agent@example.com"], check=True)

        (self.repo_dir / "README.md").write_text("# Mock Repo\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.repo_dir), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.repo_dir), "commit", "-m", "initial commit"], check=True, stdout=subprocess.DEVNULL)

    @property
    def repo_str(self) -> str:
        return str(self.repo_dir)


class TestWorktreeLifecycle(TempRepoTestBase):

    def test_add_list_remove_worktree(self):
        # 1. Add a worktree with nested path
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/deep/nested-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        self.assertIn("WORKTREE READY!", proc_add.stdout)

        expected_wt = os.path.join(self.repo_dir, ".worktrees", "feature", "deep", "nested-test")
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
        self.assertIn("feature/deep/nested-test", proc_list.stdout)
        header_pos = proc_list.stdout.find("Active Git Worktrees")
        entry_pos = proc_list.stdout.find("feature/deep/nested-test")
        self.assertGreater(header_pos, -1, "Header 'Active Git Worktrees' not found in stdout")
        self.assertGreater(entry_pos, header_pos, "Header must appear before worktree entries")

        # 3. Refuse removal when dirty without force
        dirty_file = os.path.join(expected_wt, "dirty.txt")
        with open(dirty_file, "w", encoding="utf-8") as f:
            f.write("untracked work\n")

        proc_rm_fail = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/deep/nested-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm_fail.returncode, 1)
        self.assertIn("contains uncommitted changes", proc_rm_fail.stderr)
        self.assertTrue(os.path.isdir(expected_wt))

        # 4. Remove dirty file and test clean removal WITHOUT --force
        os.remove(dirty_file)
        proc_rm_clean = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/deep/nested-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm_clean.returncode, 0, msg=proc_rm_clean.stderr)
        self.assertFalse(os.path.isdir(expected_wt))
        self.assertFalse(os.path.isdir(os.path.join(self.repo_dir, ".worktrees")))

        # 5. Verify branch was NOT deleted
        branch_check = subprocess.run(
            ["git", "-C", self.repo_dir, "show-ref", "--verify", "refs/heads/feature/deep/nested-test"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.assertEqual(branch_check.returncode, 0, "Branch should be preserved")

    def test_worktree_with_submodule_removal(self):
        # Create a mock submodule repository
        sub_repo = os.path.join(self.temp_dir.name, "sub_repo")
        os.makedirs(sub_repo)
        subprocess.run(["git", "init", "-b", "master", sub_repo], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", sub_repo, "config", "user.name", "Test Agent"], check=True)
        subprocess.run(["git", "-C", sub_repo, "config", "user.email", "agent@example.com"], check=True)
        with open(os.path.join(sub_repo, "sub.txt"), "w", encoding="utf-8") as f:
            f.write("submodule\n")
        subprocess.run(["git", "-C", sub_repo, "add", "."], check=True)
        subprocess.run(["git", "-C", sub_repo, "commit", "-m", "sub init"], check=True, stdout=subprocess.DEVNULL)

        # Add submodule to main repo
        subprocess.run(
            ["git", "-C", self.repo_dir, "-c", "protocol.file.allow=always", "submodule", "add", sub_repo, "mysub"],
            check=True,
            stdout=subprocess.DEVNULL,
        )
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", "add submodule"], check=True, stdout=subprocess.DEVNULL)

        # Add worktree with submodule
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/sub-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=dict(os.environ, GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="protocol.file.allow", GIT_CONFIG_VALUE_0="always"),
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        wt_path = os.path.join(self.repo_dir, ".worktrees", "feature", "sub-test")
        self.assertTrue(os.path.isdir(wt_path))
        self.assertTrue(os.path.isfile(os.path.join(wt_path, "mysub", "sub.txt")))

        # Test normal clean removal of worktree containing submodule WITHOUT --force
        proc_rm = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/sub-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm.returncode, 0, msg=proc_rm.stderr)
        self.assertFalse(os.path.isdir(wt_path))
        self.assertFalse(os.path.isdir(os.path.join(self.repo_dir, ".worktrees")))

    def test_add_worktree_copies_rnnoise_model(self):
        # Create mock RNNoise model files in root repo
        gen_dir = os.path.join(self.repo_dir, "libraries", "humla", "src", "main", "jni", "rnnoise-build", "generated")
        assets_dir = os.path.join(self.repo_dir, "libraries", "humla", "src", "main", "assets")
        cache_dir = os.path.join(self.repo_dir, "libraries", "humla", "build", "model_cache")
        os.makedirs(gen_dir, exist_ok=True)
        os.makedirs(assets_dir, exist_ok=True)
        os.makedirs(cache_dir, exist_ok=True)

        with open(os.path.join(gen_dir, "rnnoise_data.c"), "w", encoding="utf-8") as f:
            f.write("/* c weights */")
        with open(os.path.join(gen_dir, "rnnoise_data.h"), "w", encoding="utf-8") as f:
            f.write("/* h weights */")
        with open(os.path.join(assets_dir, "rnnoise_model.bin"), "wb") as f:
            f.write(b"mock_bin_weights")
        with open(os.path.join(cache_dir, "rnnoise_data-5e78411.tar.gz"), "wb") as f:
            f.write(b"mock_tar_gz")

        # Add to .gitignore so they are ignored, exactly as in the main repo
        gitignore_path = os.path.join(self.repo_dir, ".gitignore")
        with open(gitignore_path, "a", encoding="utf-8") as f:
            f.write("\nlibraries/humla/src/main/jni/rnnoise-build/generated/\n")
            f.write("libraries/humla/src/main/assets/rnnoise_model.bin\n")
            f.write("build/\n")
        subprocess.run(["git", "-C", self.repo_dir, "add", ".gitignore"], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", "ignore rnnoise files"], check=True, stdout=subprocess.DEVNULL)

        # Add worktree
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/rnnoise-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        self.assertIn("Copying existing RNNoise model weights from root repository...", proc_add.stdout)
        self.assertIn("RNNoise model files copied successfully.", proc_add.stdout)

        wt_path = os.path.join(self.repo_dir, ".worktrees", "feature", "rnnoise-test")
        wt_c = os.path.join(wt_path, "libraries", "humla", "src", "main", "jni", "rnnoise-build", "generated", "rnnoise_data.c")
        wt_h = os.path.join(wt_path, "libraries", "humla", "src", "main", "jni", "rnnoise-build", "generated", "rnnoise_data.h")
        wt_bin = os.path.join(wt_path, "libraries", "humla", "src", "main", "assets", "rnnoise_model.bin")
        wt_cache = os.path.join(wt_path, "libraries", "humla", "build", "model_cache", "rnnoise_data-5e78411.tar.gz")

        self.assertTrue(os.path.isfile(wt_c))
        self.assertTrue(os.path.isfile(wt_h))
        self.assertTrue(os.path.isfile(wt_bin))
        self.assertTrue(os.path.isfile(wt_cache))

        with open(wt_c, "r", encoding="utf-8") as f:
            self.assertEqual(f.read(), "/* c weights */")
        with open(wt_bin, "rb") as f:
            self.assertEqual(f.read(), b"mock_bin_weights")
        with open(wt_cache, "rb") as f:
            self.assertEqual(f.read(), b"mock_tar_gz")

        # Verify removal succeeds cleanly (copied ignored files don't block clean worktree removal)
        proc_rm = subprocess.run(
            [SCRIPT_PATH, "remove", "feature/rnnoise-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_rm.returncode, 0, msg=proc_rm.stderr)
        self.assertFalse(os.path.isdir(wt_path))

    def test_add_worktree_skips_when_rnnoise_missing(self):
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/no-rnnoise-test"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        self.assertIn("No existing RNNoise model found in root repository", proc_add.stdout)

    def test_add_worktree_skips_on_version_mismatch(self):
        # Create version file on master
        ver_dir = os.path.join(self.repo_dir, "libraries", "humla", "src", "main", "jni", "rnnoise")
        os.makedirs(ver_dir, exist_ok=True)
        ver_file = os.path.join(ver_dir, "model_version")
        with open(ver_file, "w", encoding="utf-8") as f:
            f.write("hash_v1\n")
        subprocess.run(["git", "-C", self.repo_dir, "add", "."], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", "add v1 model_version"], check=True, stdout=subprocess.DEVNULL)

        # Create mock model files in root repo
        gen_dir = os.path.join(self.repo_dir, "libraries", "humla", "src", "main", "jni", "rnnoise-build", "generated")
        assets_dir = os.path.join(self.repo_dir, "libraries", "humla", "src", "main", "assets")
        os.makedirs(gen_dir, exist_ok=True)
        os.makedirs(assets_dir, exist_ok=True)
        with open(os.path.join(gen_dir, "rnnoise_data.c"), "w", encoding="utf-8") as f:
            f.write("/* c weights v1 */")
        with open(os.path.join(gen_dir, "rnnoise_data.h"), "w", encoding="utf-8") as f:
            f.write("/* h weights v1 */")
        with open(os.path.join(assets_dir, "rnnoise_model.bin"), "wb") as f:
            f.write(b"weights_v1")

        # Create branch with different model version
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "-b", "feature/version-bump"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(ver_file, "w", encoding="utf-8") as f:
            f.write("hash_v2\n")
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-am", "bump model to v2"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "master"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        # Add worktree tracking feature/version-bump
        proc_add = subprocess.run(
            [SCRIPT_PATH, "add", "feature/version-bump"],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc_add.returncode, 0, msg=proc_add.stderr)
        self.assertIn("Notice: RNNoise model version mismatch (hash_v1 vs hash_v2). Skipping model copy.", proc_add.stdout)

        # Verify files were NOT copied
        wt_c = os.path.join(self.repo_dir, ".worktrees", "feature", "version-bump", "libraries", "humla", "src", "main", "jni", "rnnoise-build", "generated", "rnnoise_data.c")
        self.assertFalse(os.path.exists(wt_c))


class TestWorktreeUnit(TempRepoTestBase):

    def test_get_repo_root(self):
        resolved = worktree.get_repo_root(cwd=self.repo_dir)
        self.assertEqual(resolved, self.repo_dir.resolve())

    def test_find_worktree_path_direct_dir(self):
        self.assertEqual(
            worktree.find_worktree_path(self.repo_dir, str(self.repo_dir)),
            self.repo_dir.resolve()
        )

    def test_find_worktree_path_nonexistent(self):
        self.assertIsNone(
            worktree.find_worktree_path(self.repo_dir, "nonexistent-branch")
        )

    def test_find_worktree_path_repo_isolation(self):
        other_repo = Path(self.temp_dir.name) / "other_repo"
        other_repo.mkdir()
        subprocess.run(["git", "init", "-b", "isolated-branch", str(other_repo)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", str(other_repo), "config", "user.name", "Test Agent"], check=True)
        subprocess.run(["git", "-C", str(other_repo), "config", "user.email", "agent@example.com"], check=True)
        (other_repo / "file.txt").write_text("isolated\n")
        subprocess.run(["git", "-C", str(other_repo), "add", "."], check=True)
        subprocess.run(["git", "-C", str(other_repo), "commit", "-m", "isolated commit"], check=True, stdout=subprocess.DEVNULL)

        # Calling find_worktree_path for self.repo_dir looking for 'isolated-branch'
        # must return None even when the current process working directory is other_repo
        old_cwd = os.getcwd()
        try:
            os.chdir(str(other_repo))
            self.assertIsNone(worktree.find_worktree_path(self.repo_dir, "isolated-branch"))
        finally:
            os.chdir(old_cwd)

    def test_cmd_list_piped_order(self):
        proc = subprocess.run(
            [SCRIPT_PATH, "list"],
            cwd=str(self.repo_dir),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        self.assertEqual(proc.returncode, 0)
        header_pos = proc.stdout.find("Active Git Worktrees")
        master_pos = proc.stdout.find("master")
        self.assertGreater(header_pos, -1, "Header 'Active Git Worktrees' not found")
        self.assertGreater(master_pos, header_pos, "Header must precede worktree entries")


class TestWorktreeCleanup(TempRepoTestBase):

    def _commit_on_branch(self, branch, filename, content, message):
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "-b", branch], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(os.path.join(self.repo_dir, filename), "w", encoding="utf-8") as f:
            f.write(content)
        subprocess.run(["git", "-C", self.repo_dir, "add", "."], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", message], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "master"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _add_worktree(self, branch):
        proc = subprocess.run(
            [SCRIPT_PATH, "add", branch],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        return os.path.join(self.repo_dir, ".worktrees", branch)

    def _run_cleanup(self, *args, subcommand="cleanup"):
        return subprocess.run(
            [SCRIPT_PATH, subcommand, *args],
            cwd=self.repo_dir,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )

    def _branch_exists(self, branch):
        return subprocess.run(
            ["git", "-C", self.repo_dir, "show-ref", "--verify", f"refs/heads/{branch}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        ).returncode == 0

    def test_cleanup_removes_merged_clean(self):
        self._commit_on_branch("feature/merged", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/merged", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/merged")
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/merged: removed (merged,clean)", proc.stdout, msg=proc.stdout)
        self.assertIn("branch preserved", proc.stdout, msg=proc.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/merged"))

    def test_cleanup_preserves_unmerged_by_default(self):
        self._commit_on_branch("feature/unmerged", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/unmerged")
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/unmerged: preserved (unmerged,clean", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged"))
        # Opt-in flag removes it; branch is still preserved.
        proc2 = self._run_cleanup("--include-unmerged")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertIn("feature/unmerged: removed (unmerged,clean)", proc2.stdout, msg=proc2.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged"))

    def test_cleanup_explicit_unmerged_target_fails_without_flag(self):
        self._commit_on_branch("feature/unmerged2", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/unmerged2")
        proc = self._run_cleanup("feature/unmerged2")
        self.assertEqual(proc.returncode, 1, msg=proc.stderr)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged2"))
        proc2 = self._run_cleanup("--include-unmerged", "feature/unmerged2")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/unmerged2"))

    def test_cleanup_dirty_needs_force(self):
        self._commit_on_branch("feature/dirty-merged", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/dirty-merged", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/dirty-merged")
        with open(os.path.join(wt, "dirty.txt"), "w", encoding="utf-8") as f:
            f.write("untracked\n")
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dirty-merged: preserved (merged,dirty", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        proc2 = self._run_cleanup("--force")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/dirty-merged"))

    def test_cleanup_dirty_unmerged_needs_both_flags(self):
        self._commit_on_branch("feature/dirty-unmerged", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/dirty-unmerged")
        with open(os.path.join(wt, "dirty.txt"), "w", encoding="utf-8") as f:
            f.write("untracked\n")
        proc_default = self._run_cleanup()
        self.assertEqual(proc_default.returncode, 0, msg=proc_default.stderr)
        self.assertTrue(os.path.isdir(wt))
        proc_unmerged = self._run_cleanup("--include-unmerged")
        self.assertEqual(proc_unmerged.returncode, 0, msg=proc_unmerged.stderr)
        self.assertTrue(os.path.isdir(wt))
        proc_force = self._run_cleanup("--force")
        self.assertEqual(proc_force.returncode, 0, msg=proc_force.stderr)
        self.assertTrue(os.path.isdir(wt))
        proc = self._run_cleanup("--include-unmerged", "--force")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dirty-unmerged: removed (unmerged,dirty)", proc.stdout, msg=proc.stdout)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/dirty-unmerged"))

    def test_cleanup_dry_run_is_noop(self):
        self._commit_on_branch("feature/dry", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/dry", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/dry")
        proc = self._run_cleanup("--dry-run")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dry: would remove (merged,clean)", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/dry"))

    def test_cleanup_explicit_dry_run_skip_exits_zero(self):
        # Previewing a skipped target deletes nothing, so it must not fail.
        self._commit_on_branch("feature/dry-skip", "feat.txt", "unmerged\n", "feat")
        wt = self._add_worktree("feature/dry-skip")
        proc = self._run_cleanup("--dry-run", "feature/dry-skip")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/dry-skip: would preserve (unmerged,clean", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/dry-skip"))

    def test_cleanup_unknown_target_fails(self):
        proc = self._run_cleanup("nonexistent-branch")
        self.assertEqual(proc.returncode, 1, msg=proc.stdout)
        self.assertIn("Could not find worktree", proc.stderr)

    def test_cleanup_no_secondary_worktrees(self):
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("No secondary worktrees", proc.stdout)

    def test_cleanup_short_flags_and_alias(self):
        self._commit_on_branch("feature/shorthand", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/shorthand", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/shorthand")
        proc = self._run_cleanup("-n", subcommand="clean")
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn("feature/shorthand: would remove (merged,clean)", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        with open(os.path.join(wt, "dirty.txt"), "w", encoding="utf-8") as f:
            f.write("untracked\n")
        proc2 = self._run_cleanup("-f", subcommand="clean")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/shorthand"))

    def test_cleanup_path_target(self):
        self._commit_on_branch("feature/by-path", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/by-path", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/by-path")
        proc = self._run_cleanup(wt)
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/by-path"))

    def test_cleanup_detached_needs_include_unmerged(self):
        wt = os.path.join(self.repo_str, ".worktrees", "detached-one")
        subprocess.run(
            ["git", "-C", self.repo_str, "worktree", "add", "--detach", wt, "master"],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertIn(",detached", proc.stdout, msg=proc.stdout)
        self.assertTrue(os.path.isdir(wt))
        proc2 = self._run_cleanup("--include-unmerged")
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))

    def test_cleanup_locked_worktree_preserved(self):
        self._commit_on_branch("feature/locked", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/locked", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/locked")
        subprocess.run(["git", "-C", self.repo_str, "worktree", "lock", wt], check=True, stdout=subprocess.DEVNULL)
        try:
            proc = self._run_cleanup()
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            self.assertIn("feature/locked: preserved (worktree is locked", proc.stdout, msg=proc.stdout)
            self.assertTrue(os.path.isdir(wt))
            proc_explicit = self._run_cleanup("feature/locked")
            self.assertEqual(proc_explicit.returncode, 1, msg=proc_explicit.stdout)
            self.assertTrue(os.path.isdir(wt))
        finally:
            subprocess.run(["git", "-C", self.repo_str, "worktree", "unlock", wt], check=True, stdout=subprocess.DEVNULL)
        proc2 = self._run_cleanup()
        self.assertEqual(proc2.returncode, 0, msg=proc2.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/locked"))

    def test_cleanup_inside_worktree_preserved(self):
        self._commit_on_branch("feature/inside", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/inside", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/inside")
        old_cwd = os.getcwd()
        try:
            os.chdir(wt)
            proc = subprocess.run(
                [SCRIPT_PATH, "cleanup"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
            )
            self.assertEqual(proc.returncode, 0, msg=proc.stderr)
            self.assertIn("currently inside", proc.stdout, msg=proc.stdout)
        finally:
            os.chdir(old_cwd)
        self.assertTrue(os.path.isdir(wt))
        self.assertTrue(self._branch_exists("feature/inside"))

    def test_cleanup_sweeps_nested_empty_dirs(self):
        self._commit_on_branch("feature/sweep", "feat.txt", "merged\n", "feat")
        subprocess.run(["git", "-C", self.repo_dir, "merge", "--no-ff", "feature/sweep", "-m", "merge"], check=True, stdout=subprocess.DEVNULL)
        wt = self._add_worktree("feature/sweep")
        # Simulate Gradle residue: nested empty chains git does not track.
        residue = os.path.join(self.repo_str, ".worktrees", "stray", "a", "b")
        os.makedirs(residue)
        proc = self._run_cleanup()
        self.assertEqual(proc.returncode, 0, msg=proc.stderr)
        self.assertFalse(os.path.isdir(wt))
        self.assertFalse(os.path.isdir(os.path.join(self.repo_str, ".worktrees")))
        self.assertTrue(self._branch_exists("feature/sweep"))


if __name__ == "__main__":
    unittest.main()


