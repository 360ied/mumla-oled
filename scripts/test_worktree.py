#!/usr/bin/env python3
"""
Unit tests for scripts/worktree.sh.
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

SCRIPT_PATH_SH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "worktree.sh")
)
SCRIPT_PATH_PY = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "worktree.py")
)
SCRIPT_PATHS = [SCRIPT_PATH_SH, SCRIPT_PATH_PY]
SCRIPT_PATH = SCRIPT_PATH_SH


class TestWorktreeCLI(unittest.TestCase):
    def test_help_exits_zero(self):
        for script in SCRIPT_PATHS:
            with self.subTest(script=script):
                for flag in ["-h", "--help"]:
                    proc = subprocess.run(
                        [script, flag],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True,
                    )
                    self.assertEqual(proc.returncode, 0)
                    self.assertIn("Usage:", proc.stdout)
                    self.assertIn("add", proc.stdout)
                    self.assertIn("remove", proc.stdout)

    def test_no_args_exits_one(self):
        for script in SCRIPT_PATHS:
            with self.subTest(script=script):
                proc = subprocess.run(
                    [script],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                self.assertEqual(proc.returncode, 1)
                self.assertIn("Usage:", proc.stderr)

    def test_unknown_command_exits_one(self):
        for script in SCRIPT_PATHS:
            with self.subTest(script=script):
                proc = subprocess.run(
                    [script, "foobar"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                self.assertEqual(proc.returncode, 1)
                self.assertIn("Unknown command 'foobar'", proc.stderr)

    def test_add_missing_branch(self):
        for script in SCRIPT_PATHS:
            with self.subTest(script=script):
                proc = subprocess.run(
                    [script, "add"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                self.assertEqual(proc.returncode, 1)
                self.assertIn("Branch name is required", proc.stderr)

    def test_add_master_disallowed(self):
        for script in SCRIPT_PATHS:
            with self.subTest(script=script):
                proc = subprocess.run(
                    [script, "add", "master"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                )
                self.assertEqual(proc.returncode, 1)
                self.assertIn("Cannot create a worktree for 'master'", proc.stderr)

    def test_remove_missing_target(self):
        for script in SCRIPT_PATHS:
            with self.subTest(script=script):
                proc = subprocess.run(
                    [script, "remove"],
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
        with open(dirty_file, "w") as f:
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
        with open(os.path.join(sub_repo, "sub.txt"), "w") as f:
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

        with open(os.path.join(gen_dir, "rnnoise_data.c"), "w") as f:
            f.write("/* c weights */")
        with open(os.path.join(gen_dir, "rnnoise_data.h"), "w") as f:
            f.write("/* h weights */")
        with open(os.path.join(assets_dir, "rnnoise_model.bin"), "wb") as f:
            f.write(b"mock_bin_weights")
        with open(os.path.join(cache_dir, "rnnoise_data-5e78411.tar.gz"), "wb") as f:
            f.write(b"mock_tar_gz")

        # Add to .gitignore so they are ignored, exactly as in the main repo
        gitignore_path = os.path.join(self.repo_dir, ".gitignore")
        with open(gitignore_path, "a") as f:
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

        with open(wt_c, "r") as f:
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
        with open(ver_file, "w") as f:
            f.write("hash_v1\n")
        subprocess.run(["git", "-C", self.repo_dir, "add", "."], check=True)
        subprocess.run(["git", "-C", self.repo_dir, "commit", "-m", "add v1 model_version"], check=True, stdout=subprocess.DEVNULL)

        # Create mock model files in root repo
        gen_dir = os.path.join(self.repo_dir, "libraries", "humla", "src", "main", "jni", "rnnoise-build", "generated")
        assets_dir = os.path.join(self.repo_dir, "libraries", "humla", "src", "main", "assets")
        os.makedirs(gen_dir, exist_ok=True)
        os.makedirs(assets_dir, exist_ok=True)
        with open(os.path.join(gen_dir, "rnnoise_data.c"), "w") as f:
            f.write("/* c weights v1 */")
        with open(os.path.join(gen_dir, "rnnoise_data.h"), "w") as f:
            f.write("/* h weights v1 */")
        with open(os.path.join(assets_dir, "rnnoise_model.bin"), "wb") as f:
            f.write(b"weights_v1")

        # Create branch with different model version
        subprocess.run(["git", "-C", self.repo_dir, "checkout", "-b", "feature/version-bump"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(ver_file, "w") as f:
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


class TestWorktreeUnit(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.repo_dir = Path(self.temp_dir.name) / "repo"
        self.repo_dir.mkdir()

        subprocess.run(["git", "init", "-b", "master", str(self.repo_dir)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", str(self.repo_dir), "config", "user.name", "Test Agent"], check=True)
        subprocess.run(["git", "-C", str(self.repo_dir), "config", "user.email", "agent@example.com"], check=True)

        readme = self.repo_dir / "README.md"
        readme.write_text("# Test Repo\n")
        subprocess.run(["git", "-C", str(self.repo_dir), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.repo_dir), "commit", "-m", "init"], check=True, stdout=subprocess.DEVNULL)

    def tearDown(self):
        self.temp_dir.cleanup()

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
        for script in SCRIPT_PATHS:
            with self.subTest(script=script):
                proc = subprocess.run(
                    [script, "list"],
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


if __name__ == "__main__":
    unittest.main()


