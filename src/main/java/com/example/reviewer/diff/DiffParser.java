package com.example.reviewer.diff;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff (the output of {@code git diff}) into, for each file, the set of
 * lines that were added or modified — expressed in <em>new-file</em> coordinates.
 *
 * <p>This is what makes the reviewer diff-aware: we still send the whole file to the model
 * for context, but only surface findings on (or next to) the lines the pull request actually
 * touched, which is the difference between a useful reviewer and a noisy one.
 *
 * <p>The parser is deliberately tolerant — it ignores index/mode/similarity lines, handles
 * added and renamed files, and skips deletions (which have no new-file lines to review).
 */
public final class DiffParser {

    // @@ -oldStart,oldCount +newStart,newCount @@ [section heading]
    // The counts are optional (a single-line hunk omits ",count").
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private DiffParser() {
    }

    /**
     * @return map of repository-relative path → {@link FileDiff}, preserving the order files
     *         appear in the diff. Files with no added/modified lines (pure deletions) are omitted.
     */
    public static Map<String, FileDiff> parse(String diff) {
        Map<String, TreeSet<Integer>> changed = new LinkedHashMap<>();
        if (diff == null || diff.isBlank()) {
            return Map.of();
        }

        String currentPath = null;   // new-file path of the file currently being parsed
        int newLine = 0;             // next new-file line number within the current hunk
        boolean inHunk = false;

        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git")) {
                // Start of a new file section; the real path comes from the "+++" line.
                currentPath = null;
                inHunk = false;
                continue;
            }
            if (line.startsWith("+++ ")) {
                currentPath = parsePath(line.substring(4));
                inHunk = false;
                continue;
            }
            if (line.startsWith("--- ")) {
                continue; // old-file path; we only track the new side
            }

            Matcher m = HUNK_HEADER.matcher(line);
            if (m.find()) {
                newLine = Integer.parseInt(m.group(1));
                inHunk = true;
                continue;
            }

            if (!inHunk || currentPath == null) {
                continue; // header/metadata noise between files
            }

            if (line.startsWith("\\")) {
                continue; // e.g. "\ No newline at end of file"
            }
            if (line.startsWith("+")) {
                changed.computeIfAbsent(currentPath, k -> new TreeSet<>()).add(newLine);
                newLine++;
            } else if (line.startsWith("-")) {
                // Removed line: present only in the old file, does not advance new-file counter.
            } else {
                // Context line (starts with a space) or a blank line within the hunk.
                newLine++;
            }
        }

        Map<String, FileDiff> result = new LinkedHashMap<>();
        for (Map.Entry<String, TreeSet<Integer>> e : changed.entrySet()) {
            result.put(e.getKey(), new FileDiff(e.getKey(), e.getValue()));
        }
        return result;
    }

    /**
     * Extracts a usable repository-relative path from a "+++"/"---" line value, stripping the
     * conventional {@code a/} or {@code b/} prefix and surrounding quotes git adds for paths
     * with special characters. Returns {@code null} for {@code /dev/null} (added/deleted side).
     */
    private static String parsePath(String raw) {
        String p = raw.trim();
        // git may append a tab and timestamp/metadata: "b/path\t2024-..."; cut at the first tab.
        int tab = p.indexOf('\t');
        if (tab >= 0) {
            p = p.substring(0, tab);
        }
        if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) {
            p = p.substring(1, p.length() - 1);
        }
        if (p.equals("/dev/null")) {
            return null;
        }
        if (p.startsWith("a/") || p.startsWith("b/")) {
            p = p.substring(2);
        }
        return p;
    }
}
