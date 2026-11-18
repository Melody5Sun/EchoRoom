Commit **only the files you changed in this session**, with a descriptive message derived from the diff. Safe under concurrent / parallel Claude sessions that share one working tree and `.git` index. Do not include a Co-Authored-By line in the commit message.

**Why (read first):** another session may have its own uncommitted — or already *staged* — changes in the shared index. `git add -u` / `git add -A` sweeps them into your commit. So does staging in one tool call and committing in a later one: a concurrent commit can fire in the gap and carry off your staged files under its own message (this has happened — see `tasks/lessons.md`, 2026-06-12). So: stage/commit **by explicit path**, never `-u` / `-A`, and do it **atomically in a single shell call**.

Steps:

1. **Understand the change.** Run `git status` and `git diff` (read-only; scope the diff with `git diff -- ':(literal)<path>'` if the tree is noisy). From the conversation, identify the exact set of files *you* modified or created this session — call this `MY_FILES`. Everything else in the tree belongs to another session (or is unrelated) and stays out of your commit.

2. **Untracked files.** Include an untracked path in `MY_FILES` only if you're confident it's part of this change. If you're unsure about any untracked file, use the AskUserQuestion tool — the first two options must always be "Stage all" and "Skip all (don't stage any)", then list each untracked file as an individual option after those. Wait for the selection; include only what the user picked. Never include an untracked file another session created.

3. Compose the commit message (format in step 5).

4. **Commit atomically, path-scoped, in ONE Bash call.** Pass `MY_FILES` to `git commit` itself so only those paths are committed regardless of what else sits staged in the shared index:
   ```bash
   git commit -F - -- ':(literal)<path>' [':(literal)<path>' …] <<'EOF'
   <subject>

   - bullet one
   - bullet two
   EOF
   ```
   - `-- <paths>` commits the working-tree content of **exactly** those paths and ignores everything else staged → this both isolates you from a concurrent session's staged files *and* removes the add/commit race window (no separate `git add` step to be interrupted).
   - `:(literal)` is **required** for any path containing `[` `]` `(` `)` (Next.js route segments like `[datasourceId]` / `(menupages)`) — a bare bracketed path glob-expands to nothing and silently commits zero files.
   - **New untracked files can't be committed by path alone** (git has nothing to diff against). Chain the `add` into the *same* call so there's still no gap: `git add -- ':(literal)<newfile>' && git commit -F - -- ':(literal)<path>' … <<'EOF' … EOF`.
   - Never run `git add -u` / `git add -A`, and never leave staging and committing in separate tool calls.

5. Commit message format:
   - **First line (subject):** `<tag>: <summary>` in imperative mood, under 72 chars.
   - **Blank line**
   - **Body:** 2–5 bullet points (using `-`) describing the specific changes made. Each bullet should be one concise sentence focused on what changed and why, not how.

   **Tag selection — check in this order:**
   - `fix:` — corrects a bug or broken behaviour
   - `feat:` — adds new user-facing functionality
   - `refactor:` — restructures code without changing behaviour
   - `style:` — visual/UI/CSS changes with no logic change
   - `chore:` — build, tooling, config, dependency updates
   - `docs:` — documentation only
   - `test:` — adds or updates tests
   - `perf:` — performance improvement
   - `ci:` — CI/CD pipeline changes
   - If none of the above fits, choose the most descriptive tag that accurately names the change (e.g. `move:`, `rename:`, `wip:`, `revert:`).

6. **Verify after committing:** run `git show --stat HEAD` and confirm the commit contains exactly `MY_FILES` — nothing from another session. If it swept something in, `git reset --soft HEAD^` and redo path-scoped.

7. Report the commit hash and full message when done.
