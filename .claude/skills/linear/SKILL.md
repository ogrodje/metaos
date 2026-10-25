---
name: linear
description: Work on a Linear issue of Ogrodje's (OG-123, or its linear.app URL) in MetaOS - read it, move it along, implement it on a branch, test, commit, and report back on the issue. Use when the user says "work on OG-12", "pick up the next Linear ticket", "do this Linear issue" or invokes /linear.
argument-hint: "<OG-123 | linear.app URL | next> [pr]"
---

# Work on a Linear issue

Linear is reached through the project's `linear` MCP server (`.mcp.json`, tools `mcp__linear__*`): workspace
Ogrodje, team `OG`, project MetaOS. Not the claude.ai Linear connector, which is another workspace. If the `linear`
tools aren't there, tell the user to approve and authenticate it with `/mcp`, and stop.

Statuses of `OG`: Backlog, Todo, In Progress, In Review, Done, Canceled, Duplicate.

## 1. Find the issue

- `OG-123` or a URL: `get_issue` with `includeRelations`, and `list_comments` on it. Read its attachments and linked
  documents when they say what's wanted.
- `next`: `list_issues` for team `OG`, assignee `me`, state Todo (then Backlog), by priority; say which one you take.
- If it's blocked by an open issue, already Done or Canceled, or assigned to someone else, say so and ask first.
- If it's unclear what done means, ask before writing code, not after. Small gaps you fill with the repo's own
  conventions, and say so in the report.

## 2. Start

- Move it to In Progress and assign it to `me` if no one has it (`save_issue`).
- Branch from an up-to-date `master` (never commit to it: pushes to `master` deploy). Name it like the repo's branches,
  with the issue's identifier so Linear links it: `<kind>/og-123-<few-words>`, `kind` one of `feature`, `fix`, `fe`,
  `docs`, `design`. If the tree has others' uncommitted changes, leave them be (another session may own them); use a
  worktree (`EnterWorktree`) if they'd get in the way.

## 3. Do it

- Follow CLAUDE.md: MCP tools and `fe/src/tools.ts` together, tool hints, `architecture.svg` for integrations, audit
  triggers and `History.kt` for new tables, CLAUDE.md itself where it describes what changed.
- Test what you changed: `./gradlew test` (or the relevant test classes) and, for the front-end, `cd fe && yarn test`
  and `yarn build`. Look at UI changes in the running app.
- Commit on the branch only your own files, in the repo's style: a short title, a body saying what changed and why in
  plain words, and the `Co-authored-by` line. Put `OG-123` at the end of the title, e.g. `Search by season (OG-123)`.

## 4. Report

- Push and open a PR (`gh pr create`) only when the user asked for it (`pr` in the arguments, or said so); else ask at
  the end. A PR's body ends with `Fixes OG-123`, so Linear closes the issue when it merges.
- With a PR: move the issue to In Review and attach the PR (`save_issue` with `links`). Never move it to Done
  yourself: merging does that, or the user.
- Comment on the issue (`save_comment`): what was done, the branch or PR, how it was tested, and anything left open or
  decided along the way. Short, as a teammate would write it.
- If you stop before it's done (blocked, a question), leave it In Progress, comment what's missing, and tell the user.
