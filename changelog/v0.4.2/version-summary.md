<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.4.2] - 2026-10-08
--------------------

### Added (7 changes)

- sokar-release check-actions holds a Gradle Wrapper as it holds Maven's: a distributionUrl with a version and a distributionSha256Sum 
- sokar-release check-readmes refuses a Maven module without a README.md beside its pom.xml, and a README that does not link each of its submodules, walking the reactor from the root, profiles included 
- sokar-release check-releases refuses a release that builds or packages with a snapshot, reading what Maven resolved in the effective pom, a plugin's dependencies included 
- sokar-machines deploy --account installs the build readers a handover carries under builds/ into the account, beside the packages 
- sokar-release check-releases also reads a module's opt-in to Central where it sets it on the publishing plugins, as a BOM does 
- sokar-release check-releases refuses a module published to Central whose pom would still name its parent, since nothing flattens it or its flatten mode keeps the parent 
- A leg, a snapshot and a deploy build and install sokar's stub build reader as the account's stub-forge when the tree has one, so the acceptance suite can follow builds without a forge 

### Changed (5 changes)

- A push that changes only documents - Markdown, mkdocs.yml, doc/ and issues/ - starts no build and leases no machine; the Shared rules workflow checks it 
- sokar-release check-releases accepts a published pom that has no parent, as the parent pom itself is 
- A release tag refuses every snapshot in the effective pom - a dependency, a plugin or a plugin's dependency - before it publishes, checked by the sokar-release that very tag builds 
- The NullAway compile comes from sokar-parent, and exec-maven-plugin is 3.6.3 as it sets 
- The build takes org.fuin.sokar:sokar-parent as its parent, with every version it builds with unchanged 

### Fixed (1 change)

- sokar-machines runs with bcpkix and bcutil of bcprov's release line, and sokar-release with the Jackson dataformats of the Jackson it resolves, also where another repository resolves them 


