package lv.sknarovs.futsalcalendar;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Shared test data: the saved schedule page and a fixed DTSTAMP. */
final class Fixtures {
    static final Instant STAMP = Instant.parse("2026-10-09T03:00:00Z");

    private Fixtures() {
    }

    static String html() {
        try (InputStream in = Fixtures.class.getResourceAsStream("/virsliga.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
