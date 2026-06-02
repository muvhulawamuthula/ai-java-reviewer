package com.example.reviewer.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** The class of issue a finding represents. */
public enum Category {
    /** Security vulnerabilities: injection, deserialization, crypto misuse, secrets, etc. */
    SECURITY,
    /** Design / coding anti-patterns: God classes, swallowed exceptions, leaky abstractions, etc. */
    ANTI_PATTERN,
    /** Excessive cyclomatic / cognitive complexity, deep nesting, long methods. */
    COMPLEXITY,
    /** Likely functional bugs: NPEs, off-by-one, resource leaks, race conditions. */
    BUG,
    /** Minor style / readability nits. */
    @JsonEnumDefaultValue
    STYLE;

    public String label() {
        return switch (this) {
            case SECURITY -> "Security";
            case ANTI_PATTERN -> "Anti-pattern";
            case COMPLEXITY -> "Complexity";
            case BUG -> "Bug";
            case STYLE -> "Style";
        };
    }
}
