# Sokar Machines

Renting a test machine, and talking to one.

Four repositories rent servers from one Hetzner project to test against: this one and the three
agents. Until now each carried its own copy of a 483-line Python module, and the copies had already
drifted — the agents' copy grew a `key_material()` that normalises a key's line endings, and this
repository's did not, so the same secret was cleaned in three places and passed through raw in the
fourth. That is what this replaces.

## The one thing worth reading first

**A server that is not destroyed costs 81 EUR a month**, against 3 cents for the fifteen minutes it
is meant to live. So destruction is structural rather than a step at the end:

```java
try (Hetzner hetzner = Hetzner.with(token, runId);
     Rental rental = hetzner.rent(Spec.of("sokar-ci-1", "ubuntu", "build", credential))) {
    rental.awaitSsh();
    rental.ssh().run("./mvnw -B verify");
}
```

`Rental.close()` deletes, and it is not conditional on success — the expensive mistake is a server
that outlives a script which failed on line three, not one that is deleted twice. `sweep` is the net
under that, because a process killed between two statements cannot clean up after itself.

Everything created here is labelled `sokar=ci` plus the run that made it, so the sweep finds it
without a list of names to keep in step with reality.

## Sweeping

```
java -jar sokar-machines.jar sweep [--mine | --now | --older-than <hours>]
```

With no option it lists what would go and deletes nothing. `--mine` deletes what this run created
and only that: deleting by age instead would catch another run's server whenever that run is slower
than the window, and ssh dying part way through somebody else's build is close to undebuggable.

`HETZNER_API` carries the API token and is read from the environment, never from an argument —
`/proc/<pid>/cmdline` is world readable and neither supported distribution mounts `/proc` with
`hidepid`.

## What is here and what is not

Seven endpoints, not a client library: create a server, wait for it, delete it, list what was left
behind, and find an image, a location and a key. Paging is followed to the end rather than assumed
away — the API returns 25 entries a page, and a sweep that reads one page finds fewer leaked servers
than exist and then reports that it cleaned up.

`Ssh` is the other half, and the acceptance kit's `Machine` is built on it. Connecting,
authenticating and running a command are the same problem whether the caller is renting the machine
or testing on it; the kit adds what only a scenario needs.

## Tests

`StubApi` stands up a real HTTP server on a real socket, because what is being checked is paging,
status codes and headers — the layer a mocked client would replace with an assumption. The
fingerprint is checked against what `ssh-keygen -E md5` says, since the API agrees with that rather
than with us.
