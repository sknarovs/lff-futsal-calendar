package lv.sknarovs.futsalcalendar;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.Callable;

/** Fetches the LFF Futsal Virslīga schedule and writes one iCalendar file per team into ./cal. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(() -> ScheduleFetcher.fetch(ScheduleFetcher.URL), Path.of("cal"), Instant.now(),
                System.out, System.err));
    }

    /** Returns the process exit code: 0 when calendars were written, 1 otherwise. */
    static int run(Callable<String> fetch, Path calDir, Instant now, PrintStream out, PrintStream err) {
        try {
            out.println("Fetching schedule from lff.lv...");
            String html = fetch.call();

            out.println("Parsing matches...");
            List<Match> matches = ScheduleParser.parse(html);
            out.println("  Found " + matches.size() + " matches.");
            if (matches.isEmpty()) {
                err.println("No matches found. Exiting.");
                return 1;
            }

            out.println("Generating calendars...");
            SortedMap<String, TeamCalendar> calendars = CalendarBuilder.build(matches);
            out.println("  " + calendars.size() + " team calendars.");

            out.println("Writing .ics files...");
            Files.createDirectories(calDir);
            for (Map.Entry<String, TeamCalendar> e : calendars.entrySet()) {
                String name = e.getKey() + ".ics";
                Files.writeString(calDir.resolve(name), IcsWriter.write(e.getValue(), now), StandardCharsets.UTF_8);
                out.println("  Written: " + name);
            }
            out.println("Done!");
            return 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            err.println("Error: " + e);
            return 1;
        }
    }
}
