package com.example.reviewer.report;

import com.example.reviewer.model.Category;
import com.example.reviewer.model.Confidence;
import com.example.reviewer.model.Finding;
import com.example.reviewer.model.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SarifReporterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode render(List<Finding> findings) throws Exception {
        return mapper.readTree(new SarifReporter().render(findings));
    }

    @Test
    void emptyFindingsProduceValidSarifSkeleton() throws Exception {
        JsonNode root = render(List.of());
        assertEquals("2.1.0", root.get("version").asText());
        assertEquals(0, root.path("runs").get(0).path("results").size());
    }

    @Test
    void securityFindingGetsCweHelpUriAndSecuritySeverity() throws Exception {
        Finding f = new Finding(Category.SECURITY, Severity.CRITICAL, Confidence.HIGH,
                "SQL injection", 12, "concatenated query", "use PreparedStatement", "CWE-89", "Db.java");
        JsonNode run = render(List.of(f)).path("runs").get(0);

        JsonNode rule = run.path("tool").path("driver").path("rules").get(0);
        assertEquals("SECURITY/CWE-89", rule.get("id").asText());
        assertTrue(rule.get("helpUri").asText().endsWith("/89.html"));
        assertEquals("9.5", rule.path("properties").get("security-severity").asText());

        JsonNode result = run.path("results").get(0);
        assertEquals("error", result.get("level").asText());
        assertEquals(12, result.path("locations").get(0)
                .path("physicalLocation").path("region").get("startLine").asInt());
        assertTrue(result.path("partialFingerprints").has("aiReviewer/v1"));
        assertEquals("HIGH", result.path("properties").get("confidence").asText());
    }

    @Test
    void unlocalizedFindingDefaultsToLineOne() throws Exception {
        Finding f = new Finding(Category.ANTI_PATTERN, Severity.MEDIUM, Confidence.MEDIUM,
                "Mutable static", null, "global state", null, null, "Cfg.java");
        JsonNode result = render(List.of(f)).path("runs").get(0).path("results").get(0);
        assertEquals(1, result.path("locations").get(0)
                .path("physicalLocation").path("region").get("startLine").asInt());
    }

    @Test
    void fingerprintIsStableForSameFinding() {
        Finding a = new Finding(Category.BUG, Severity.HIGH, Confidence.HIGH, "NPE", 5, "d1", null, null, "A.java");
        Finding b = new Finding(Category.BUG, Severity.HIGH, Confidence.LOW, "NPE", 5, "different desc", "fix", null, "A.java");
        // Same location + rule + title => same fingerprint, despite different wording/confidence.
        assertEquals(a.fingerprint(), b.fingerprint());
    }
}
