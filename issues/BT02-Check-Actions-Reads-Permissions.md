# BT02 — Check Actions Reads Permissions

**Status:** soon. Wanted before `sokar-frontend` builds its Windows job for F102's stage 2.

**What must be true.** `check-actions` reads a workflow's `permissions:` as well as its `uses:`: at the top a workflow
grants read or nothing, and a `write` permission is allowed only in a job that the check's own list names for it.
Anything else is refused by name.

## Why

Today `check-actions` reads only `uses:` lines, so a `write` permission passes in any job and at the top of any
workflow. A build that attests what it made needs `id-token: write` and `attestations: write`, and a release needs
`contents: write`; each is wanted in the one job that does it, and nowhere else, where a step it fetches could use it.

## The rule

- A workflow's top-level `permissions:` is `read-all`, `{}`, or a map of `read` and `none` values only.
- A job's `permissions:` may hold a `write` value only for a permission the list names for that job, by workflow file and
  job id - for example `id-token: write` and `attestations: write` for the job that attests a build, `contents: write`
  for the job that makes a release.
- `write-all` is refused everywhere.
- A refusal names the file, the job, the permission and what the list allows there.

## Acceptance

- Seen red first: a test workflow with `contents: write` at the top, and one with `id-token: write` in a job the list
  does not name, both pass today's `check-actions`; afterwards each is refused, by name.
- A workflow whose writes are all in the jobs the list names passes.
- The workflows of the repositories that run the check pass with the list as written, or the list grows by name in
  the same change, with the reason.
- `doc/index.md` says how a repository names a job in the list.

## To be checked

- Where the list lives: in the check, or in a file of each repository that the shared rules keep alike.
