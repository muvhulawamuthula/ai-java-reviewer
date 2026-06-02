package com.example.reviewer.report;

import com.example.reviewer.model.Category;
import com.example.reviewer.model.Confidence;
import com.example.reviewer.model.Finding;
import com.example.reviewer.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownReporterTest {

    private final MarkdownReporter reporter = new MarkdownReporter();

    @Test
    void emptyReportSaysNoIssuesAndCarriesMarker() {
        String md = reporter.render(List.of(), 3);
        assertTrue(md.startsWith(MarkdownReporter.MARKER));
        assertTrue(md.contains("No issues found"));
        assertTrue(md.contains("**3**"));
    }

    @Test
    void populatedReportShowsSeverityConfidenceAndEscapesHtml() {
        Finding f = new Finding(Category.SECURITY, Severity.HIGH, Confidence.MEDIUM,
                "Bad <thing>", 7, "uses List<String> badly", "fix it", "CWE-89", "A.java");
        String md = reporter.render(List.of(f), 1);

        assertTrue(md.contains("HIGH"));
        assertTrue(md.contains("MEDIUM confidence"));
        assertTrue(md.contains("`A.java`"));
        assertTrue(md.contains("CWE-89"));
        assertTrue(md.contains("(line 7)"));
        // Angle brackets must be escaped so they don't break Markdown/HTML rendering.
        assertTrue(md.contains("Bad &lt;thing&gt;"));
        assertFalse(md.contains("Bad <thing>"));
    }
}
