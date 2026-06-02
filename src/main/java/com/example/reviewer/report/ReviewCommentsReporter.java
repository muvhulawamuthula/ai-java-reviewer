package com.example.reviewer.report;

import com.example.reviewer.model.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Emits the payload for line-anchored GitHub pull-request review comments as a JSON array.
 * A workflow step feeds this to the GitHub review API (one review, many comments).
 *
 * <p>Each entry is {@code {path, line, side, body, fingerprint}}. Only findings that carry a
 * concrete line number are included — GitHub rejects review comments on lines outside the PR
 * diff, and in diff-aware mode those lines are exactly the changed lines, so they anchor
 * cleanly. The {@code body} embeds a hidden fingerprint marker so re-runs can avoid posting
 * the same comment twice.
 */
public final class ReviewCommentsReporter {

    /** Hidden HTML-comment prefix that tags each comment with its finding fingerprint. */
    public static final String MARKER_PREFIX = "<!-- ai-reviewer:fp=";

    private final ObjectMapper mapper = new ObjectMapper();

    public String render(List<Finding> findings) {
        ArrayNode arr = mapper.createArrayNode();
        for (Finding f : findings) {
            if (f.line() == null || f.line() <= 0 || f.file() == null) {
                continue; // not anchorable to a diff line
            }
            ObjectNode node = arr.addObject();
            node.put("path", f.file());
            node.put("line", f.line());
            node.put("side", "RIGHT"); // the new version of the file
            node.put("fingerprint", f.fingerprint());
            node.put("body", body(f));
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(arr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize review comments", e);
        }
    }

    private static String body(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER_PREFIX).append(f.fingerprint()).append(" -->\n");
        sb.append(f.severity().emoji()).append(" **").append(f.title()).append("**  \n");
        sb.append("_").append(f.severity().name()).append(" · ").append(f.category().label())
          .append(" · ").append(f.confidence().name()).append(" confidence");
        if (f.cwe() != null && !f.cwe().isBlank()) {
            String url = f.cweUrl();
            sb.append(" · ").append(url != null ? "[" + f.cwe().trim() + "](" + url + ")" : f.cwe().trim());
        }
        sb.append("_\n\n");
        if (f.description() != null && !f.description().isBlank()) {
            sb.append(f.description()).append("\n");
        }
        if (f.suggestion() != null && !f.suggestion().isBlank()) {
            sb.append("\n**Suggested fix:** ").append(f.suggestion()).append('\n');
        }
        return sb.toString();
    }
}
