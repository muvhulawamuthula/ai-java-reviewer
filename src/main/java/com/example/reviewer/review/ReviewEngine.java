package com.example.reviewer.review;

import com.example.reviewer.ai.CodeReviewAssistant;
import com.example.reviewer.diff.FileDiff;
import com.example.reviewer.model.Finding;
import com.example.reviewer.model.ReviewResult;
import com.example.reviewer.report.ResponseParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Owns the per-file review loop: reads each file, asks the model (in parallel, bounded by
 * {@code concurrency}), parses the response, and applies the {@link FindingFilter}. Keeping
 * this out of {@link com.example.reviewer.Reviewer} leaves the entry point a thin CLI.
 *
 * <p>When a diff map is supplied the engine runs diff-aware: each file is reviewed with its
 * changed-line ranges passed to the model and findings filtered to those lines. Files absent
 * from the map (or an empty map) are reviewed whole-file.
 */
public final class ReviewEngine {

    // Files larger than this are skipped to stay within sensible token limits.
    private static final long MAX_FILE_BYTES = 120_000;

    private final CodeReviewAssistant assistant;
    private final ResponseParser parser;
    private final FindingFilter filter;
    private final Map<String, FileDiff> diffsByPath;
    private final int concurrency;
    private final boolean diffMode;

    public ReviewEngine(CodeReviewAssistant assistant,
                        ResponseParser parser,
                        FindingFilter filter,
                        Map<String, FileDiff> diffsByPath,
                        int concurrency) {
        this.assistant = assistant;
        this.parser = parser;
        this.filter = filter;
        this.diffsByPath = diffsByPath == null ? Map.of() : diffsByPath;
        this.concurrency = Math.max(1, concurrency);
        this.diffMode = !this.diffsByPath.isEmpty();
    }

    public Result review(List<Path> files) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, Math.max(1, files.size())));
        try {
            // Submit in file order and collect futures in the same order so console output and
            // the aggregated finding list are deterministic regardless of completion order.
            List<Future<FileOutcome>> futures = new ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(pool.submit(reviewTask(file)));
            }

            List<Finding> all = new ArrayList<>();
            int reviewed = 0;
            int suppressed = 0;
            for (Future<FileOutcome> future : futures) {
                FileOutcome outcome = get(future);
                System.out.println(outcome.logLine);
                if (outcome.reviewed) {
                    reviewed++;
                }
                suppressed += outcome.suppressed;
                all.addAll(outcome.findings);
            }

            all.sort(Comparator
                    .comparing((Finding f) -> f.file() == null ? "" : f.file())
                    .thenComparingInt(f -> f.line() == null ? Integer.MAX_VALUE : f.line())
                    .thenComparingInt(f -> f.severity().ordinal()));

            return new Result(all, reviewed, suppressed);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<FileOutcome> reviewTask(Path file) {
        return () -> {
            String relative = relativize(file);
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_FILE_BYTES) {
                    return FileOutcome.skipped(String.format(
                            "  - %s: skipped (%,d bytes exceeds limit)", relative, bytes.length));
                }
                String code = new String(bytes, StandardCharsets.UTF_8);
                if (code.isBlank()) {
                    return FileOutcome.skipped("  - " + relative + ": skipped (empty)");
                }

                FileDiff diff = diffsByPath.get(relative);
                if (diffMode && diff != null && diff.isEmpty()) {
                    return FileOutcome.skipped("  - " + relative + ": skipped (no changed lines)");
                }
                String changedLines = diff == null ? "(whole file)" : diff.describeRanges();

                String raw = assistant.review(relative, code, changedLines);
                ReviewResult result = parser.parse(raw);

                List<Finding> withPath = result.findings().stream()
                        .map(f -> f.withFile(relative))
                        .toList();
                FindingFilter.Result filtered = filter.filter(withPath, diff);

                String note = filtered.suppressed() == 0
                        ? ""
                        : " (" + filtered.suppressed() + " suppressed)";
                return new FileOutcome(
                        filtered.kept(), true, filtered.suppressed(),
                        String.format("  - %s: %d finding(s)%s", relative, filtered.kept().size(), note));
            } catch (Exception e) {
                // One bad file shouldn't abort the whole run.
                return FileOutcome.skipped(String.format("  - %s: review failed (%s)", relative, e.getMessage()));
            }
        };
    }

    private static FileOutcome get(Future<FileOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FileOutcome.skipped("  - interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return FileOutcome.skipped("  - review failed (" + cause.getMessage() + ")");
        }
    }

    static String relativize(Path file) {
        Path cwd = Path.of("").toAbsolutePath();
        Path abs = file.toAbsolutePath();
        try {
            return cwd.relativize(abs).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    /**
     * @param findings   kept findings across all files, sorted by file then line
     * @param reviewed   number of files actually sent to the model
     * @param suppressed number of findings dropped by the filter
     */
    public record Result(List<Finding> findings, int reviewed, int suppressed) {
    }

    private record FileOutcome(List<Finding> findings, boolean reviewed, int suppressed, String logLine) {
        static FileOutcome skipped(String logLine) {
            return new FileOutcome(List.of(), false, 0, logLine);
        }
    }
}
