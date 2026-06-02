package com.example.reviewer.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Severity of a finding, ordered from most to least serious.
 * The declaration order matters: {@link #isAtLeast} relies on the enum ordinal,
 * so CRITICAL must come first and INFO last.
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    @JsonEnumDefaultValue
    INFO;

    /** True if this severity is as serious as, or more serious than, {@code threshold}. */
    public boolean isAtLeast(Severity threshold) {
        // Lower ordinal == more serious.
        return this.ordinal() <= threshold.ordinal();
    }

    /** SARIF level mapping (error | warning | note). */
    public String sarifLevel() {
        return switch (this) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO -> "note";
        };
    }

    /**
     * CVSS-style numeric score (0-10) exposed as the {@code security-severity} SARIF property.
     * GitHub code scanning uses this to bucket and sort findings (>=9 critical, >=7 high,
     * >=4 medium, else low), independent of the SARIF {@code level}.
     */
    public String securitySeverity() {
        return switch (this) {
            case CRITICAL -> "9.5";
            case HIGH -> "8.0";
            case MEDIUM -> "5.0";
            case LOW -> "3.0";
            case INFO -> "1.0";
        };
    }

    public String emoji() {
        return switch (this) {
            case CRITICAL -> "\uD83D\uDD25"; // fire
            case HIGH -> "\uD83D\uDD34";     // red circle
            case MEDIUM -> "\uD83D\uDFE0";   // orange circle
            case LOW -> "\uD83D\uDFE1";      // yellow circle
            case INFO -> "\u2139\uFE0F";     // info
        };
    }
}
