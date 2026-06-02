package com.example.reviewer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single issue found in a file. Field names match the JSON keys the model is
 * instructed to emit (see {@code system-prompt.txt}).
 *
 * @param category   the class of issue
 * @param severity   how serious it is
 * @param confidence how confident the model is that this is a real, actionable issue
 * @param title      a short headline (e.g. "SQL injection via string concatenation")
 * @param line       1-based line number in the reviewed file, or 0/null if not localizable
 * @param description what the problem is and why it matters
 * @param suggestion  how to fix it (may be null)
 * @param cwe         optional CWE identifier for security findings (e.g. "CWE-89"), may be null
 * @param file        populated by the reviewer, not the model (path of the file this came from)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(
        Category category,
        Severity severity,
        Confidence confidence,
        String title,
        Integer line,
        String description,
        String suggestion,
        String cwe,
        String file
) {
    public Finding {
        if (category == null) category = Category.STYLE;
        if (severity == null) severity = Severity.INFO;
        if (confidence == null) confidence = Confidence.MEDIUM;
        if (title == null || title.isBlank()) title = "(untitled finding)";
    }

    /** Returns a copy with the file path set (the model doesn't know the path). */
    public Finding withFile(String filePath) {
        return new Finding(category, severity, confidence, title, line, description, suggestion, cwe, filePath);
    }

    /** A stable rule id used for SARIF grouping, e.g. "SECURITY/CWE-89" or "COMPLEXITY". */
    public String ruleId() {
        if (cwe != null && !cwe.isBlank()) {
            return category.name() + "/" + cwe.trim();
        }
        return category.name();
    }

    /**
     * A MITRE CWE definition URL when this finding carries a well-formed CWE id (e.g.
     * "CWE-89" -> https://cwe.mitre.org/data/definitions/89.html), otherwise {@code null}.
     */
    public String cweUrl() {
        if (cwe == null) {
            return null;
        }
        String digits = cwe.replaceAll("\\D", "");
        return digits.isEmpty() ? null : "https://cwe.mitre.org/data/definitions/" + digits + ".html";
    }

    /**
     * A stable hash identifying this finding by location and content, used to deduplicate it
     * across re-runs — both in the SARIF Code scanning tab and in inline PR comments. Excludes
     * mutable wording like the description so cosmetic re-phrasings don't read as a new finding.
     */
    public String fingerprint() {
        String basis = (file == null ? "" : file) + "|" + ruleId() + "|" + line + "|" + title;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 16); // 64 bits of stability is plenty for dedup
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(basis.hashCode());
        }
    }
}
