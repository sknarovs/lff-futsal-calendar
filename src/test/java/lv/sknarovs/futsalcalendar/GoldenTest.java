package lv.sknarovs.futsalcalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Compares the output for the saved page with src/test/resources/expected/.
 * Actual output goes to build/golden-actual/; copy it over expected/ to accept a change.
 */
class GoldenTest {
    private static final Path EXPECTED = Path.of("src/test/resources/expected");
    private static final Path ACTUAL = Path.of("build/golden-actual");

    @Test
    void fixtureOutputMatchesExpectedFiles() throws IOException {
        Map<String, TeamCalendar> calendars = CalendarBuilder.build(ScheduleParser.parse(Fixtures.html()));
        Files.createDirectories(ACTUAL);
        try (Stream<Path> old = Files.list(ACTUAL)) {
            for (Path p : old.toList()) {
                Files.delete(p);
            }
        }
        for (var e : calendars.entrySet()) {
            Files.writeString(ACTUAL.resolve(e.getKey() + ".ics"), IcsWriter.write(e.getValue(), Fixtures.STAMP),
                    StandardCharsets.UTF_8);
        }
        assertEquals(names(EXPECTED), names(ACTUAL));
        for (String name : names(EXPECTED)) {
            assertEquals(withoutStamp(EXPECTED.resolve(name)), withoutStamp(ACTUAL.resolve(name)), name);
        }
    }

    private static Set<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String withoutStamp(Path file) throws IOException {
        // Keep line endings: CRLF is part of what must match.
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?m)^DTSTAMP:[^\r\n]*\r?\n", "");
    }
}
