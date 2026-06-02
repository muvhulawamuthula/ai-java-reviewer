package com.example.reviewer.review;

import com.example.reviewer.diff.FileDiff;
import com.example.reviewer.model.Category;
import com.example.reviewer.model.Confidence;
import com.example.reviewer.model.Finding;
import com.example.reviewer.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FindingFilterTest {

    private static Finding at(int line, Severity sev, Confidence conf) {
        return new Finding(Category.BUG, sev, conf, "t", line, "d", null, null, "F.java");
    }

    @Test
    void wholeFileModeAppliesOnlyConfidenceGate() {
        FindingFilter filter = new FindingFilter(Confidence.MEDIUM, 2);
        List<Finding> in = List.of(
                at(5, Severity.LOW, Confidence.HIGH),
                at(6, Severity.LOW, Confidence.MEDIUM),
                at(7, Severity.LOW, Confidence.LOW)); // dropped: below MEDIUM
        FindingFilter.Result r = filter.filter(in, null);
        assertEquals(2, r.kept().size());
        assertEquals(1, r.suppressed());
    }

    @Test
    void diffModeKeepsOnlyFindingsTouchingChangedLines() {
        FileDiff diff = new FileDiff("F.java", new TreeSet<>(List.of(20, 21)));
        FindingFilter filter = new FindingFilter(Confidence.LOW, 2);
        List<Finding> in = List.of(
                at(20, Severity.LOW, Confidence.HIGH),  // exact
                at(22, Severity.LOW, Confidence.HIGH),  // within slack of 20/21
                at(50, Severity.LOW, Confidence.HIGH)); // out of scope
        FindingFilter.Result r = filter.filter(in, diff);
        assertEquals(2, r.kept().size());
        assertEquals(1, r.suppressed());
    }

    @Test
    void unlocalizedFindingKeptOnlyWhenSerious() {
        FileDiff diff = new FileDiff("F.java", new TreeSet<>(List.of(20)));
        FindingFilter filter = new FindingFilter(Confidence.LOW, 2);
        List<Finding> in = List.of(
                at(0, Severity.CRITICAL, Confidence.HIGH), // no line, serious -> kept
                at(0, Severity.MEDIUM, Confidence.HIGH));   // no line, not serious -> dropped
        FindingFilter.Result r = filter.filter(in, diff);
        assertEquals(1, r.kept().size());
        assertEquals(Severity.CRITICAL, r.kept().get(0).severity());
    }

    @Test
    void confidenceAndScopeGatesCompose() {
        FileDiff diff = new FileDiff("F.java", new TreeSet<>(List.of(10)));
        FindingFilter filter = new FindingFilter(Confidence.HIGH, 2);
        List<Finding> in = List.of(
                at(10, Severity.LOW, Confidence.HIGH),   // in scope + confident -> kept
                at(10, Severity.LOW, Confidence.MEDIUM), // in scope but not confident -> dropped
                at(99, Severity.LOW, Confidence.HIGH));  // confident but out of scope -> dropped
        FindingFilter.Result r = filter.filter(in, diff);
        assertEquals(1, r.kept().size());
        assertEquals(2, r.suppressed());
    }
}
