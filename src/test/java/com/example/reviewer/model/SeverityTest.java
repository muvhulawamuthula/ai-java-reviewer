package com.example.reviewer.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeverityTest {

    @Test
    void isAtLeastFollowsSeverityOrder() {
        assertTrue(Severity.CRITICAL.isAtLeast(Severity.HIGH));
        assertTrue(Severity.HIGH.isAtLeast(Severity.HIGH));
        assertFalse(Severity.MEDIUM.isAtLeast(Severity.HIGH));
        assertFalse(Severity.INFO.isAtLeast(Severity.LOW));
    }

    @Test
    void sarifLevelMapping() {
        assertEquals("error", Severity.CRITICAL.sarifLevel());
        assertEquals("error", Severity.HIGH.sarifLevel());
        assertEquals("warning", Severity.MEDIUM.sarifLevel());
        assertEquals("note", Severity.LOW.sarifLevel());
        assertEquals("note", Severity.INFO.sarifLevel());
    }

    @Test
    void securitySeverityIsDescending() {
        double critical = Double.parseDouble(Severity.CRITICAL.securitySeverity());
        double high = Double.parseDouble(Severity.HIGH.securitySeverity());
        double medium = Double.parseDouble(Severity.MEDIUM.securitySeverity());
        double low = Double.parseDouble(Severity.LOW.securitySeverity());
        assertTrue(critical > high && high > medium && medium > low);
    }

    @Test
    void confidenceIsAtLeastFollowsOrder() {
        assertTrue(Confidence.HIGH.isAtLeast(Confidence.MEDIUM));
        assertTrue(Confidence.MEDIUM.isAtLeast(Confidence.MEDIUM));
        assertFalse(Confidence.LOW.isAtLeast(Confidence.MEDIUM));
    }
}
