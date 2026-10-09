package lv.sknarovs.futsalcalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScheduleFetcherTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void pointsAtTheVirsligaPage() {
        assertEquals(URI.create("https://lff.lv/sacensibas/telpu-futbols/virsliga/"), ScheduleFetcher.URL);
    }

    @Test
    void sendsUserAgentAndReturnsBody() throws Exception {
        AtomicReference<String> agent = new AtomicReference<>();
        URI uri = serve(200, "<p>Nīca</p>", agent);
        assertEquals("<p>Nīca</p>", ScheduleFetcher.fetch(uri));
        assertEquals("LFF-Futsal-Calendar/1.0", agent.get());
    }

    @Test
    void non2xxIsAnError() throws Exception {
        URI uri = serve(500, "oops", new AtomicReference<>());
        IOException e = assertThrows(IOException.class, () -> ScheduleFetcher.fetch(uri));
        assertTrue(e.getMessage().contains("HTTP 500"));
    }

    @Test
    void stalledBodyTimesOut() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("<html".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class, () -> ScheduleFetcher.fetch(uri, Duration.ofSeconds(1)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "took too long");
        assertTrue(e.getMessage().contains("timed out"), e.getMessage());
        release.countDown();
    }

    private URI serve(int status, String body, AtomicReference<String> agent) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }
}
