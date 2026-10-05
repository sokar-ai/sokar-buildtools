# BT01 — A Release Leg Installs The Release

**Status:** soon.

**What must be true.** A leg on a rented machine for a release tests that release: `sokar-machines acceptance` and
`lease` install Sokar from the channel the run publishes to, and `acceptance --candidate` needs no package name.

## Why

The release of 0.4.0 found both on the first tag runs that rented a machine. `AgentLeg.install` writes the
repository line with the channel fixed to `snapshots`, so a tag leg installed Sokar from a channel the release had
emptied, and stopped at "Unable to locate package sokar". And `AgentLeg.run` checked the package name before it
looked at `--candidate`, so `--candidate` alone, which `acceptance` documents as needing no name, was refused with
"--package '' is not a package name".

## Acceptance

- `acceptance --candidate <dir>` without `--package` passes the name check and installs the candidate. Seen to fail:
  a unit test with an empty package name and a candidate, against the code as released in 0.4.0.
- The install script a leg sends names the channel it was given, `snapshots` or `releases`, for Debian and RPM alike,
  and a run on a tag gives `releases`. Seen to fail: a unit test reading the script for `releases` and finding
  `snapshots`.
- A tag run of an agent repository and of `sokar-frontend`, on `sokar-machines` with this change, installs the
  released Sokar and is green.

## To be checked

- Whether the channel is an option of `acceptance` and `lease`, or read from the run (`GITHUB_REF_TYPE`), and which
  wins when both are there.
