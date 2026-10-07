# sokar-buildtools

<img src="doc/images/early-bird.svg" width="350" alt="Early bird - work in progress">

> **Early bird - work in progress.** Sokar is not stable yet: until release 1.0.0, its code, commands
> and file formats can change without notice.

The build and test tooling shared by the Sokar repositories, published to Maven Central under
`org.fuin.sokar`.

**Documentation: [sokar-ai.github.io/buildtools](https://sokar-ai.github.io/buildtools/)**, one chapter of
[all of Sokar's documentation](https://sokar-ai.github.io).

| Artifact | Directory | What it does |
|---|---|---|
| `sokar-machines` | [`hetzner/`](hetzner/README.md) | Rents test machines, builds the snapshots they boot from, runs the acceptance legs on them, and installs the pinned GraalVM in CI. |
| `sokar-release` | [`release/`](release/README.md) | Decides what an agent package may publish without a person, moves named pins, and checks that every workflow step is pinned by commit. |
| `sokar-ffm-check` | [`ffm-check/`](ffm-check/README.md) | Checks that every FFM downcall a test makes is registered for the native image. |
| `sokar-cpu-check` | [`cpu-check/`](cpu-check/README.md) | Checks that a native image runs on any x86-64 CPU. |
| `sokar-package-check` | [`package-check/`](package-check/README.md) | Checks the .deb and the .rpm against each other and against a real install. |
| `sokar-json` | [`json/`](json/README.md) | The JSON reader and writer the tools above share. |

## Building

```
JAVA_HOME=~/.sdkman/candidates/java/25.3.4+1.r25-graalce ./mvnw -B clean verify
```

JDK 25; the pinned GraalVM is the one CI uses. The tools depend on nothing of sokar's; `sokar-json` is
their own JSON reader. See [AGENTS.md](AGENTS.md) for the conventions.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).
