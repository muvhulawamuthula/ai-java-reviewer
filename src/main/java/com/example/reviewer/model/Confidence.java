package com.example.reviewer.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * How confident the model is that a finding is real and actionable, ordered from
 * most to least confident. Used to suppress likely false positives: a run can be
 * configured (via {@code REVIEWER_MIN_CONFIDENCE}) to drop findings below a
 * threshold.
 *
 * <p>Declaration order matters: {@link #isAtLeast} relies on the enum ordinal, so
 * HIGH must come first and LOW last.
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    @JsonEnumDefaultValue
    LOW;

    /** True if this confidence is as strong as, or stronger than, {@code threshold}. */
    public boolean isAtLeast(Confidence threshold) {
        // Lower ordinal == more confident.
        return this.ordinal() <= threshold.ordinal();
    }
}
