# Agent Guidelines

## Commit Strategy & Rules

### 1. Practice Atomic Commits
- Every commit must represent a single, logical, self-contained unit of change.
- Never mix unrelated changes (e.g. refactoring, dependency updates, and feature implementations) into a single commit.
- Keep commits granular so they can be easily reviewed, cherry-picked, or reverted if necessary.

### 2. Maintain Working Build State
- Each commit must leave the codebase in a fully building and working state.
- Run tests and verify the build passes before committing any change (`nix develop --command ./gradlew test`).

### 3. Clear & Imperative Commit Messages
- Use concise, imperative commit messages (e.g. `proto: add version_v2 to Mumble.proto` instead of `added version_v2`).
- Prefix commit messages with a clear scope tag when applicable (e.g., `nix:`, `proto:`, `core:`, `docs:`).

### 4. Separate Generated Code & Formatting
- Keep automated code generation (e.g. `protoc` outputs) in separate commits from manual code edits when feasible, or couple them directly with the schema change that triggered the generation.

### 5. Never Rewrite Git History
- Never rewrite, squash, or mutate existing git history (no rebasing, squashing existing commits, or force-pushing).
- All changes, bug fixes, and improvements must be introduced as new, forward-only atomic commits on top of the current branch.
