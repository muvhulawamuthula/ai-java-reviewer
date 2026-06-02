package com.example.reviewer.review;

import com.example.reviewer.diff.FileDiff;
import com.example.reviewer.model.Confidence;
import com.example.reviewer.model.Finding;
import com.example.reviewer.model.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which raw model findings survive into the report. This is the noise-control
 * stage and is deliberately a <em>pure</em> function (no I/O, no model calls) so it can be
 * unit-tested exhaustively. Two independent gates apply:
 *
 * <ol>
 *   <li><b>Confidence</b> — drop findings the model is less sure about than
 *       {@code minConfidence}.</li>
 *   <li><b>Diff scope</b> — in diff-aware mode, drop findings that don't touch the lines the
 *       pull request changed. Unlocalized findings (no line number) are kept only when serious,
 *       since a file-level security/data-loss concern is worth surfacing even without a line.</li>
 * </ol>
 *
 * In whole-file mode (no {@link FileDiff}) only the confidence gate applies.
 */
public final class FindingFilter {

    private final Confidence minConfidence;
    private final int slack;

    /**
     * @param minConfidence findings below this confidence are suppressed
     * @param slack         how many lines of tolerance to allow around a changed line when
     *                      matching a finding's reported line (models localize imprecisely)
     */
    public FindingFilter(Confidence minConfidence, int slack) {
        this.minConfidence = minConfidence == null ? Confidence.LOW : minConfidence;
        this.slack = Math.max(0, slack);
    }

    /**
     * @param findings raw findings for a single file
     * @param diff     the file's changed lines, or {@code null} for whole-file mode
     */
    public Result filter(List<Finding> findings, FileDiff diff) {
        List<Finding> kept = new ArrayList<>();
        int suppressed = 0;
        for (Finding f : findings) {
            if (keep(f, diff)) {
                kept.add(f);
            } else {
                suppressed++;
            }
        }
        return new Result(kept, suppressed);
    }

    private boolean keep(Finding f, FileDiff diff) {
        if (!f.confidence().isAtLeast(minConfidence)) {
            return false;
        }
        if (diff == null) {
            return true; // whole-file mode: confidence is the only gate
        }
        boolean localized = f.line() != null && f.line() > 0;
        if (!localized) {
            // No line to anchor to the diff; keep only genuinely serious issues.
            return f.severity().isAtLeast(Severity.HIGH);
        }
        return diff.touches(f.line(), slack);
    }

    /**
     * @param kept       findings that passed both gates
     * @param suppressed how many were dropped (for the run log)
     */
    public record Result(List<Finding> kept, int suppressed) {
    }
}
