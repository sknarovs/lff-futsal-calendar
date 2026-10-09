package lv.sknarovs.futsalcalendar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Downloads the schedule page. */
final class ScheduleFetcher {
    static final URI URL = URI.create("https://lff.lv/sacensibas/telpu-futbols/virsliga/");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ScheduleFetcher() {
    }

    static String fetch(URI uri) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "LFF-Futsal-Calendar/1.0")
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " from " + uri);
            }
            return response.body();
        }
    }
}
