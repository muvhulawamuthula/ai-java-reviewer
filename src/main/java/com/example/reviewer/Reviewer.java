package com.example.reviewer;

import com.example.reviewer.ai.CodeReviewAssistant;
import com.example.reviewer.ai.ReviewerFactory;
import com.example.reviewer.diff.DiffParser;
import com.example.reviewer.diff.FileDiff;
import com.example.reviewer.model.Finding;
import com.example.reviewer.model.Severity;
import com.example.reviewer.report.MarkdownReporter;
import com.example.reviewer.report.ResponseParser;
import com.example.reviewer.report.ReviewCommentsReporter;
import com.example.reviewer.report.SarifReporter;
import com.example.reviewer.review.FindingFilter;
import com.example.reviewer.review.ReviewEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entry point. Usage:
 *
 * <pre>
 *   java -jar ai-java-reviewer.jar &lt;file1.java&gt; &lt;file2.java&gt; ...
 * </pre>
 *
 * File paths are the Java files to review (typically the files changed in a PR). They may
 * also be supplied via {@code REVIEWER_FILES_FROM} or derived from a unified diff supplied
 * via {@code REVIEWER_DIFF_FROM}. Configuration comes from environment variables (see README).
 *
 * <p>When a diff is supplied the review is diff-aware: only lines the PR changed are flagged.
 * Otherwise files are reviewed whole.
 *
 * <p>Exit code: 1 if any finding is at least REVIEWER_FAIL_ON severity, else 0.
 * Operational failures (e.g. missing API key) exit with code 2.
 */
public final class Reviewer {

    // How far a model-reported line may sit from a changed line and still count as in-scope.
    private static final int LINE_SLACK = 2;

    public static void main(String[] args) {
        try {
            if (isServeMode(args)) {
                serve(args);
                return; // serve() blocks; never returns normally
            }
            System.exit(run(args));
        } catch (IllegalStateException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    /** True if the user asked for the web UI via {@code --serve} or {@code REVIEWER_SERVE=true}. */
    private static boolean isServeMode(String[] args) {
        for (String a : args) {
            if ("--serve".equals(a) || "serve".equals(a)) return true;
        }
        return Boolean.parseBoolean(System.getenv().getOrDefault("REVIEWER_SERVE", "false"));
    }

    /** Starts the web UI and blocks forever. Port comes from {@code --port N}, {@code REVIEWER_PORT}, or 8080. */
    private static void serve(String[] args) throws IOException {
        int port = resolvePort(args);
        new com.example.reviewer.web.WebServer(port).start();
        // Keep the JVM alive; the HttpServer runs on daemon-ish pool threads.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int resolvePort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1].trim());
                } catch (NumberFormatException e) {
                    System.err.printf("Ignoring invalid --port '%s'; trying REVIEWER_PORT then 8080.%n",
                            args[i + 1]);
                }
            }
        }
        String env = System.getenv("REVIEWER_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException e) {
                System.err.printf("Ignoring invalid REVIEWER_PORT '%s'; defaulting to 8080.%n", env);
            }
        }
        return 8080;
    }

    private static int run(String[] args) throws IOException {
        Map<String, FileDiff> diffs = loadDiff();
        List<Path> files = collectJavaFiles(args, diffs);
        if (files.isEmpty()) {
            System.out.println("No Java files to review. Nothing to do.");
            writeReports(List.of(), 0);
            return 0;
        }

        ReviewerFactory.Config config = ReviewerFactory.Config.fromEnv();
        Severity failOn = parseFailOn();
        CodeReviewAssistant assistant = ReviewerFactory.create(config);
        FindingFilter filter = new FindingFilter(config.minConfidence(), LINE_SLACK);
        ReviewEngine engine = new ReviewEngine(
                assistant, new ResponseParser(), filter, diffs, config.concurrency());

        System.out.printf("Reviewing %d file(s) with model '%s'%s (concurrency %d, min confidence %s)...%n",
                files.size(), config.model(),
                diffs.isEmpty() ? "" : " in diff-aware mode",
                config.concurrency(), config.minConfidence());

        ReviewEngine.Result result = engine.review(files);
        List<Finding> findings = result.findings();

        writeReports(findings, result.reviewed());
        printSummary(findings, result.reviewed());
        if (result.suppressed() > 0) {
            System.out.printf("Suppressed %d finding(s) (out of scope or below confidence threshold).%n",
                    result.suppressed());
        }

        long blocking = findings.stream().filter(f -> f.severity().isAtLeast(failOn)).count();
        setActionOutputs(findings.size(), blocking, failOn);

        if (blocking > 0) {
            System.out.printf("%nFailing: %d finding(s) at or above %s severity.%n", blocking, failOn);
            return 1;
        }
        System.out.println("\nNo findings at or above the failure threshold.");
        return 0;
    }

    // --- helpers -----------------------------------------------------------

    /** Reads and parses the unified diff at {@code REVIEWER_DIFF_FROM}, or an empty map. */
    private static Map<String, FileDiff> loadDiff() throws IOException {
        String diffFrom = System.getenv("REVIEWER_DIFF_FROM");
        if (diffFrom == null || diffFrom.isBlank()) {
            return Map.of();
        }
        Path p = Path.of(diffFrom.trim());
        if (!Files.isRegularFile(p)) {
            System.err.println("REVIEWER_DIFF_FROM='" + diffFrom + "' is not a file; reviewing whole files.");
            return Map.of();
        }
        return DiffParser.parse(Files.readString(p, StandardCharsets.UTF_8));
    }

    private static List<Path> collectJavaFiles(String[] args, Map<String, FileDiff> diffs) throws IOException {
        // Candidate paths come from CLI args, an optional file list, and the diff itself.
        // A LinkedHashSet keeps order stable and de-duplicates overlap between the sources.
        Set<String> candidates = new LinkedHashSet<>(List.of(args));

        // Optionally read a newline-separated list of paths from a file. This is how a workflow
        // can pass changed files, avoiding command-line length limits.
        String fromFile = System.getenv("REVIEWER_FILES_FROM");
        if (fromFile != null && !fromFile.isBlank()) {
            Path listPath = Path.of(fromFile.trim());
            if (Files.isRegularFile(listPath)) {
                candidates.addAll(Files.readAllLines(listPath));
            }
        }

        // When a diff is supplied and no explicit list was given, the changed files are the
        // files to review.
        candidates.addAll(diffs.keySet());

        List<Path> files = new ArrayList<>();
        for (String raw : candidates) {
            if (raw == null || raw.isBlank()) continue;
            String arg = raw.trim();
            Path p = Path.of(arg);
            if (Files.isRegularFile(p) && arg.endsWith(".java")) {
                files.add(p);
            }
        }
        return files;
    }

    private static Severity parseFailOn() {
        String v = System.getenv("REVIEWER_FAIL_ON");
        if (v == null || v.isBlank()) return Severity.HIGH;
        try {
            return Severity.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown REVIEWER_FAIL_ON '" + v + "', defaulting to HIGH");
            return Severity.HIGH;
        }
    }

    private static void writeReports(List<Finding> findings, int reviewed) throws IOException {
        String mdPath = envOr("REVIEWER_OUTPUT_MD", "review-report.md");
        String sarifPath = envOr("REVIEWER_OUTPUT_SARIF", "review.sarif");
        String commentsPath = envOr("REVIEWER_OUTPUT_COMMENTS", "review-comments.json");

        String md = new MarkdownReporter().render(findings, reviewed);
        Files.writeString(Path.of(mdPath), md, StandardCharsets.UTF_8);

        String sarif = new SarifReporter().render(findings);
        Files.writeString(Path.of(sarifPath), sarif, StandardCharsets.UTF_8);

        String comments = new ReviewCommentsReporter().render(findings);
        Files.writeString(Path.of(commentsPath), comments, StandardCharsets.UTF_8);

        System.out.printf("Wrote %s, %s and %s%n", mdPath, sarifPath, commentsPath);
    }

    private static void printSummary(List<Finding> findings, int reviewed) {
        // Mirror the Markdown into the GitHub Actions job summary if available.
        String summaryFile = System.getenv("GITHUB_STEP_SUMMARY");
        if (summaryFile != null && !summaryFile.isBlank()) {
            try {
                String md = new MarkdownReporter().render(findings, reviewed);
                Files.writeString(Path.of(summaryFile), md, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Job summary is best-effort.
            }
        }
    }

    private static void setActionOutputs(long total, long blocking, Severity failOn) {
        String outputFile = System.getenv("GITHUB_OUTPUT");
        if (outputFile == null || outputFile.isBlank()) return;
        try {
            String content = "total_findings=" + total + "\n"
                    + "blocking_findings=" + blocking + "\n"
                    + "fail_on=" + failOn.name() + "\n";
            Files.writeString(Path.of(outputFile), content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Outputs are best-effort.
        }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
