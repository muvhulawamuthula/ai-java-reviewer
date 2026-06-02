package com.example.reviewer.diff;

import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The set of lines a pull request added or modified in a single file, expressed in
 * <em>new-file</em> coordinates (the line numbers as they appear in the post-change file,
 * which is what we review and what GitHub anchors review comments to).
 *
 * @param path         repository-relative path of the file (the "b/" side of the diff)
 * @param changedLines 1-based new-file line numbers that were added or modified
 */
public record FileDiff(String path, NavigableSet<Integer> changedLines) {

    public FileDiff {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        changedLines = changedLines == null ? new TreeSet<>() : new TreeSet<>(changedLines);
    }

    public boolean isEmpty() {
        return changedLines.isEmpty();
    }

    /** True if {@code line} was changed, or is within {@code slack} lines of a changed line. */
    public boolean touches(int line, int slack) {
        // A finding may localize a line or two off from the exact changed line (e.g. it
        // points at a method signature while the change is one line below), so we accept a
        // small window around the changed lines rather than requiring an exact match.
        Integer floor = changedLines.floor(line);
        if (floor != null && line - floor <= slack) {
            return true;
        }
        Integer ceiling = changedLines.ceiling(line);
        return ceiling != null && ceiling - line <= slack;
    }

    /**
     * A compact human/LLM-readable description of the changed lines, collapsing runs into
     * ranges, e.g. {@code "12-18, 40, 55-57"}. Returns an empty string when nothing changed.
     */
    public String describeRanges() {
        if (changedLines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Integer start = null;
        Integer prev = null;
        for (int line : changedLines) {
            if (start == null) {
                start = line;
            } else if (line != prev + 1) {
                appendRange(sb, start, prev);
                start = line;
            }
            prev = line;
        }
        appendRange(sb, start, prev);
        return sb.toString();
    }

    private static void appendRange(StringBuilder sb, int start, int end) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(start);
        if (end != start) {
            sb.append('-').append(end);
        }
    }
}
