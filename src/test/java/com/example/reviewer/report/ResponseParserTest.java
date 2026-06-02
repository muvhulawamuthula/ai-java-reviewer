package com.example.reviewer.report;

import com.example.reviewer.model.Confidence;
import com.example.reviewer.model.ReviewResult;
import com.example.reviewer.model.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseParserTest {

    private final ResponseParser parser = new ResponseParser();

    @Test
    void parsesPlainJson() throws Exception {
        String raw = """
                {"summary":"ok","findings":[
                  {"category":"SECURITY","severity":"HIGH","confidence":"HIGH","title":"SQLi","line":12,"cwe":"CWE-89"}
                ]}""";
        ReviewResult r = parser.parse(raw);
        assertEquals(1, r.findings().size());
        assertEquals(Severity.HIGH, r.findings().get(0).severity());
        assertEquals(Confidence.HIGH, r.findings().get(0).confidence());
        assertEquals(12, r.findings().get(0).line());
    }

    @Test
    void stripsMarkdownFences() throws Exception {
        String raw = "```json\n{\"summary\":\"s\",\"findings\":[]}\n```";
        ReviewResult r = parser.parse(raw);
        assertEquals("s", r.summary());
        assertTrue(r.findings().isEmpty());
    }

    @Test
    void toleratesSurroundingProse() throws Exception {
        String raw = "Here is the review:\n{\"summary\":\"s\",\"findings\":[]}\nThanks!";
        ReviewResult r = parser.parse(raw);
        assertEquals("s", r.summary());
    }

    @Test
    void unknownEnumValuesFallBackToDefaults() throws Exception {
        String raw = """
                {"findings":[{"category":"WAT","severity":"NOPE","confidence":"???","title":"x"}]}""";
        ReviewResult r = parser.parse(raw);
        // Defaults: STYLE / INFO / LOW per @JsonEnumDefaultValue.
        assertEquals(Severity.INFO, r.findings().get(0).severity());
        assertEquals(Confidence.LOW, r.findings().get(0).confidence());
    }

    @Test
    void throwsWhenNoJsonObjectPresent() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("no json here"));
        assertThrows(IllegalArgumentException.class, () -> ResponseParser.extractJson(null));
    }
}
