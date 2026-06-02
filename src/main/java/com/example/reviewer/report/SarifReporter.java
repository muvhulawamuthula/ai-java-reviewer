package com.example.reviewer.report;

import com.example.reviewer.model.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits a SARIF 2.1.0 document. When uploaded with github/codeql-action/upload-sarif,
 * GitHub renders each finding as an inline annotation on the PR diff and in the
 * repository's "Code scanning" tab.
 */
public final class SarifReporter {

    private static final String SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Schemata/sarif-schema-2.1.0.json";

    private final ObjectMapper mapper = new ObjectMapper();

    public String render(List<Finding> findings) {
        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", SCHEMA);
        root.put("version", "2.1.0");

        ArrayNode runs = root.putArray("runs");
        ObjectNode run = runs.addObject();

        // tool.driver with the set of rules we actually emitted.
        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", "AI Java Reviewer");
        driver.put("informationUri", "https://github.com/your-org/ai-java-reviewer");
        driver.put("version", "0.1.0");

        ArrayNode rules = driver.putArray("rules");
        Set<String> seenRules = new HashSet<>();
        for (Finding f : findings) {
            String id = f.ruleId();
            if (seenRules.add(id)) {
                ObjectNode rule = rules.addObject();
                rule.put("id", id);
                rule.put("name", id.replace("/", "_"));
                rule.putObject("shortDescription").put("text", f.category().label() + " issue");
                rule.putObject("fullDescription").put("text",
                        f.category().label() + " findings reported by the AI Java Reviewer"
                                + (f.cwe() != null && !f.cwe().isBlank() ? " (" + f.cwe().trim() + ")" : ""));
                rule.putObject("defaultConfiguration").put("level", f.severity().sarifLevel());
                // GitHub code scanning reads security-severity to bucket/sort findings.
                rule.putObject("properties").put("security-severity", f.severity().securitySeverity());
                String url = f.cweUrl();
                if (url != null) {
                    rule.put("helpUri", url);
                }
            }
        }

        ArrayNode results = run.putArray("results");
        for (Finding f : findings) {
            ObjectNode result = results.addObject();
            result.put("ruleId", f.ruleId());
            result.put("level", f.severity().sarifLevel());

            String text = "[" + f.severity().name() + " / " + f.confidence().name() + " confidence] " + f.title();
            if (f.description() != null && !f.description().isBlank()) {
                text += "\n\n" + f.description();
            }
            if (f.suggestion() != null && !f.suggestion().isBlank()) {
                text += "\n\nSuggested fix: " + f.suggestion();
            }
            result.putObject("message").put("text", text);

            ObjectNode physicalLocation = result.putArray("locations")
                    .addObject()
                    .putObject("physicalLocation");
            physicalLocation.putObject("artifactLocation")
                    .put("uri", f.file() == null ? "unknown" : f.file());
            // SARIF requires a region; default to line 1 when the model didn't localize.
            int line = (f.line() != null && f.line() > 0) ? f.line() : 1;
            physicalLocation.putObject("region").put("startLine", line);

            // Stable fingerprint so code scanning treats the same issue across re-runs as one alert.
            result.putObject("partialFingerprints").put("aiReviewer/v1", f.fingerprint());
            result.putObject("properties").put("confidence", f.confidence().name());
        }

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SARIF", e);
        }
    }
}
