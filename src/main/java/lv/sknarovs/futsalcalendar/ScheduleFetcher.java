package lv.sknarovs.futsalcalendar;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Downloads the schedule page. */
final class ScheduleFetcher {
    static final URI URL = URI.create("https://lff.lv/sacensibas/telpu-futbols/virsliga/");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ScheduleFetcher() {
    }

    static String fetch(URI uri) throws IOException, InterruptedException {
        return fetch(uri, TIMEOUT);
    }

    static String fetch(URI uri, Duration timeout) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", "LFF-Futsal-Calendar/1.0")
                    .build();
            // The request timeout stops counting once headers arrive, so bound the whole download too.
            HttpResponse<String> response;
            try {
                response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                client.shutdownNow(); // close() would otherwise wait for the stalled exchange
                throw new IOException("Download from " + uri + " timed out after " + timeout.toSeconds() + " s");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            }
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " from " + uri);
            }
            return response.body();
        }
    }
}
