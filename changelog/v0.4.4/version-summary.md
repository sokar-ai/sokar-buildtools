<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.4.4] - 2026-10-09
--------------------

### Changed (2 changes)

- sokar-machines waits up to 60 minutes at the project's server limit instead of 10, since acceptance legs run one after another 
- The Build rebuilds the machine images when their recipe changes, not only a pin, and when started on main by hand, to recover after a push whose snapshot failed 

### Fixed (1 change)

- The machine images install unzip, which the Maven wrapper needs to check its download against the pinned digest; without it the snapshot build failed 


