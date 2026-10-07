# release - `sokar-release`

The command line an agent repository's build and update job call: it decides what an agent package may publish
without a person, moves named pins, and holds the shared checks - `check-actions`, `check-shared`, `check-citations`,
`check-readmes`, `check-doc-site`, `check-releases`. It is not a release process of its own: each repository's
workflow calls it.

- Exit codes are answers: 0 yes, 1 refused, another code when it could not answer. What each check reads is in the
  [documentation](../doc/index.md).
