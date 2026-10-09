package lv.sknarovs.futsalcalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void writesOneFilePerTeamAndPrintsProgress(@TempDir Path dir) throws IOException {
        Path cal = dir.resolve("cal");
        assertEquals(0, run(Fixtures::html, cal));

        List<String> expected = names(Path.of("src/test/resources/expected"));
        assertEquals(expected, names(cal));
        StringBuilder progress = new StringBuilder(String.join("\n",
                "Fetching schedule from lff.lv...",
                "Parsing matches...",
                "  Found 72 matches.",
                "Generating calendars...",
                "  9 team calendars.",
                "Writing .ics files...")).append('\n');
        expected.forEach(name -> progress.append("  Written: ").append(name).append('\n'));
        progress.append("Done!\n");
        assertEquals(progress.toString(), text(out));
        assertEquals("", text(err));
    }

    @Test
    void writtenFileMatchesWriterOutput(@TempDir Path dir) throws IOException {
        run(Fixtures::html, dir);
        TeamCalendar talsi = CalendarBuilder.build(ScheduleParser.parse(Fixtures.html())).get("fc-talsi");
        assertEquals(IcsWriter.write(talsi, Fixtures.STAMP),
                Files.readString(dir.resolve("fc-talsi.ics"), StandardCharsets.UTF_8));
    }

    @Test
    void noMatchesExitsOneAndWritesNothing(@TempDir Path dir) {
        Path cal = dir.resolve("cal");
        assertEquals(1, run(() -> "<html></html>", cal));
        assertFalse(Files.exists(cal));
        assertEquals("No matches found. Exiting.\n", text(err));
    }

    @Test
    void fetchFailureExitsOneAndKeepsExistingFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("x.ics"), "old");
        assertEquals(1, run(() -> {
            throw new IOException("boom");
        }, dir));
        assertEquals("old", Files.readString(dir.resolve("x.ics")));
        assertEquals(1, text(err).lines().count());
        assertTrue(text(err).contains("boom"));
    }

    @Test
    void leavesUnrelatedFilesAlone(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("other.ics"), "keep");
        assertEquals(0, run(Fixtures::html, dir));
        assertEquals("keep", Files.readString(dir.resolve("other.ics")));
    }

    private int run(java.util.concurrent.Callable<String> fetch, Path calDir) {
        return Main.run(fetch, calDir, Fixtures.STAMP, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static String text(ByteArrayOutputStream s) {
        return s.toString(StandardCharsets.UTF_8);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
