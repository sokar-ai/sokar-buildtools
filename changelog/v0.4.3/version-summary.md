<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.4.3] - 2026-10-08
--------------------

### Added (2 changes)

- sokar-release check-deploy reads a deploy dry run's log and refuses a module deployed without the publishing plugin, or an upload aimed off the machine 
- The build runs that deploy dry run on every push, so a tag's deploy repeats what main already did 

### Changed (1 change)

- sokar-machines Stopping and AgentLeg.quote are public, for the leg sokar now runs from its own tree 

### Removed (2 changes)

- sokar-machines no longer has the leg and deploy commands; sokar keeps them in its own tree, since they know its module layout 
- sokar-package-check is no longer published; sokar checks its packages with a module of its own tree, which knows where its modules write them 

### Fixed (1 change)

- sokar-machines snapshot proves an image with the tree's own ci/leg-build.sh, and a tree from before it with the app where that tree keeps it 


