# Java + GraalVM Native Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Python scraper with a Java program built as a GraalVM native binary. The binary only writes `cal/*.ics`; `run.sh` on the Pi installs new releases and does the git work.

**Architecture:** The binary is a small pipeline of pure units: `ScheduleParser` (HTML to matches), `CalendarBuilder` (matches to per-team calendars), and `IcsWriter` (calendar to text). `ScheduleFetcher` does I/O and `Main` wires the units together. First the port reproduces the Python output byte for byte on a saved copy of the page. The three subscriber-facing improvements then follow, one commit each. GitHub Actions builds the aarch64 binary on tag push, and `run.sh` on the Pi downloads it.

**Tech Stack:** Java 25, Gradle 9.8.1 (Kotlin DSL), jsoup 1.23.2, JUnit 6.1.3, GraalVM CE `25.4.4.1+1-graalce` (SDKMAN) / `ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1-ol8-20260922` (CI), and the plugin `org.graalvm.buildtools.native` 1.1.14.

**Spec:** `docs/superpowers/specs/2026-10-09-java-native-migration-design.md`. Read it first. This plan refers to it for exact formats instead of repeating them.

## Global Constraints

- Package `lv.sknarovs.futsalcalendar`; main class `lv.sknarovs.futsalcalendar.Main`; native image name `lff-futsal-calendar`.
- Every native build passes `-march=compatibility` (the target is a Pi 3, Cortex-A53, ARMv8.0).
- Only one runtime dependency: jsoup. No ical4j, no logging framework.
- The binary takes no arguments, does no git work, and writes only `cal/<slug>.ics` under the current directory.
- Use the User-Agent `LFF-Futsal-Calendar/1.0`, a 30 s timeout, and the URL `https://lff.lv/sacensibas/telpu-futbols/virsliga/`.
- iCalendar output: UTF-8, CRLF, the property order from the spec, and folding with at most 74 content octets per line.
- Every file written under `cal/` and `src/test/resources/expected/` is UTF-8. Never rely on the platform default charset.
- Commits on branch `java-native-migration`. Never push, tag, or touch the Pi without the owner's go-ahead (Task 9 is owner-driven).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **The markup changes so nothing parses.** `Main` exits 1, writes no files, and `run.sh` therefore never commits. Test: `Main` with HTML that contains no matches (Task 5).
2. **lff.lv is down or returns a non-2xx status.** Exit 1, existing `cal/` files untouched. Test: `Main` with a fetcher that throws (Task 5); `ScheduleFetcher` rejects a 500 (Task 5).
3. **A stadium or round contains `,` `;` or `\`** (e.g. `Rīga, Arēna`). The output is escaped and calendar apps don't split the field. Test: escaping inside a full `VEVENT` (Task 3).
4. **GitHub is unreachable when `run.sh` checks for releases.** A warning is printed, the existing binary runs, and the commit still happens. Verified by hand with a dead proxy (Task 7).
5. **A push is rejected because the laptop pushed first.** The commit stays local, and the next run rebases and pushes it. Verified by hand with a bare remote (Task 7).

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradlew*`, `gradle/wrapper/*` | Build, dependencies, native-image config |
| `src/main/java/lv/sknarovs/futsalcalendar/Match.java` | Record for one parsed match |
| `src/main/java/lv/sknarovs/futsalcalendar/ScheduleParser.java` | HTML → `List<Match>`, BeautifulSoup-compatible `text()` |
| `src/main/java/lv/sknarovs/futsalcalendar/TeamCalendar.java` | Record: calendar name + matches |
| `src/main/java/lv/sknarovs/futsalcalendar/CalendarBuilder.java` | Grouping by slug, `slug()` |
| `src/main/java/lv/sknarovs/futsalcalendar/IcsWriter.java` | iCalendar text: structure, event content, escaping, folding, VTIMEZONE |
| `src/main/java/lv/sknarovs/futsalcalendar/ScheduleFetcher.java` | HTTP GET |
| `src/main/java/lv/sknarovs/futsalcalendar/Main.java` | Wiring, progress output, exit codes, file writing |
| `src/test/resources/virsliga.html` | Fixture: saved schedule page |
| `src/test/resources/expected/*.ics` | Golden output for the fixture |
| `src/test/java/lv/sknarovs/futsalcalendar/*Test.java` | One test class per unit + `GoldenTest` |
| `run.sh` | Pi cron entry: pull, install release, run, commit, push |
| `.github/workflows/release.yml` | Tag-triggered aarch64 build + GitHub Release |
| `.gitignore`, `README.md` | Updated; Python files deleted |

Shared test constants: `GoldenTest.STAMP = Instant.parse("2026-10-09T03:00:00Z")`.

---

### Task 1: Gradle project, fixture, and the match parser

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper files, `src/main/java/lv/sknarovs/futsalcalendar/Match.java`, `src/main/java/lv/sknarovs/futsalcalendar/ScheduleParser.java`, `src/test/resources/virsliga.html`
- Modify: `.gitignore`
- Test: `src/test/java/lv/sknarovs/futsalcalendar/ScheduleParserTest.java`

**Interfaces:**
- Produces:
  - `record Match(String id, LocalDate date, LocalTime time, String homeTeam, String awayTeam, String score, String stadium, String round)`. A `time` of `null` means all-day and a `score` of `null` means no score; every other field is non-null, possibly empty.
  - `final class ScheduleParser`: `static List<Match> parse(String html)` and package-private `static String text(Element e)`.

- [ ] **Step 1: Create the build**

  - Run `gradle wrapper --gradle-version 9.8.1` (host Gradle) and set `rootProject.name = "lff-futsal-calendar"`.
  - `build.gradle.kts`:
    - plugins `application` and `id("org.graalvm.buildtools.native") version "1.1.14"`, with `mavenCentral()`
    - Java toolchain 25
    - `implementation("org.jsoup:jsoup:1.23.2")`, `testImplementation(platform("org.junit:junit-bom:6.1.3"))`, `testImplementation("org.junit.jupiter:junit-jupiter")`, `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`
    - `application.mainClass = "lv.sknarovs.futsalcalendar.Main"` and `tasks.test { useJUnitPlatform() }`
    - `graalvmNative { binaries.named("main") { imageName = "lff-futsal-calendar"; buildArgs.add("-march=compatibility") } }`
  - `.gitignore`: add `.gradle/`, `build/`, `bin/`, `.idea/`, and keep the Python lines until Task 6.

- [ ] **Step 2: Save the fixture**

```bash
curl -fsS --max-time 30 -A 'LFF-Futsal-Calendar/1.0' -o src/test/resources/virsliga.html https://lff.lv/sacensibas/telpu-futbols/virsliga/
grep -c 'class="date"' src/test/resources/virsliga.html   # expect 72 (or the season's current count; note it)
```

  Keep the whole page; don't trim it. Write the noted count into the test below as `EXPECTED_MATCHES`.

- [ ] **Step 3: Write the failing tests**

```java
class ScheduleParserTest {
    static final int EXPECTED_MATCHES = 72; // value noted in Step 2
    static String fixture() throws IOException { /* read virsliga.html from the classpath as UTF-8 */ }

    @Test void parsesEveryDatedMatchOnTheFixture() {
        assertEquals(EXPECTED_MATCHES, ScheduleParser.parse(fixture()).size());
    }
    @Test void parsesAPlayedMatch() {
        // data-id 25157907 (or a played match from the saved fixture; pin its real values)
        Match m = find(parse(fixture()), "25157907");
        assertEquals(LocalDate.of(2026, 9, 19), m.date());
        assertEquals(LocalTime.of(17, 0), m.time());
        assertEquals("FK Nīca/OtankiMill", m.homeTeam());
        assertEquals("TFK Salaspils", m.awayTeam());
        assertEquals("9:3", m.score());
        assertEquals("Nīcas sporta halle", m.stadium());
        assertFalse(m.round().isEmpty());
    }
    @Test void unplayedMatchHasNoScore() { /* res "-" → score() == null */ }
    @Test void skipsMatchWithoutDateBlock() { /* div.tr.match with div.matchday only → empty list */ }
    @Test void textMimicsBeautifulSoupStrip() {
        assertEquals("FKNīca", ScheduleParser.text(el("<a> FK <b> Nīca </b></a>")));
        assertEquals("", ScheduleParser.text(el("<span>   </span>")));
    }
    @Test void invalidTimeMakesAllDayMatch() { /* .h7 "TBA", "25:00" and missing → time() == null */ }
    @Test void invalidDateIsSkipped() { /* h5 31, h6 feb → no match */ }
    @Test void unknownMonthOrNonNumericDayIsSkipped() { /* h6 "xyz"; h5 "x" */ }
    @Test void fewerThanTwoClubsIsSkipped() {}
    @Test void missingTeamLinkIsUnknown() { /* .club without .title a → "Unknown" */ }
    @Test void roundComesFromNearestPrecedingTh1() {
        // two th1 headers ("1. kārta", "2. kārta") each followed by a match → rounds in order; match before any th1 → ""
    }
}
```

  Build small HTML snippets with a helper `matchHtml(id, day, month, year, time, home, away, res1, res2, stadium)`.

- [ ] **Step 4: Run them and confirm they fail**

  Run `./gradlew test --tests '*ScheduleParserTest'`. Expected: compilation fails, because `ScheduleParser` doesn't exist yet.

- [ ] **Step 5: Implement `Match` and `ScheduleParser`**

  Follow the spec's "Parsing" section field by field.
  - **Round:** select `div.th1, div.tr.match` once. The result is in document order; keep the current round as you iterate.
  - **Months:** `jan feb mar apr mai jūn jūl aug sep okt nov dec` map to 1–12. Lowercase with `Locale.ROOT`.
  - **Time:** matches `(\d{1,2}):(\d{1,2})` with hour ≤ 23 and minute ≤ 59.
  - **Date:** an invalid date throws `DateTimeException` from `LocalDate.of`; catch it and skip the match.
  - **Score:** both parts are non-empty and all characters are `Character::isDigit`.

  `text()` is the one algorithm the tests don't fully pin down:

```java
static String text(Element e) {
    var sb = new StringBuilder();
    NodeTraversor.traverse((node, depth) -> {
        if (node instanceof TextNode t) sb.append(pyStrip(t.getWholeText()));
    }, e);
    return sb.toString();
}
// Python str.strip() whitespace
private static boolean pySpace(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
}
```

- [ ] **Step 6: Run the tests and confirm they pass**

  Run `./gradlew test`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

  `git add` the build files, wrapper, `.gitignore`, sources and tests, then commit with the message `Add Gradle project and schedule parser`.

---

### Task 2: Group matches into per-team calendars (Python semantics)

**Files:**
- Create: `src/main/java/lv/sknarovs/futsalcalendar/TeamCalendar.java`, `src/main/java/lv/sknarovs/futsalcalendar/CalendarBuilder.java`
- Test: `src/test/java/lv/sknarovs/futsalcalendar/CalendarBuilderTest.java`

**Interfaces:**
- Consumes: `Match` (Task 1).
- Produces:
  - `record TeamCalendar(String name, List<Match> matches)`.
  - `final class CalendarBuilder`: `static SortedMap<String, TeamCalendar> build(List<Match> matches)` (key = slug) and `static String slug(String name)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void slugMatchesPythonForNow() {
    assertEquals("fc-talsi", CalendarBuilder.slug("FC Talsi"));
    assertEquals("squad-samgus-aizkraukle", CalendarBuilder.slug("Squad/Samgus Aizkraukle"));
    assertEquals("fk-n-ca-otankimill", CalendarBuilder.slug("FK Nīca/OtankiMill")); // changes in Task 4
    assertEquals("a-b", CalendarBuilder.slug("--A  b--"));
}
@Test void eachMatchGoesToHomeAndAwayCalendarsInPageOrder() {
    // m1 A vs B, m2 C vs A → keys [a, b, c]; a.matches == [m1, m2]; b == [m1]; c == [m2]
}
@Test void calendarNameIsFirstSpellingSeenForSlug() { /* "FC X" then "fc x" → name "FC X", one calendar */ }
```

- [ ] **Step 2: Run them and confirm they fail**

  Run `./gradlew test --tests '*CalendarBuilderTest'`. Expected: compilation fails.

- [ ] **Step 3: Implement the two classes**

  `slug` uses Python semantics for now: lowercase (`Locale.ROOT`), then replace each run of `[^a-z0-9]+` with `-`, then trim `-` from both ends. Use a `TreeMap` keyed by slug, with mutable lists that are wrapped as unmodifiable when returned.

- [ ] **Step 4: Run the tests and confirm they pass**

  Run `./gradlew test`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

  Commit with the message `Group matches into per-team calendars`.

---

### Task 3: iCalendar writer, proven byte-identical to the Python output

**Files:**
- Create: `src/main/java/lv/sknarovs/futsalcalendar/IcsWriter.java`, `src/test/resources/expected/*.ics`
- Test: `src/test/java/lv/sknarovs/futsalcalendar/IcsWriterTest.java`, `src/test/java/lv/sknarovs/futsalcalendar/GoldenTest.java`

**Interfaces:**
- Consumes: `TeamCalendar`, `CalendarBuilder.build`, `ScheduleParser.parse`.
- Produces: `final class IcsWriter` with `static String write(TeamCalendar cal, Instant stamp)` and package-private `static String escape(String)` and `static String fold(String)`.

- [ ] **Step 1: Generate the Python baseline (throwaway harness, outside the repo)**

```bash
S=<scratchpad>/pybaseline; mkdir -p "$S"
python3 -m venv "$S/venv" && "$S/venv/bin/pip" install -q -r requirements.txt
cat > "$S/harness.py" <<'EOF'
import sys, pathlib
sys.path.insert(0, sys.argv[1])
import fetch_schedule as fs
html = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")
out = pathlib.Path(sys.argv[3]); out.mkdir(parents=True, exist_ok=True)
for slug, cal in fs.build_calendars(fs.parse_matches(html)).items():
    (out / f"{slug}.ics").write_bytes(cal.to_ical())
EOF
"$S/venv/bin/python" -I "$S/harness.py" "$PWD" src/test/resources/virsliga.html src/test/resources/expected
for f in cal/*.ics; do diff <(grep -v '^DTSTAMP' "$f") <(grep -v '^DTSTAMP' "src/test/resources/expected/$(basename "$f")") >/dev/null || echo "DIFF $f"; done
```

  Expected: 9 files, and no `DIFF` lines. Differences may appear only if a score or time on lff.lv changed since the Pi's last run. Inspect any diff: a formatting difference (property order, escaping) means the laptop's `icalendar` version differs from the Pi's, so stop and report it.

- [ ] **Step 2: Write the failing tests**

```java
class GoldenTest {
    static final Instant STAMP = Instant.parse("2026-10-09T03:00:00Z");
    @Test void fixtureOutputMatchesExpectedFiles() {
        // parse fixture → build → for each (slug, cal): write IcsWriter.write(cal, STAMP) to build/golden-actual/<slug>.ics
        // assert set of "<slug>.ics" names == names in src/test/resources/expected/
        // assert each file equal after dropping lines starting "DTSTAMP:"
    }
}
class IcsWriterTest {
    @Test void escapesTextValues() {
        assertEquals("a\\\\b\\;c\\,d\\ne\\nf", IcsWriter.escape("a\\b;c,d\r\ne\nf"));
    }
    @Test void foldsAt74ContentOctets() {
        assertEquals("x".repeat(74), IcsWriter.fold("x".repeat(74)));
        assertEquals("x".repeat(74) + "\r\n x", IcsWriter.fold("x".repeat(75)));
        assertEquals("x".repeat(73) + "\r\n ā", IcsWriter.fold("x".repeat(73) + "ā")); // never split UTF-8
    }
    @Test void specialCharactersInStadiumAreEscapedInEvent() { // Review Focus 3
        // match with stadium "Rīga, Arēna; \\Halle" → output contains "LOCATION:Rīga\\, Arēna\\; \\\\Halle\r\n"
    }
    @Test void allDayEventUsesValueDate() {
        // time null, 2026-12-19 → "DTSTART;VALUE=DATE:20261219\r\nDTEND;VALUE=DATE:20261220\r\n"
    }
    @Test void stampIsUtc() { /* contains "DTSTAMP:20261009T030000Z\r\n" */ }
    @Test void emptyStadiumOmitsLocation() {}
    @Test void endsWithEndVcalendarCrlf() {}
}
```

- [ ] **Step 3: Run them and confirm they fail**

  Run `./gradlew test`. Expected: compilation fails.

- [ ] **Step 4: Implement `IcsWriter`**

  For now, keep Python's semantics:
  - **Calendar and event properties:** in the order the spec lists them; the event content (UID, SUMMARY, DESCRIPTION, LOCATION) is built from the `Match` exactly as the spec says.
  - **Timed events:** floating local times `DTSTART:<yyyyMMdd>T<HHmmss>`, with `DTEND` 1 hour later.
  - **`DTSTAMP`:** `stamp` formatted in UTC.
  - **No `VTIMEZONE` yet.**

  Every content line goes through `fold`, and every text value goes through `escape`. `fold` counts UTF-8 octets per code point, and starts a new line (`"\r\n "`) whenever the next code point would push the current line's content over 74 octets.

- [ ] **Step 5: Run the tests and confirm they pass**

  Run `./gradlew test`. Expected: BUILD SUCCESSFUL. `GoldenTest` passing is success criterion 1.

- [ ] **Step 6: Commit**

  Commit with the message `Add iCalendar writer matching the Python output`. Include `expected/`.

---

### Task 4: Subscriber improvements (three commits)

Each sub-step updates the expected files. To regenerate them, run `./gradlew test` (it fails), then `cp build/golden-actual/*.ics src/test/resources/expected/`, remove stale expected files, read `git diff --stat` and the diff, and confirm it shows only the intended change.

**Files:**
- Modify: `CalendarBuilder.java`, `IcsWriter.java`, `CalendarBuilderTest.java`, `IcsWriterTest.java`, `src/test/resources/expected/*`

**Interfaces:** unchanged.

- [ ] **Step 1: Make slugs transliterate**

  - **Test:** change the assertion to `assertEquals("fk-nica-otankimill", slug("FK Nīca/OtankiMill"))` and add `assertEquals("aceegiklnsuz", slug("āčēēģīķļņšūž"))`. Run it and watch it fail.
  - **Implementation:** apply `Normalizer.normalize(name, NFD)`, then `replaceAll("\\p{M}", "")`, before the existing steps.
  - **Expected files:** regenerate them. The diff must be a pure rename, `fk-n-ca-otankimill.ics` → `fk-nica-otankimill.ics`.
  - **Commit:** `Transliterate diacritics in calendar file names`.

- [ ] **Step 2: Use the Europe/Riga time zone**

  - **Tests:**
    - Timed events contain `"DTSTART;TZID=Europe/Riga:20261010T160000\r\n"`.
    - Output contains the spec's `VTIMEZONE` block verbatim, placed after `X-WR-CALNAME` and before the first `VEVENT`.
    - `vtimezoneMatchesJavaZoneRules()`: for each year 2026–2035, `ZoneId.of("Europe/Riga").getRules().nextTransition(Jan 1 of that year)` equals the last Sunday of March at 01:00Z, and the transition after it equals the last Sunday of October at 01:00Z. Compute "last Sunday" with `TemporalAdjusters.lastInMonth(SUNDAY)`.

    Run them and watch them fail.
  - **Implementation:**
    - Add `TZID=Europe/Riga` to the `DTSTART`/`DTEND` of timed events.
    - Emit the static `VTIMEZONE` text exactly as the spec gives it, as a constant.
  - **Expected files:** regenerate them. The diff must touch only `DTSTART`/`DTEND` lines of timed events and add the `VTIMEZONE` block.
  - **Commit:** `Put events in the Europe/Riga time zone`.

- [ ] **Step 3: Make games 2-hour slots**

  - **Test:** a timed match at 16:00 has `DTEND;TZID=Europe/Riga:20261010T180000`. All-day events still end the next day. Run it and watch it fail.
  - **Implementation:** add the constant `GAME_DURATION = Duration.ofHours(2)`.
  - **Expected files:** regenerate them. The diff must touch only the `DTEND` lines of timed events.
  - **Commit:** `Show games as two-hour slots`.

---

### Task 5: Fetcher and `Main`

**Files:**
- Create: `src/main/java/lv/sknarovs/futsalcalendar/ScheduleFetcher.java`, `src/main/java/lv/sknarovs/futsalcalendar/Main.java`
- Test: `src/test/java/lv/sknarovs/futsalcalendar/MainTest.java`, `src/test/java/lv/sknarovs/futsalcalendar/ScheduleFetcherTest.java`

**Interfaces:**
- Consumes: `ScheduleParser.parse`, `CalendarBuilder.build`, `IcsWriter.write`.
- Produces:
  - `final class ScheduleFetcher`: `static final URI URL` and `static String fetch(URI uri) throws IOException, InterruptedException`. Use `java.net.http.HttpClient` with `Redirect.NORMAL`, a 30 s connect and request timeout, and the User-Agent header. A non-2xx response throws `IOException("HTTP <code> from <uri>")`.
  - `final class Main`: `static int run(Callable<String> fetch, Path calDir, Instant now, PrintStream out, PrintStream err)`, plus `main`, which calls `System.exit(run(() -> ScheduleFetcher.fetch(ScheduleFetcher.URL), Path.of("cal"), Instant.now(), System.out, System.err))`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void writesOneFilePerTeamAndPrintsProgress(@TempDir Path dir) {
    int code = Main.run(() -> fixture(), dir.resolve("cal"), STAMP, out, err);
    assertEquals(0, code);
    // files == expected/ names; stdout equals the spec's progress lines exactly, with "Found 72 matches." and "9 team calendars."
}
@Test void noMatchesExitsOneAndWritesNothing(@TempDir Path dir) {  // Review Focus 1
    assertEquals(1, Main.run(() -> "<html></html>", dir.resolve("cal"), STAMP, out, err));
    assertFalse(Files.exists(dir.resolve("cal")));
    assertEquals("No matches found. Exiting.\n", errText());
}
@Test void fetchFailureExitsOneAndKeepsExistingFiles(@TempDir Path dir) {  // Review Focus 2
    // pre-create cal/x.ics "old"; fetch throws IOException("boom") → 1, file still "old", stderr has one line containing "boom"
}
@Test void leavesUnrelatedFilesAlone(@TempDir Path dir) { /* cal/other.ics survives a run */ }
// ScheduleFetcherTest: start com.sun.net.httpserver.HttpServer on port 0
@Test void sendsUserAgentAndReturnsBody() {}
@Test void non2xxIsAnError() { /* 500 → IOException containing "HTTP 500" */ }
```

- [ ] **Step 2: Run them and confirm they fail**

  Run `./gradlew test`. Expected: compilation fails.

- [ ] **Step 3: Implement both classes**

  - `Main.run` catches `Exception` from fetching, parsing and writing, prints `"Error: " + message` to `err`, and returns 1.
  - The progress wording is copied from the spec. File names are printed in slug order.

- [ ] **Step 4: Run the tests and confirm they pass**

  Run `./gradlew test`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Live run on the JVM (not committed)**

  Run `./gradlew installDist && (cd "$(mktemp -d)" && <repo>/build/install/lff-futsal-calendar/bin/lff-futsal-calendar && ls cal)`. It runs in a temp directory, so the repo's `cal/` stays untouched.

  Expected: exit 0, and 9 `.ics` files including `fk-nica-otankimill.ics`.

- [ ] **Step 6: Commit**

  Commit with the message `Add fetcher and command-line entry point`.

---

### Task 6: Native build in the toolbox, and Python removal

**Files:**
- Modify: `.gitignore` (drop the `.venv/`, `__pycache__/` and `*.pyc` lines)
- Delete: `fetch_schedule.py`, `requirements.txt`

- [ ] **Step 1: One-time toolbox and GraalVM setup**

```bash
toolbox run -c fedora-toolbox-44 sudo dnf install -y gcc glibc-devel zlib-devel libstdc++-static
bash -c 'source "$HOME/.sdkman/bin/sdkman-init.sh" && yes n | sdk install java 25.4.4.1+1-graalce'
readlink ~/.sdkman/candidates/java/current     # expect 25.0.4-tem (default unchanged)
```

- [ ] **Step 2: Check that the local and CI GraalVM releases match**

```bash
~/.sdkman/candidates/java/25.4.4.1+1-graalce/bin/native-image --version
podman run --rm ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1-ol8-20260922 --version
```

  Expected: both print the same GraalVM CE / JDK 25.0.4.1 version line. If they differ, pick an image tag whose `--version` matches, and use that tag in Task 8.

- [ ] **Step 3: Build natively and smoke-test it**

```bash
toolbox run -c fedora-toolbox-44 env GRAALVM_HOME="$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce" ./gradlew nativeCompile
(cd "$(mktemp -d)" && <repo>/build/native/nativeCompile/lff-futsal-calendar; echo "exit=$?"; ls cal | wc -l)
```

  Expected: `exit=0` and `9`.
  - If the build or the run fails on missing reflection or resource metadata, add only the config native-image asks for under `src/main/resources/META-INF/native-image/lv.sknarovs/lff-futsal-calendar/`, then rebuild.
  - Also run `./gradlew nativeTest` in the toolbox. Expected: the tests pass natively.

- [ ] **Step 4: Remove Python and commit**

  `git rm fetch_schedule.py requirements.txt`, update `.gitignore`, run `./gradlew test` (it must still pass), then commit with the message `Remove the Python implementation`. Add any native-image config from Step 3 in its own commit first, with the message `Add native-image configuration`.

---

### Task 7: `run.sh` for the Pi

**Files:**
- Modify: `run.sh` (full rewrite)

- [ ] **Step 1: Write `run.sh`**

  The behaviour is fixed by the spec's `run.sh` section, so here is the whole script:

```bash
#!/usr/bin/env bash
# Cron entry point on the Raspberry Pi: update the binary, regenerate calendars, publish them.
set -euo pipefail

REPO=sknarovs/lff-futsal-calendar
BIN=bin/lff-futsal-calendar

main() {
    cd "$(dirname "$0")"
    git pull -q --rebase --autostash
    update_binary
    "$BIN"
    publish
}

update_binary() {
    local tag
    tag=$(curl -fsSI --max-time 30 "https://github.com/$REPO/releases/latest" \
        | tr -d '\r' | sed -n 's#^location: .*/releases/tag/##Ip') || tag=""
    if [[ -n "$tag" && "$tag" == "$(cat bin/VERSION 2>/dev/null)" && -x "$BIN" ]]; then
        return
    fi
    if [[ -n "$tag" ]] && mkdir -p bin && curl -fsSL --max-time 300 -o "$BIN.tmp" \
        "https://github.com/$REPO/releases/download/$tag/lff-futsal-calendar-linux-$(uname -m)"; then
        chmod +x "$BIN.tmp"
        mv "$BIN.tmp" "$BIN"
        echo "$tag" > bin/VERSION
        echo "Installed $tag."
        return
    fi
    rm -f "$BIN.tmp"
    if [[ ! -x "$BIN" ]]; then
        echo "No binary and no release could be downloaded." >&2
        exit 1
    fi
    echo "Warning: could not check for a new release; using the installed binary." >&2
}

publish() {
    git add cal/
    if [[ -z "$(git status --porcelain cal/)" ]]; then
        echo "No changes to commit."
        return
    fi
    git commit -q -m "Update calendars ($(date '+%Y-%m-%d %H:%M'))"
    git push -q
    echo "Committed and pushed."
}

main "$@"
```

- [ ] **Step 2: Lint it**

  Run `toolbox run -c fedora-toolbox-44 sh -c 'command -v shellcheck || sudo dnf install -y ShellCheck' && toolbox run -c fedora-toolbox-44 shellcheck run.sh`. Expected: no output.

- [ ] **Step 3: Verify by hand against a scratch clone (Review Focus 4 and 5)**

  Setup:

```bash
W=<scratchpad>/runsh; rm -rf "$W"; mkdir -p "$W"
git clone -q --bare . "$W/remote.git"
git clone -q "$W/remote.git" "$W/pi"; git clone -q "$W/remote.git" "$W/laptop"
cp run.sh "$W/pi/run.sh"; mkdir -p "$W/pi/bin"
cp build/native/nativeCompile/lff-futsal-calendar "$W/pi/bin/"; echo v0 > "$W/pi/bin/VERSION"
```

  The checks below run with `https_proxy=http://127.0.0.1:9`, a dead proxy. Only curl honours that variable; Java's `HttpClient` ignores it. So the GitHub release lookup fails while the binary still reaches lff.lv, and the bare remote is a local path, so git is unaffected.

  | # | Action | Expected |
  |---|---|---|
  | a | `(cd "$W/pi" && https_proxy=http://127.0.0.1:9 ./run.sh)` | Warning line, binary runs, "Committed and pushed.", and `git -C "$W/remote.git" log -1 --format=%s` starts with `Update calendars (` |
  | b | Run (a) again within the same minute | "Committed and pushed." again (DTSTAMP changed). Then `git -C "$W/pi" status --porcelain` is empty |
  | c | In `$W/laptop`: pull, commit a README change, push. Then in `$W/pi`: make a manual commit to `cal/` without pushing, and run (a) | The pull rebases the local commit, the run pushes, and the remote log shows the laptop commit below both Pi commits |
  | d | `(cd "$W/pi" && rm -rf bin && https_proxy=http://127.0.0.1:9 ./run.sh); echo $?` | "No binary and no release could be downloaded.", exit 1, nothing committed |

  Scenario (c) checks the push-race recovery, because it's the same code path as a rejected push followed by the next run.

- [ ] **Step 4: Commit**

  Commit with the message `Rewrite run.sh as release updater and git publisher`.

---

### Task 8: Release workflow and README

**Files:**
- Create: `.github/workflows/release.yml`
- Modify: `README.md`

- [ ] **Step 1: Write the workflow**

  - **Trigger:** `on: push: tags: ['v*']`; `permissions: contents: write`; one job on `runs-on: ubuntu-24.04-arm`.
  - **Steps:**
    1. `actions/checkout@v4`.
    2. Build:
       ```bash
       docker run --rm -v "$PWD:/work" -w /work --entrypoint bash \
         ghcr.io/graalvm/native-image-community:25i4-25.0.4.1.1-ol8-20260922 \
         -c 'microdnf install -y findutils >/dev/null && ./gradlew --no-daemon test nativeCompile'
       ```
       `gradlew` needs `xargs`, which the slim image lacks; that's why `findutils` is installed.
    3. Prepare the asset:
       ```bash
       cp build/native/nativeCompile/lff-futsal-calendar lff-futsal-calendar-linux-aarch64
       ```
    4. Smoke test:
       ```bash
       d=$(mktemp -d); (cd "$d" && "$GITHUB_WORKSPACE/lff-futsal-calendar-linux-aarch64") && ls "$d"/cal/*.ics
       ```
    5. Release:
       ```bash
       gh release create "$GITHUB_REF_NAME" lff-futsal-calendar-linux-aarch64 --generate-notes $([[ "$GITHUB_REF_NAME" == *-* ]] && echo --prerelease)
       ```
       Run this with `env: GH_TOKEN: ${{ github.token }}`. A tag containing `-` (e.g. `v1.0.0-rc.1`) becomes a prerelease, which `releases/latest` ignores, so a trial release never reaches the Pi.

- [ ] **Step 2: Rehearse the build step locally (amd64 variant of the same image)**

  Run `./gradlew clean`, then the same `docker run` command with `podman run` and `-v "$PWD:/work:Z"`. Expected: BUILD SUCCESSFUL, and `build/native/nativeCompile/lff-futsal-calendar` runs on the host. Run `./gradlew clean` afterwards.

- [ ] **Step 3: Rewrite the README**

  Follow the spec's "README changes" section exactly:
  - **Keep:** the team table and the subscribe instructions.
  - **Schedule Updates:** no frequency. Say that times are Europe/Riga (shown in the viewer's own time zone) and that games are 2-hour slots.
  - **Replace "Manual Run" with three parts:**
    - Develop: `./gradlew test`, and the Task 6 native build command with the pinned identifier.
    - Release: `git tag vX.Y.Z && git push origin vX.Y.Z`, and the prerelease note.
    - Raspberry Pi setup:
      - clone over SSH (needed for push)
      - `./run.sh` once
      - a crontab line `<schedule> /path/to/lff-futsal-calendar/run.sh >> /path/to/run.log 2>&1`, where the schedule is up to the owner
      - a note that `bin/` holds the installed binary

- [ ] **Step 4: Validate the workflow syntax and commit**

  Run `toolbox run -c fedora-toolbox-44 sh -c 'command -v actionlint || sudo dnf install -y actionlint; actionlint'`. Expected: no output. If the package isn't available, check the YAML with `python3 -c 'import yaml'` in a scratch venv instead.

  Commit with the message `Add release workflow and update README`.

---

### Task 9: Cutover (owner-driven; the agent prepares commands and waits)

The agent does not run steps 2–5 without explicit approval for each.

- [ ] **Step 1: Final check on the branch**

  Run `git fetch origin && git rebase origin/master && ./gradlew test`. Expected: green, and only `cal/` commits came in from origin.

- [ ] **Step 2: Rehearse in CI**

  With the owner's approval, push the branch and the tag `v1.0.0-rc.1`. Expected: the workflow is green and a prerelease with `lff-futsal-calendar-linux-aarch64` appears. If the Pi is reachable, the owner downloads it there and runs it once in a temp directory as an ARMv8.0 check.

- [ ] **Step 3: Release**

  With approval: fast-forward `master` to the branch, `git push origin master`, then `git tag v1.0.0 && git push origin v1.0.0`. Wait for the Release.

- [ ] **Step 4: On the Pi (owner)**

  Run `git pull --rebase && rm -rf .venv cal/fk-n-ca-otankimill.ics && ./run.sh`. Expected: "Installed v1.0.0.", 9 files written, "Committed and pushed.", and GitHub shows `cal/fk-nica-otankimill.ics`.

- [ ] **Step 5: Crontab (owner)**

  Confirm the crontab line calls `run.sh`. Then delete the `v1.0.0-rc.1` prerelease and its tag (`gh release delete v1.0.0-rc.1 --cleanup-tag`).
