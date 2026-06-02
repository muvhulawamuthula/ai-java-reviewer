package com.example.reviewer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The structured response the model returns for a single file:
 * a one-paragraph summary plus the list of findings.
 *
 * @param summary  a brief plain-language assessment of the file
 * @param findings the issues found (may be empty)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewResult(String summary, List<Finding> findings) {
    public ReviewResult {
        if (findings == null) findings = List.of();
        if (summary == null) summary = "";
    }
}
