package com.example.reviewer.web;

import com.example.reviewer.ai.CodeReviewAssistant;
import com.example.reviewer.ai.ReviewerFactory;
import com.example.reviewer.model.Finding;
import com.example.reviewer.model.ReviewResult;
import com.example.reviewer.report.ResponseParser;
import com.example.reviewer.review.FindingFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A tiny, dependency-free web front end for the reviewer. It serves a single-page UI where
 * you paste a Java file and get findings back, reusing the exact same review pipeline the CLI
 * uses (assistant -&gt; {@link ResponseParser} -&gt; {@link FindingFilter}).
 *
 * <p>Built on the JDK's {@code com.sun.net.httpserver.HttpServer} so it adds no new
 * dependencies and still packages into the existing fat jar.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /} — the HTML/JS page (served from {@code /web/index.html} on the classpath)</li>
 *   <li>{@code POST /api/review} — body {@code {"filename": "...", "code": "..."}};
 *       returns {@code {"summary": "...", "findings": [...]}}</li>
 *   <li>{@code GET /api/health} — {@code {"ready": true|false}} (ready = an API key is configured)</li>
 * </ul>
 */
public final class WebServer {

    // Whole-file mode: how far a model-reported line may sit from a real one. Matches Reviewer.
    private static final int LINE_SLACK = 2;
    private static final long MAX_CODE_BYTES = 200_000;

    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();

    // Built lazily/once at startup. Null when no API key is configured, so the page still loads
    // (and the UI shows a friendly "set ANTHROPIC_API_KEY" message) instead of refusing to start.
    private final CodeReviewAssistant assistant;
    private final ResponseParser parser = new ResponseParser();
    private final FindingFilter filter;

    public WebServer(int port) {
        this.port = port;
        CodeReviewAssistant a = null;
        FindingFilter f = new FindingFilter(null, LINE_SLACK);
        try {
            ReviewerFactory.Config config = ReviewerFactory.Config.fromEnv();
            a = ReviewerFactory.create(config);
            f = new FindingFilter(config.minConfidence(), LINE_SLACK);
        } catch (RuntimeException e) {
            System.err.println("Warning: " + e.getMessage()
                    + " The page will load, but reviews will fail until a key is set.");
        }
        this.assistant = a;
        this.filter = f;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::serveIndex);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/review", this::handleReview);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.printf("AI Java Reviewer UI running at http://localhost:%d/  (Ctrl+C to stop)%n", port);
        if (assistant == null) {
            System.out.println("Note: ANTHROPIC_API_KEY is not set — reviews will return an error until it is.");
        }
    }

    // --- handlers ----------------------------------------------------------

    private void serveIndex(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method Not Allowed");
            return;
        }
        // Only the root path serves the page; anything else is a 404.
        if (!"/".equals(ex.getRequestURI().getPath())) {
            sendText(ex, 404, "Not Found");
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                sendText(ex, 500, "index.html not found on classpath");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            setSecurityHeaders(ex);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("ready", assistant != null);
        sendJson(ex, 200, mapper.writeValueAsString(node));
    }

    private void handleReview(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, error("Use POST"));
            return;
        }
        if (assistant == null) {
            sendJson(ex, 503, error("Server has no ANTHROPIC_API_KEY configured."));
            return;
        }
        try {
            byte[] raw = ex.getRequestBody().readAllBytes();
            if (raw.length > MAX_CODE_BYTES) {
                sendJson(ex, 413, error("Code too large (limit " + MAX_CODE_BYTES + " bytes)."));
                return;
            }
            var req = mapper.readTree(raw);
            String code = req.path("code").asText("");
            if (code.isBlank()) {
                sendJson(ex, 400, error("No code supplied."));
                return;
            }
            String filename = req.path("filename").asText("");
            if (filename.isBlank()) {
                filename = "PastedCode.java";
            }

            // Whole-file review: no diff, so the diff-scope gate is off and only confidence filters.
            String response = assistant.review(filename, code, "(whole file)");
            ReviewResult result = parser.parse(response);
            String finalFilename = filename;
            List<Finding> withFile = result.findings().stream().map(f -> f.withFile(finalFilename)).toList();
            FindingFilter.Result filtered = filter.filter(withFile, null);

            ObjectNode out = mapper.createObjectNode();
            out.put("summary", result.summary());
            out.put("filename", filename);
            out.put("suppressed", filtered.suppressed());
            out.set("findings", mapper.valueToTree(filtered.kept()));
            sendJson(ex, 200, mapper.writeValueAsString(out));
        } catch (JsonProcessingException e) {
            // Malformed request body or an unparseable model response: a client/data problem,
            // not a server bug. Report it as a 400 without a stack trace.
            sendJson(ex, 400, error("Could not parse JSON: " + e.getOriginalMessage()));
        } catch (IOException e) {
            // Reading the request body failed (e.g. the client went away).
            sendJson(ex, 400, error("Could not read request: " + e.getMessage()));
        } catch (RuntimeException e) {
            // Unexpected: a bug (NPE, ClassCastException) or a model/transport failure. Log the
            // full stack trace so it's diagnosable, then return a generic 500.
            System.err.println("Unexpected error handling /api/review:");
            e.printStackTrace();
            sendJson(ex, 500, error("Review failed: " + e.getMessage()));
        } catch (Exception e) {
            // ResponseParser.parse declares `throws Exception`; treat anything else as a 500.
            System.err.println("Unexpected error handling /api/review:");
            e.printStackTrace();
            sendJson(ex, 500, error("Review failed: " + e.getMessage()));
        }
    }

    // --- helpers -----------------------------------------------------------

    private String error(String message) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("error", message);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"internal\"}";
        }
    }

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        setSecurityHeaders(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        setSecurityHeaders(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Sets baseline hardening headers on every response: block MIME-sniffing, deny framing
     * (clickjacking), and a CSP tight enough for this self-contained page. The page uses an
     * inline &lt;style&gt; and &lt;script&gt;, so 'unsafe-inline' is required for those; everything else
     * is locked to 'self' / 'none'.
     */
    private static void setSecurityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy",
                "default-src 'none'; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline'; "
                        + "connect-src 'self'; "
                        + "base-uri 'none'; "
                        + "form-action 'none'; "
                        + "frame-ancestors 'none'");
    }
}
