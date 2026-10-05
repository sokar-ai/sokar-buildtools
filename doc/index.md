# Build tools

The tooling every Sokar repository builds and tests with, published to Maven Central under `org.fuin.sokar`. None
of it ships to a person who runs Sokar: it rents the machines the acceptance tests run on, checks what a release
may publish, and checks the native images and packages before they go out.

## The tools

| Artifact | What it does | Commands |
|---|---|---|
| `sokar-machines` | Rents test machines and the snapshots they boot from, runs acceptance legs on them, and installs the pinned GraalVM and musl toolchain in a build. A leg whose build is cancelled ends its suite and gives its machines back at once. | `sweep`, `snapshot`, `leg`, `acceptance`, `lease`, `deploy`, `jdk`, `musl` |
| `sokar-release` | Checks that every workflow step is pinned by commit, that a repository's shared rules are copied byte for byte, and that an agent's pinned CLI is the one its download names; records that CLI in the package's bill; moves named pins. | `check-actions`, `check-shared`, `check-pin`, `add-fetched-cli`, `compare-bills`, `update`, … |
| `sokar-ffm-check` | Checks that every downcall of the Foreign Function & Memory API a test made is registered for the native image. | run by `sokar`'s build |
| `sokar-cpu-check` | Checks that a native image asks for no more than x86-64 v1, so it runs on any x86-64 CPU. | run by `sokar`'s build |
| `sokar-package-check` | Checks the `.deb` and the `.rpm` against each other and against a real install. | run by `sokar`'s build |
| `sokar-json` | The JSON reader and writer the tools above share. | - |

## Who uses which

| Repository | Uses |
|---|---|
| `sokar` | all of them: the acceptance legs, the image and package checks, `check-actions` and `check-shared` |
| the agent repositories (`claude`, `pi`, `omp`) | `sokar-machines` for their acceptance legs and the GraalVM; `sokar-release` for the pinned CLI, the bill and the shared rules |
| `frontend` | `sokar-machines` to lease a machine its integration tests reach; `sokar-release` for the shared rules |
| `sluice`, `matrix` | `sokar-machines` for the GraalVM; `sokar-release` for the shared rules |

## How a repository takes them

`sokar` names the version in its root pom, `sokar.buildtools.version`, and publishes it in `sokar-bom`. Every other
repository imports `sokar-bom` at the `sokar` version it builds against, and so takes the build tools that `sokar`
release names, without a version of its own. A build tool runs as a plugin's dependency, for example:

```
./mvnw -B -s settings.xml -N exec:exec@machines -Dmachines.args=jdk
```

The tools depend on nothing of `sokar`'s. `sokar`'s build runs them, so a dependency the other way would make the
two impossible to release one after the other.
