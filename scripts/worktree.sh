#!/usr/bin/env bash
#
# scripts/worktree.sh: Helper script for managing Git worktrees in Mumla OLED.
#
# Worktrees allow developing on dedicated feature/bugfix branches without
# modifying the main working tree (which remains permanently on 'master').
#
set -euo pipefail

usage() {
    cat <<'EOF'
Usage:
  ./scripts/worktree.sh add <branch-name> [base-ref] [-p <custom-path>]
  ./scripts/worktree.sh list
  ./scripts/worktree.sh remove <branch-name-or-path> [--force]

Commands:
  add       Create a new worktree under .worktrees/<branch-name> (or custom path)
            and automatically initialize all required git submodules.
            If base-ref is omitted, it defaults to 'master'.
  list      List all active worktrees and their checked-out branches.
  remove    Safely remove a worktree. Refuses if there are uncommitted changes
            unless --force is specified. Never deletes the git branch.

Examples:
  ./scripts/worktree.sh add feature/vad-tuning
  ./scripts/worktree.sh add bugfix/opus-resampler master
  ./scripts/worktree.sh list
  ./scripts/worktree.sh remove feature/vad-tuning
EOF
}

# Resolve the root repository directory (common git dir)
get_repo_root() {
    local common_dir
    common_dir="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
    if [ -z "$common_dir" ]; then
        echo "Error: Not inside a git repository." >&2
        exit 1
    fi
    # If common_dir is /path/to/repo/.git, strip /.git
    sed 's#/\.git$##' <<< "$common_dir"
}

cmd_add() {
    local branch=""
    local base_ref=""
    local custom_path=""

    while [ $# -gt 0 ]; do
        case "$1" in
            -p|--path)
                if [ -n "${2:-}" ]; then
                    custom_path="$2"
                    shift 2
                else
                    echo "Error: --path requires a path argument." >&2
                    exit 1
                fi
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                if [ -z "$branch" ]; then
                    branch="$1"
                elif [ -z "$base_ref" ]; then
                    base_ref="$1"
                else
                    echo "Error: Unexpected argument '$1'." >&2
                    exit 1
                fi
                shift
                ;;
        esac
    done

    if [ -z "$branch" ]; then
        echo "Error: Branch name is required." >&2
        echo "Usage: ./scripts/worktree.sh add <branch-name> [base-ref] [-p <path>]" >&2
        exit 1
    fi

    if [ "$branch" == "master" ]; then
        echo "Error: Cannot create a worktree for 'master'. The root tree is dedicated to master." >&2
        exit 1
    fi

    local repo_root
    repo_root="$(get_repo_root)"

    local wt_path
    if [ -n "$custom_path" ]; then
        wt_path="$custom_path"
    else
        wt_path="$repo_root/.worktrees/$branch"
    fi

    if [ -d "$wt_path" ]; then
        echo "Error: Target directory '$wt_path' already exists." >&2
        exit 1
    fi

    echo "========================================"
    echo " 1. Creating Git Worktree"
    echo "========================================"
    echo "Branch: $branch"
    echo "Path:   $wt_path"

    # Check if branch exists locally or on origin
    if git show-ref --verify --quiet "refs/heads/$branch"; then
        echo "Branch '$branch' already exists locally. Checking out in worktree..."
        git worktree add "$wt_path" "$branch"
    elif git show-ref --verify --quiet "refs/remotes/origin/$branch"; then
        echo "Branch '$branch' exists on origin. Tracking in new worktree..."
        git worktree add -b "$branch" "$wt_path" "origin/$branch"
    else
        local start_point="${base_ref:-master}"
        echo "Creating new branch '$branch' from '$start_point'..."
        git worktree add -b "$branch" "$wt_path" "$start_point"
    fi

    echo ""
    echo "========================================"
    echo " 2. Initializing Git Submodules"
    echo "========================================"
    # Uses local .git/modules objects without re-cloning over network
    git -C "$wt_path" submodule update --init --recursive

    # Allow direnv if direnv is installed
    if command -v direnv >/dev/null 2>&1; then
        (cd "$wt_path" && direnv allow 2>/dev/null || true)
    fi

    echo ""
    echo "========================================"
    echo " WORKTREE READY!"
    echo "========================================"
    echo "Path:   $wt_path"
    echo "Branch: $branch"
    echo ""
    echo "To start working in this worktree:"
    echo "  cd \"$wt_path\""
    echo ""
    echo "To verify changes in this worktree:"
    echo "  cd \"$wt_path\" && ./scripts/check.sh"
    echo ""
    echo "To remove when finished:"
    echo "  ./scripts/worktree.sh remove \"$branch\""
    echo "========================================"
}

cmd_list() {
    echo "========================================"
    echo " Active Git Worktrees"
    echo "========================================"
    git worktree list
}

cmd_remove() {
    local target=""
    local force=false

    while [ $# -gt 0 ]; do
        case "$1" in
            -f|--force)
                force=true
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                if [ -z "$target" ]; then
                    target="$1"
                else
                    echo "Error: Unexpected argument '$1'." >&2
                    exit 1
                fi
                shift
                ;;
        esac
    done

    if [ -z "$target" ]; then
        echo "Error: Worktree branch or path is required." >&2
        echo "Usage: ./scripts/worktree.sh remove <branch-name-or-path> [--force]" >&2
        exit 1
    fi

    local repo_root
    repo_root="$(get_repo_root)"

    local wt_path=""
    if [ -d "$target" ]; then
        wt_path="$(cd "$target" && pwd)"
    elif [ -d "$repo_root/.worktrees/$target" ]; then
        wt_path="$(cd "$repo_root/.worktrees/$target" && pwd)"
    else
        # Try matching from git worktree list
        local matched_path
        matched_path="$(git worktree list --porcelain | awk -v tgt="$target" '
            $1 == "worktree" { wt=$2 }
            $1 == "branch" && $2 ~ "refs/heads/" tgt "$" { print wt }
        ')"
        if [ -n "$matched_path" ] && [ -d "$matched_path" ]; then
            wt_path="$matched_path"
        fi
    fi

    if [ -z "$wt_path" ] || [ ! -d "$wt_path" ]; then
        echo "Error: Could not find worktree for '$target'." >&2
        exit 1
    fi

    if [ "$wt_path" == "$repo_root" ]; then
        echo "Error: Cannot remove the primary root worktree!" >&2
        exit 1
    fi

    # Check for uncommitted changes
    if [ "$force" = false ]; then
        local status_out
        status_out="$(git -C "$wt_path" status --porcelain 2>/dev/null || true)"
        if [ -n "$status_out" ]; then
            echo "Error: Worktree at '$wt_path' contains uncommitted changes:" >&2
            echo "$status_out" >&2
            echo "Commit or stash changes before removing, or use --force." >&2
            exit 1
        fi
    fi

    local branch_name
    branch_name="$(git -C "$wt_path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"

    echo "Removing worktree at '$wt_path'..."
    if [ "$force" = true ]; then
        git worktree remove --force "$wt_path"
    else
        git worktree remove "$wt_path"
    fi
    git worktree prune

    # Clean up empty parent directories inside .worktrees/ if applicable
    if [[ "$wt_path" == "$repo_root/.worktrees/"* ]]; then
        local parent_dir
        parent_dir="$(dirname "$wt_path")"
        if [ "$parent_dir" != "$repo_root/.worktrees" ] && [ -d "$parent_dir" ]; then
            rmdir "$parent_dir" 2>/dev/null || true
        fi
        rmdir "$repo_root/.worktrees" 2>/dev/null || true
    fi

    echo "Worktree removed successfully."
    if [ -n "$branch_name" ] && [ "$branch_name" != "HEAD" ]; then
        echo ""
        echo "Note: Local branch '$branch_name' has been preserved."
        echo "To delete it when fully merged, run:"
        echo "  git branch -d $branch_name"
    fi
}

main() {
    if [ $# -eq 0 ]; then
        usage >&2
        exit 1
    fi

    local subcmd="$1"
    shift

    case "$subcmd" in
        add)
            cmd_add "$@"
            ;;
        list|ls)
            cmd_list "$@"
            ;;
        remove|rm)
            cmd_remove "$@"
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Error: Unknown command '$subcmd'." >&2
            usage >&2
            exit 1
            ;;
    esac
}

main "$@"
