<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.4.1] - 2026-10-06
--------------------

### Added (1 change)

- Check citations and documentation chapters: check-citations reads every committed text file and refuses a link to an issue's file, a pointer to an issue that is gone, a number among the issues that names none and an issue number anywhere else, with not-a-citation and citations-exempt.txt for text that holds the shape on purpose; check-doc-site refuses a page missing from the navigation or in it twice and a link a published page could not follow 

### Changed (1 change)

- The build checks this repository's own citations and documentation chapter on every push, beside its shared block 


