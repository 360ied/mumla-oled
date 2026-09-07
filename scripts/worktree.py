#!/usr/bin/env python3
# Copyright (C) 2026 Mumla OLED Contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
"""
scripts/worktree.py: Helper script for managing Git worktrees in Mumla OLED.

Worktrees allow developing on dedicated feature/bugfix branches without
modifying the main working tree (which remains permanently on 'master').
"""

import functools
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import List, Optional, TextIO

# Configure unbuffered/line-buffered I/O so CLI output and child process
# output maintain strict ordering even when piped or redirected.
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except Exception:
        pass
if hasattr(sys.stderr, "reconfigure"):
    try:
        sys.stderr.reconfigure(line_buffering=True)
    except Exception:
        pass
print = functools.partial(print, flush=True)


def print_usage(stream: TextIO) -> None:
    prog = "./scripts/worktree.sh"
    print(f"""Usage:
  {prog} add <branch-name> [base-ref] [-p <custom-path>]
  {prog} list
  {prog} remove <branch-name-or-path> [--force]

Commands:
  add       Create a new worktree under .worktrees/<branch-name> (or custom path)
            and automatically initialize all required git submodules.
            If base-ref is omitted, it defaults to 'master'.
  list      List all active worktrees and their checked-out branches.
  remove    Safely remove a worktree. Refuses if there are uncommitted changes
            unless --force is specified. Never deletes the git branch.

Examples:
  {prog} add feature/vad-tuning
  {prog} add bugfix/opus-resampler master
  {prog} list
  {prog} remove feature/vad-tuning""", file=stream)


def get_repo_root(cwd: Optional[Path] = None) -> Path:
    """Resolve the root repository directory (common git dir)."""
    res = subprocess.run(
        ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    common_dir = res.stdout.strip()
    if res.returncode != 0 or not common_dir:
        # Fallback to --show-toplevel
        res2 = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        if res2.returncode != 0 or not res2.stdout.strip():
            sys.stderr.write("Error: Not inside a git repository.\n")
            sys.exit(1)
        return Path(res2.stdout.strip()).resolve()

    # Strip /.git and any trailing submodule/worktree path components
    # Equivalent to sed -E 's#/\.git(/.*)?$##'
    root_str = re.sub(r"/+\.git(/.*)?$", "", common_dir)
    return Path(root_str).resolve()


def copy_rnnoise_model(repo_root: Path, wt_path: Path) -> None:
    """Copy existing RNNoise pre-trained model weights from root repo if available."""
    src_gen_dir = repo_root / "libraries" / "humla" / "src" / "main" / "jni" / "rnnoise-build" / "generated"
    src_asset = repo_root / "libraries" / "humla" / "src" / "main" / "assets" / "rnnoise_model.bin"
    src_cache_dir = repo_root / "libraries" / "humla" / "build" / "model_cache"

    dst_gen_dir = wt_path / "libraries" / "humla" / "src" / "main" / "jni" / "rnnoise-build" / "generated"
    dst_asset_dir = wt_path / "libraries" / "humla" / "src" / "main" / "assets"
    dst_cache_dir = wt_path / "libraries" / "humla" / "build" / "model_cache"

    root_ver_file = repo_root / "libraries" / "humla" / "src" / "main" / "jni" / "rnnoise" / "model_version"
    wt_ver_file = wt_path / "libraries" / "humla" / "src" / "main" / "jni" / "rnnoise" / "model_version"

    if root_ver_file.is_file() and wt_ver_file.is_file():
        root_ver = "".join(root_ver_file.read_text().split())
        wt_ver = "".join(wt_ver_file.read_text().split())
        if root_ver and wt_ver and root_ver != wt_ver:
            print(f"Notice: RNNoise model version mismatch ({root_ver} vs {wt_ver}). Skipping model copy.")
            return

    copied = False
    c_file = src_gen_dir / "rnnoise_data.c"
    h_file = src_gen_dir / "rnnoise_data.h"
    if c_file.is_file() and h_file.is_file() and src_asset.is_file():
        print("Copying existing RNNoise model weights from root repository...")
        dst_gen_dir.mkdir(parents=True, exist_ok=True)
        dst_asset_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(c_file, dst_gen_dir / "rnnoise_data.c")
        shutil.copy2(h_file, dst_gen_dir / "rnnoise_data.h")
        shutil.copy2(src_asset, dst_asset_dir / "rnnoise_model.bin")
        copied = True

    tarballs = sorted(src_cache_dir.glob("rnnoise_data-*.tar.gz"))
    if tarballs:
        dst_cache_dir.mkdir(parents=True, exist_ok=True)
        for tb in tarballs:
            shutil.copy2(tb, dst_cache_dir / tb.name)
        copied = True

    if copied:
        print("RNNoise model files copied successfully.")
    else:
        print("No existing RNNoise model found in root repository (will download on first build).")


def find_worktree_path(repo_root: Path, target: str) -> Optional[Path]:
    """Find a worktree directory given a path or branch name."""
    target_path = Path(target)
    if target_path.is_dir():
        return target_path.resolve()

    candidate = repo_root / ".worktrees" / target
    if candidate.is_dir():
        return candidate.resolve()

    proc = subprocess.run(
        ["git", "worktree", "list", "--porcelain"],
        cwd=repo_root,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if proc.returncode == 0:
        current_wt = None
        for line in proc.stdout.splitlines():
            line = line.strip()
            if not line:
                current_wt = None
            elif line.startswith("worktree "):
                current_wt = line.split(" ", 1)[1].strip()
            elif line.startswith("branch "):
                ref = line.split(" ", 1)[1].strip()
                if ref == f"refs/heads/{target}" and current_wt:
                    p = Path(current_wt)
                    if p.is_dir():
                        return p.resolve()
    return None


def cmd_add(args: List[str], repo_root: Path) -> int:
    branch = ""
    base_ref = ""
    custom_path = ""

    idx = 0
    while idx < len(args):
        arg = args[idx]
        if arg in ("-p", "--path"):
            if idx + 1 < len(args):
                custom_path = args[idx + 1]
                idx += 2
                continue
            else:
                sys.stderr.write("Error: --path requires a path argument.\n")
                return 1
        elif arg in ("-h", "--help"):
            print_usage(sys.stdout)
            return 0
        elif arg.startswith("-"):
            sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
            return 1
        else:
            if not branch:
                branch = arg
            elif not base_ref:
                base_ref = arg
            else:
                sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
                return 1
            idx += 1

    if not branch:
        sys.stderr.write("Error: Branch name is required.\n")
        sys.stderr.write("Usage: ./scripts/worktree.sh add <branch-name> [base-ref] [-p <path>]\n")
        return 1

    if branch == "master":
        sys.stderr.write("Error: Cannot create a worktree for 'master'. The root tree is dedicated to master.\n")
        return 1

    if custom_path:
        wt_path = Path(custom_path)
    else:
        wt_path = repo_root / ".worktrees" / branch

    if wt_path.exists():
        sys.stderr.write(f"Error: Target directory '{wt_path}' already exists.\n")
        return 1

    print("========================================")
    print(" 1. Creating Git Worktree")
    print("========================================")
    print(f"Branch: {branch}")
    print(f"Path:   {wt_path}")

    try:
        res_local = subprocess.run(
            ["git", "show-ref", "--verify", "--quiet", f"refs/heads/{branch}"],
            cwd=repo_root,
            check=False,
        )
        if res_local.returncode == 0:
            print(f"Branch '{branch}' already exists locally. Checking out in worktree...")
            subprocess.run(["git", "worktree", "add", str(wt_path), branch], cwd=repo_root, check=True)
        else:
            res_remote = subprocess.run(
                ["git", "show-ref", "--verify", "--quiet", f"refs/remotes/origin/{branch}"],
                cwd=repo_root,
                check=False,
            )
            if res_remote.returncode == 0:
                print(f"Branch '{branch}' exists on origin. Tracking in new worktree...")
                subprocess.run(["git", "worktree", "add", "-b", branch, str(wt_path), f"origin/{branch}"], cwd=repo_root, check=True)
            else:
                start_point = base_ref if base_ref else "master"
                print(f"Creating new branch '{branch}' from '{start_point}'...")
                subprocess.run(["git", "worktree", "add", "-b", branch, str(wt_path), start_point], cwd=repo_root, check=True)

        print("")
        print("========================================")
        print(" 2. Initializing Git Submodules")
        print("========================================")
        subprocess.run(["git", "-C", str(wt_path), "submodule", "update", "--init", "--recursive"], check=True)

        print("")
        print("========================================")
        print(" 3. Copying Pre-trained RNNoise Model")
        print("========================================")
        copy_rnnoise_model(repo_root, wt_path)

        # Allow direnv if direnv is installed
        if shutil.which("direnv"):
            subprocess.run(["direnv", "allow"], cwd=str(wt_path), check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        print("")
        print("========================================")
        print(" WORKTREE READY!")
        print("========================================")
        print(f"Path:   {wt_path}")
        print(f"Branch: {branch}")
        print("")
        print("To start working in this worktree:")
        print(f"  cd \"{wt_path}\"")
        print("")
        print("To verify changes in this worktree:")
        print(f"  cd \"{wt_path}\" && ./scripts/check.sh")
        print("")
        print("To remove when finished:")
        print(f"  ./scripts/worktree.sh remove \"{branch}\"")
        print("========================================")
        return 0
    except subprocess.CalledProcessError as e:
        return e.returncode


def cmd_list(repo_root: Optional[Path] = None) -> int:
    if repo_root is None:
        repo_root = get_repo_root()
    print("========================================")
    print(" Active Git Worktrees")
    print("========================================")
    proc = subprocess.run(["git", "worktree", "list"], cwd=repo_root)
    return proc.returncode


def cmd_remove(args: List[str], repo_root: Path) -> int:
    target = ""
    force = False

    idx = 0
    while idx < len(args):
        arg = args[idx]
        if arg in ("-f", "--force"):
            force = True
            idx += 1
        elif arg in ("-h", "--help"):
            print_usage(sys.stdout)
            return 0
        elif arg.startswith("-"):
            sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
            return 1
        else:
            if not target:
                target = arg
            else:
                sys.stderr.write(f"Error: Unexpected argument '{arg}'.\n")
                return 1
            idx += 1

    if not target:
        sys.stderr.write("Error: Worktree branch or path is required.\n")
        sys.stderr.write("Usage: ./scripts/worktree.sh remove <branch-name-or-path> [--force]\n")
        return 1

    wt_path = find_worktree_path(repo_root, target)
    if not wt_path or not wt_path.is_dir():
        sys.stderr.write(f"Error: Could not find worktree for '{target}'.\n")
        return 1

    if wt_path == repo_root:
        sys.stderr.write("Error: Cannot remove the primary root worktree!\n")
        return 1

    # Check for uncommitted changes
    if not force:
        status_proc = subprocess.run(
            ["git", "-C", str(wt_path), "status", "--porcelain"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        if status_proc.stdout.strip():
            sys.stderr.write(f"Error: Worktree at '{wt_path}' contains uncommitted changes:\n")
            sys.stderr.write(status_proc.stdout)
            sys.stderr.write("Commit or stash changes before removing, or use --force.\n")
            return 1

    branch_proc = subprocess.run(
        ["git", "-C", str(wt_path), "rev-parse", "--abbrev-ref", "HEAD"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    branch_name = branch_proc.stdout.strip() if branch_proc.returncode == 0 else ""

    print(f"Removing worktree at '{wt_path}'...")
    # Always pass --force to git worktree remove because Git forbids removing
    # worktrees with submodules otherwise. The status check above guarantees safety.
    try:
        subprocess.run(["git", "worktree", "remove", "--force", str(wt_path)], cwd=repo_root, check=True)
        subprocess.run(["git", "worktree", "prune"], cwd=repo_root, check=True)

        # Clean up empty parent directories inside .worktrees/ if applicable
        worktrees_dir = (repo_root / ".worktrees").resolve()
        try:
            wt_path.relative_to(worktrees_dir)
            curr = wt_path.parent
            while curr != worktrees_dir and curr != repo_root and curr.is_dir():
                try:
                    curr.rmdir()
                except OSError:
                    break
                curr = curr.parent
            try:
                worktrees_dir.rmdir()
            except OSError:
                pass
        except ValueError:
            pass

        print("Worktree removed successfully.")
        if branch_name and branch_name != "HEAD":
            print("")
            print(f"Note: Local branch '{branch_name}' has been preserved.")
            print("To delete it when fully merged, run:")
            print(f"  git branch -d {branch_name}")
        return 0
    except subprocess.CalledProcessError as e:
        return e.returncode


def main(argv: Optional[List[str]] = None) -> int:
    if argv is None:
        argv = sys.argv[1:]

    if not argv:
        print_usage(sys.stderr)
        return 1

    subcmd = argv[0]
    rest = argv[1:]

    if subcmd in ("-h", "--help"):
        print_usage(sys.stdout)
        return 0

    if subcmd == "add":
        repo_root = get_repo_root()
        return cmd_add(rest, repo_root)
    elif subcmd in ("list", "ls"):
        repo_root = get_repo_root()
        return cmd_list(repo_root)
    elif subcmd in ("remove", "rm"):
        repo_root = get_repo_root()
        return cmd_remove(rest, repo_root)
    else:
        sys.stderr.write(f"Error: Unknown command '{subcmd}'.\n")
        print_usage(sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
