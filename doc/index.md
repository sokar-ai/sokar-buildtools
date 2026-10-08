# Build tools

The tooling every Sokar repository builds and tests with, published to Maven Central under `org.fuin.sokar`. None
of it ships to a person who runs Sokar: it rents the machines the acceptance tests run on, checks what a release
may publish, and checks the native images and packages before they go out.

## The tools

| Artifact | What it does | Commands |
|---|---|---|
| `sokar-machines` | Rents test machines and the snapshots they boot from, runs the agent repositories' acceptance legs on them, and installs the pinned GraalVM and musl toolchain in a build. A leg whose build is cancelled ends its suite and gives its machines back at once. A snapshot proves itself by building `sokar` with the tree's own `ci/leg-build.sh`. | `sweep`, `snapshot`, `acceptance`, `lease`, `jdk`, `musl` |
| `sokar-release` | Checks that every workflow step is pinned by commit, that a repository's shared rules are copied byte for byte, that no page or source cites an issue in a way that goes stale, that a release builds and packages with no snapshot, that a deploy dry run went through the publishing plugin in every module and sent nothing off the machine, that every page of a documentation chapter is in its navigation once and every link on it can be followed, that every Maven module has a README linking its submodules, and that an agent's pinned CLI is the one its download names; records that CLI in the package's bill; moves named pins. | `check-actions`, `check-shared`, `check-citations`, `check-doc-site`, `check-readmes`, `check-releases`, `check-deploy`, `check-pin`, `add-fetched-cli`, `compare-bills`, `update`, … |
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

## What `check-citations` reads

Every committed text file: what `git ls-files` names in a checkout, every file in a tree exported from a commit. A
binary file - one holding a NUL byte or bytes that are not UTF-8 - is skipped and counted, and so is a file no person
writes: an SVG image's path data, a lock file's hashes. Build output is not read.

- **Outside `issues/`**, no file names an issue number, and no link goes to an issue's file or a design document.
- **Among the issues**, a number of the repository's own prefixes - the prefixes its issue files carry - names an
  issue file that exists, and a number beside an `[index](…)` link into the repository names one too.
- **A line that holds an issue number's shape on purpose**, as test data for something that reads such text, says
  `not-a-citation` in its own text. A linter's codes after `noqa:` are not read.
- **A whole file of such text** is named in `citations-exempt.txt` at the repository's root, one path per line,
  relative to the root, with `#` starting a comment; a path there that is not a committed file is refused, so the
  list cannot outlive what it exempts.

## What `check-readmes` reads

```
sokar-release check-readmes [REPOSITORY ROOT, default .]
```

The reactor from the root `pom.xml` down, through every `<module>`, a profile's included, since a module only a
profile builds is a module all the same. Each module directory must hold a `README.md`, and a module with submodules
must link each of them in it - to its directory or to its `README.md`; a name in a code span is no link. Every fault is
named; a root without a `pom.xml` is refused, never passed as an empty walk.

## What `check-deploy` reads

The log of the deploy a tag runs, run on every push with both of the publishing plugin's upload URLs at a port on the
runner where nothing listens:

```
./mvnw -B -s settings.xml deploy -Pcentral-sonatype-release -Dgpg.skip -DskipTests \
    -DcentralBaseUrl=http://127.0.0.1:9 -DcentralSnapshotsUrl=http://127.0.0.1:9/ > target/deploy-dry-run.log 2>&1
sokar-release check-deploy target/deploy-dry-run.log
```

The upload failing against the dead port is expected. Refused, each named:

- **a module deployed by `default-deploy`**: the release profile's publishing plugin did not reach it as an extension,
  so the real deploy fails there with "repository element was not specified";
- **a module where `injected-central-publishing` never ran**;
- **an upload aimed at any host but the loopback address**: both URLs must be set, since a snapshot goes to
  `centralSnapshotsUrl` and a release to `centralBaseUrl`, and a dry run must not be able to publish;
- **a run offline**, where the plugin skips its goal and proves nothing.

So a tag's deploy repeats what `main` already did, rather than being the first time the release profile meets the
build.

## What `check-releases` reads

The effective pom Maven writes, taken on a release tag before the build:

```
./mvnw -B -s settings.xml help:effective-pom -Doutput=target/effective-pom.xml
sokar-release check-releases target/effective-pom.xml --requires org.fuin.sokar:sokar-release
```

So what is checked is what Maven resolved: every property interpolated, an imported BOM's versions merged in, plugins
and the dependencies they run with. A release depends only on releases, so every `-SNAPSHOT` it names is refused and
named with where it was found - parent, dependency, managed dependency, plugin, a plugin's dependency, extension. The
enforcer's rule for release dependencies does not look at a plugin's dependencies, and the Sokar tools are exactly
those.

- **The repository's own modules are outside the rule**: the same run builds them, and a repository whose Maven version
  is never published (`0-SNAPSHOT`, where another file carries the package version) is not refused for it. The tag's
  own version is checked where the release names its channel.
- **A profile that is not active is read too**: the effective pom keeps its declarations, and a snapshot named there is
  refused as well.
- **Write the effective pom with the profiles the release build uses**: a module a profile adds - a `.deb` or `.rpm`
  module under `dist` - is in the file only when the profile was active. A profile's module the file does not hold is
  refused, naming the profile to add.
- **`--requires GROUP:ARTIFACT`**, given once per artifact, refuses an effective pom that does not name it, and one that
  names no artifact at all is refused always - so a file written from the wrong directory fails rather than passes.
- **A published pom names no parent**: a module that opts in to Central - `skipPublishing` or `maven.deploy.skip`
  `false`, on `central-publishing-maven-plugin` or `maven-deploy-plugin` where the module sets it there, as a BOM does
  since its properties are published with it, else as the property - is refused unless `flatten-maven-plugin` flattens its pom in a mode that leaves the parent out (`oss`,
  `ossrh`, `defaults`, `bom`) and no `pomElements` keeps it. A consumer then needs the module alone, and the root and
  grouping modules stay unpublished.

## How a repository takes them

`sokar` names the version in its root pom, `sokar.buildtools.version`, and publishes it in `sokar-bom`. Every other
repository imports `sokar-bom` at the `sokar` version it builds against, and so takes the build tools that `sokar`
release names, without a version of its own. A build tool runs as a plugin's dependency, for example:

```
./mvnw -B -s settings.xml -N exec:exec@machines -Dmachines.args=jdk
```

The tools depend on nothing of `sokar`'s. `sokar`'s build runs them, so a dependency the other way would make the
two impossible to release one after the other.
