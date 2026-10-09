# Java + GraalVM native migration: design

- **Date:** 2026-10-09
- **Status:** agreed in conversation; awaiting review of this written spec

## Goal

Replace the Python scraper (`fetch_schedule.py`, run in a venv by `run.sh`) with a
Java program compiled to a GraalVM native binary.

The binary does one job: fetch the schedule and write the calendar files. `run.sh`
stays the cron entry point on the Raspberry Pi and does everything else: install new
binary releases, then `git pull`, commit and push. When it runs is decided by cron
alone; neither the binary nor `run.sh` has any notion of a schedule.

The port also improves the calendars for subscribers: events get the Europe/Riga time
zone, games become 2-hour slots, and file names are transliterated to plain ASCII.

### Constraints

- **Laptop:** Fedora 44 Sway Atomic, x86_64. The native-image toolchain (`gcc`,
  `glibc-devel`, `zlib-devel`, `libstdc++-static`) cannot be installed on the host, so
  local native builds run in the existing `fedora-toolbox-44` toolbox.
- **Production:** a Raspberry Pi with 64-bit Raspberry Pi OS (aarch64), driven by cron.
  native-image cannot cross-compile, so GitHub Actions builds the Pi binary natively on
  an ARM64 runner.
- **Subscriber URLs:** they stay
  `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/<slug>.ics`.
  The only renamed file is `fk-n-ca-otankimill.ics` → `fk-nica-otankimill.ics`, and
  the owner accepts that this breaks existing subscriptions to the old name.

### Success criteria

1. On a saved copy of the schedule page, the first Java version reproduces the Python
   output byte for byte, apart from `DTSTAMP` lines.
2. The final version differs from that baseline only by the changes listed under
   [Deliberate changes](#deliberate-changes-from-the-python-version).
3. Pushing a `v*` tag publishes a GitHub Release containing a working
   `lff-futsal-calendar-linux-aarch64`.
4. On the Pi, cron runs `run.sh`, which installs new releases by itself and commits and
   pushes updated calendars.
5. No Python remains in the repository.

## Current system

`run.sh` creates `.venv`, installs `requirements.txt` and runs `fetch_schedule.py`,
which:

1. fetches `https://lff.lv/sacensibas/telpu-futbols/virsliga/`
2. parses the `div.tr.match` elements with BeautifulSoup
3. builds one calendar per team with the `icalendar` package
4. writes `cal/<slug>.ics`
5. runs `git add cal/`, `git commit -m "Update calendars (YYYY-MM-DD HH:MM)"` and
   `git push`

## Architecture

```
laptop (x86_64)                 GitHub Actions                 Raspberry Pi (aarch64)
  host JDK:  ./gradlew test       on tag v*: release.yml         cron -> run.sh
  toolbox:   ./gradlew              runner ubuntu-24.04-arm        1. git pull --rebase
             nativeCompile          GraalVM CE image (OL 8)        2. install newer release
  git push; git tag v* ------->     test + nativeCompile              into bin/ if any
                                    smoke run                      3. bin/lff-futsal-calendar
                                    GitHub Release  ------------>     -> cal/*.ics
                                                    (download      4. git commit + push
                                                     when the tag
                                                     changes)
```

## The binary

Java 25, package `lv.sknarovs.futsalcalendar`. The only runtime dependency is jsoup;
everything else comes from the JDK. The binary takes no arguments.

| Unit | Responsibility |
|---|---|
| `Main` | Runs fetch → parse → build → write, prints progress, sets the exit code |
| `ScheduleFetcher` | Downloads the schedule page |
| `ScheduleParser` | Turns the page HTML into a `List<Match>` |
| `Match` (record) | One match: id, date, optional time, home team, away team, optional score, stadium, round |
| `CalendarBuilder` | Groups matches into one `TeamCalendar` (record: name + matches) per team slug; owns `slug()` |
| `IcsWriter` | Renders a `TeamCalendar` as iCalendar text |

Only `ScheduleFetcher` touches the network and only `Main` touches the file system;
every other unit is pure and tested in isolation.

### Fetching

- `GET https://lff.lv/sacensibas/telpu-futbols/virsliga/` with the header
  `User-Agent: LFF-Futsal-Calendar/1.0`, a 30-second timeout, and redirects followed.
- A non-2xx status or an I/O error is a failure (see [exit codes](#output-and-exit-codes)).

### Parsing (port of `parse_matches`)

`text(e)` reproduces BeautifulSoup's `get_text(strip=True)`. It takes every descendant
text node of `e` in document order, strips each one of whitespace as Python's
`str.strip()` defines it (this includes U+00A0, the no-break space), drops the empty
pieces, and concatenates the rest with no separator.

For each `div.tr.match`, in document order:

- `id` is the `data-id` attribute, or empty if it is missing.
- **Date:** the match is skipped unless it has a `.date` element containing `h5` (day),
  `h6` (month) and `.h8` (year).
  - Day and year must parse as integers.
  - The month's lowercase text must be one of `jan feb mar apr mai jūn jūl aug sep okt
    nov dec`, which map to 1–12.
  - The match is skipped if any of these fail, or if the date doesn't exist (e.g. 31
    Feb).
- **Time:** taken from `text(.h7)` inside `.date`.
  - `H:MM` or `HH:MM` with hour 0–23 and minute 0–59 makes a timed match.
  - Anything else, including an empty or missing element, makes an all-day match.
- **Teams:** taken from the match's `.club` elements. With fewer than two, the match is
  skipped. A team's name is `text(.title a)` inside its club, or `Unknown` if there is
  no such element. The first club is home, the second away.
- **Score:** `res1` is `text(.result .res1)` in the first club and `res2` is
  `text(.result .res2)` in the second. If both are non-empty and consist only of digits,
  the score is `res1:res2`; otherwise there is none.
- **Stadium:** `text(.stadium)` inside the match, or empty.
- **Round:** `text(span.h3)` of the nearest `div.th1` that comes before the match in
  document order (as BeautifulSoup's `find_previous` would find it), or empty.

### Calendars (port of `build_calendars`)

- **`slug(name)`:**
  1. Apply Unicode NFD.
  2. Remove combining marks.
  3. Lowercase.
  4. Replace each run of characters outside `[a-z0-9]` with `-`.
  5. Trim `-` from both ends.

  Example: `FK Nīca/OtankiMill` → `fk-nica-otankimill`.
- There is one calendar per slug, named after the first team name seen with that slug.
  Each match adds one event to the home team's calendar and one to the away team's, in
  page order.
- Each event has these properties:
  - **`UID`:** `<id>@lff-futsal`
  - **`SUMMARY`:** `<home> vs <away>`, or `<home> <score> <away>` when there is a score
  - **`DESCRIPTION`:** these lines joined by newlines: the round (if non-empty),
    `Score: <score>` (if any), and `Match ID: <id>`
  - **`LOCATION`:** the stadium, or omitted when the stadium is empty
  - **Timed match:** `DTSTART;TZID=Europe/Riga:<yyyyMMdd>T<HHmmss>`, and `DTEND` with the
    same TZID, 2 hours later
  - **All-day match:** `DTSTART;VALUE=DATE:<yyyyMMdd>`, and `DTEND;VALUE=DATE:` set to
    the following day
  - **`DTSTAMP`:** the time the run started, in UTC, formatted `<yyyyMMdd>T<HHmmss>Z`;
    every event in a run gets the same value

### iCalendar output

- The text is UTF-8 with CRLF line endings, and the last line is `END:VCALENDAR` plus a
  CRLF.
- Components and property order match today's files, which the `icalendar` package
  produced. The `VTIMEZONE` block is new and is always included:

  ```
  BEGIN:VCALENDAR
  VERSION:2.0
  PRODID:-//LFF Futsal Virslīga//lv
  CALSCALE:GREGORIAN
  X-WR-CALNAME:<team name>
  BEGIN:VTIMEZONE
  TZID:Europe/Riga
  BEGIN:DAYLIGHT
  TZOFFSETFROM:+0200
  TZOFFSETTO:+0300
  TZNAME:EEST
  DTSTART:19700329T030000
  RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
  END:DAYLIGHT
  BEGIN:STANDARD
  TZOFFSETFROM:+0300
  TZOFFSETTO:+0200
  TZNAME:EET
  DTSTART:19701025T040000
  RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
  END:STANDARD
  END:VTIMEZONE
  BEGIN:VEVENT
  SUMMARY / DTSTART / DTEND / DTSTAMP / UID / DESCRIPTION / LOCATION
  END:VEVENT
  ... one VEVENT per event ...
  END:VCALENDAR
  ```

- Text values (`PRODID`, `X-WR-CALNAME`, `SUMMARY`, `UID`, `DESCRIPTION`, `LOCATION`) are
  escaped in this order:
  1. `\` becomes `\\`
  2. `;` becomes `\;`
  3. `,` becomes `\,`
  4. A CRLF or LF becomes `\n`
- Lines are folded so that no line exceeds 75 octets. The content is cut into pieces of
  at most 74 octets, never inside a UTF-8 character, and each continuation line starts
  with one space.

### Writing files

The binary creates `cal/` in the current directory if it is missing, then writes
`cal/<slug>.ics` for every calendar in slug order, overwriting existing files. It leaves
every other file in `cal/` alone.

### Output and exit codes

Progress goes to stdout, with the same wording as the Python version minus the git
lines:

```
Fetching schedule from lff.lv...
Parsing matches...
  Found <n> matches.
Generating calendars...
  <m> team calendars.
Writing .ics files...
  Written: <slug>.ics
Done!
```

| Exit code | Meaning |
|---|---|
| 0 | Calendars written |
| 1 | No matches found (prints `No matches found. Exiting.` to stderr and writes nothing), or a fetch, parse or write error (prints a one-line message to stderr) |

## Deliberate changes from the Python version

| # | Change | Reason |
|---|---|---|
| 1 | Timed events carry `TZID=Europe/Riga`, and each file has a `VTIMEZONE` block. Python wrote floating local times. | Subscribers outside Latvia see the correct local time |
| 2 | Timed events last 2 hours instead of 1 | Games take about two hours |
| 3 | Slugs strip diacritics, so `fk-n-ca-otankimill.ics` becomes `fk-nica-otankimill.ics` | Readable file names that match the README link |
| 4 | `DTSTAMP` is real UTC. Python wrote local time with a `Z` suffix, 2–3 hours off. | Correctness |
| 5 | An invalid time gives an all-day event and an invalid date skips the match. Python gave a 00:00 event or crashed. | Robustness |
| 6 | The binary does no git work. `run.sh` does it, adds `git pull --rebase`, and exits non-zero on git errors; Python printed git errors and exited 0. | Owner's decision; code is now also pushed from the laptop, so the Pi has to pull before it pushes |
| 7 | Files are written to `cal/` under the current directory, where Python used the script's own location. `run.sh` changes into the repo first. | The binary lives in `bin/`, not at the repo root |

## `run.sh`: the Pi's cron entry point

`run.sh` stays the cron entry point, so the crontab line stays the same. The script body
is wrapped in functions and called from the last line. Bash therefore reads the whole
script before running anything, and a `git pull` that replaces `run.sh` cannot disturb
the copy that is running. The script uses `set -euo pipefail`.

1. `cd` into the script's directory, which is the repo clone.
2. Run `git pull --rebase --autostash`.
3. Install a newer binary if one exists:
   - **Find the latest tag:** read the `Location` header that
     `https://github.com/sknarovs/lff-futsal-calendar/releases/latest` redirects to; the
     tag is its last path segment. This needs no API token.
   - **Download it:** if the tag differs from the contents of `bin/VERSION`, or there is
     no binary yet, download
     `https://github.com/sknarovs/lff-futsal-calendar/releases/download/<tag>/lff-futsal-calendar-linux-$(uname -m)`
     to a temporary file in `bin/`. Then `chmod +x` it, rename it over
     `bin/lff-futsal-calendar`, and write the tag to `bin/VERSION`.
   - **If that fails:** when the lookup or the download fails but a binary already
     exists, print a warning and use the existing binary. If there is no binary at all,
     exit 1.
4. Run `bin/lff-futsal-calendar`. If it fails, exit with its exit code without
   committing.
5. Run `git add cal/`. If `git status --porcelain cal/` prints nothing, print
   `No changes to commit.` and exit 0.
6. Run `git commit -m "Update calendars ($(date '+%Y-%m-%d %H:%M'))"`, then `git push`.

If a push is rejected because the laptop pushed at the same moment, the commit stays
local. The next run's step 2 rebases it and its step 6 pushes it.

`bin/` is gitignored.

## Build

- Gradle 9.8.1 wrapper with the Kotlin DSL and the `application` and
  `org.graalvm.buildtools.native` plugins. The Java toolchain is 25.
- The native image is named `lff-futsal-calendar` and is built with
  `-march=compatibility`. That flag is required: on ARM64, native-image targets
  `armv8.1-a` by default, which the ARMv8.0 CPUs in the Pi 3 and Pi 4 can't run. GitHub's
  ARM runners can run it, so CI would never notice the problem.
- Dependencies are jsoup (`implementation`) and JUnit 5 (`test`), pinned to exact
  versions in the build file.
- GraalVM is GraalVM Community Edition for JDK 25. The laptop (SDKMAN) and CI (the
  container image tag) use the same pinned release.

### Local development (laptop)

- Tests and the IDE use the host JDK: `./gradlew test`.
- **One-time toolbox setup:**
  - Inside `fedora-toolbox-44`, run
    `sudo dnf install gcc glibc-devel zlib-devel libstdc++-static`.
  - Install GraalVM CE with SDKMAN. It lands in the shared `$HOME`, so the toolbox sees
    it. The default SDKMAN JDK stays Temurin.
- **Native build:**
  `toolbox run -c fedora-toolbox-44 env GRAALVM_HOME="$HOME/.sdkman/candidates/java/<pinned GraalVM CE identifier>" ./gradlew nativeCompile`.
  The binary lands at `build/native/nativeCompile/lff-futsal-calendar`. The README gives
  the command with the identifier filled in.

### Release (`.github/workflows/release.yml`)

- **Trigger:** pushing a tag that matches `v*`.
- **Runner:** `ubuntu-24.04-arm`, with permissions `contents: write`.
- **Steps:**
  1. Check out the repository.
  2. Run `./gradlew test nativeCompile` inside the official GraalVM CE container image
     (`ghcr.io/graalvm/native-image-community`, Oracle Linux 8 variant, exact tag
     pinned). Oracle Linux 8 ships glibc 2.28, so the binary runs on every 64-bit
     Raspberry Pi OS release.
  3. Smoke-test the binary on the runner: run it in an empty temporary directory. The
     step fails unless the binary exits 0 and writes at least one `cal/*.ics`. This also
     exercises HTTPS inside the native image.
  4. Run `gh release create <tag> lff-futsal-calendar-linux-aarch64 --generate-notes`.

## Testing

- **Fixture:** a saved copy of the schedule page, kept in `src/test/resources`. It may
  be trimmed to the schedule section, but the order of the `div.th1` and `div.tr.match`
  elements must stay intact.
- **Parity baseline:** run the current Python code once on the fixture to produce
  expected `.ics` files.
  - The first Java version must match them byte for byte, apart from `DTSTAMP` lines.
  - Then changes 1–3, the ones that change the fixture output, land one at a time.
    Each one regenerates the expected files from the Java code with a fixed clock, and
    the diff is reviewed in that commit.
- **Unit tests:**
  - parsing: `text()` parity (including U+00A0 and nested tags); date, month and time
    parsing, including invalid times and dates; score detection; round tracking
  - calendars and output: slugs (including `FK Nīca/OtankiMill`); escaping; folding at
    multi-byte boundaries; all-day events; the 2-hour end; `TZID` lines; the `DTSTAMP`
    format
- **`VTIMEZONE` check:** for every year from 2026 to 2035, the transitions the static
  block implies must equal the transitions that `ZoneId.of("Europe/Riga")` reports.
  Those implied transitions are the last Sunday of March at 03:00 +02:00 and the last
  Sunday of October at 04:00 +03:00.
- **Native smoke run:** in the toolbox before tagging, and in CI on every release.
- **`run.sh`:** checked with `shellcheck`, and run by hand against a scratch clone with
  a local bare remote before the cutover. No automated test is committed for it.

## Repository layout after the migration

```
.github/workflows/release.yml
build.gradle.kts  settings.gradle.kts  gradlew  gradlew.bat  gradle/wrapper/
src/main/java/lv/sknarovs/futsalcalendar/   Main, ScheduleFetcher, ScheduleParser,
                                            Match, CalendarBuilder, TeamCalendar, IcsWriter
src/test/java/lv/sknarovs/futsalcalendar/   tests
src/test/resources/                         fixture page, expected .ics files
cal/                                        unchanged location
run.sh                                      updater + git; cron entry point
README.md
.gitignore                                  .gradle/  build/  bin/
docs/superpowers/specs/                     this document
```

`fetch_schedule.py` and `requirements.txt` are deleted.

## README changes

- **Schedule Updates:** say that a cron job on a Raspberry Pi regenerates the calendars,
  without naming a frequency. Also state that event times are in Latvian time
  (Europe/Riga), so calendar apps show them in the viewer's own time zone, and that
  games appear as 2-hour slots.
- **Manual Run:** replace this section with three parts:
  - building and testing locally (host JDK plus the toolbox)
  - releasing (pushing a tag)
  - setting up the Pi (clone the repo, add a crontab entry that calls `run.sh`)
- **Team table:** no change is needed; the FK Nīca link becomes correct.

## Cutover

1. Do the work on the branch `java-native-migration`. The Pi pushes regularly, so rebase
   onto `origin/master` before merging.
2. Merge into `master` and push.
3. Run `git tag v1.0.0 && git push origin v1.0.0`, then wait for the Release to appear.
4. On the Pi, inside the clone, run
   `git pull --rebase && rm -rf .venv cal/fk-n-ca-otankimill.ics && ./run.sh`. That
   first run commits the new `fk-nica-otankimill.ics` together with the removal of the
   old file.
5. The crontab line keeps calling `run.sh`, which is assumed to be what it calls today.
   If it calls something else, point it at `run.sh`.

Between steps 2 and 4 the Pi's old Python job keeps running. Once `master` has moved,
its push fails and its commit stays local. Step 4's `git pull --rebase` replays that
commit. The commit touches only `cal/` and the migration never does, so the rebase
cannot conflict. The old file is deleted on the Pi rather than in a laptop commit for
the same reason.

## Out of scope

- x86_64 release binaries
- automatically removing stale `.ics` files, e.g. for teams that leave the league
- skipping commits when only `DTSTAMP` changed
- generating the README team table
