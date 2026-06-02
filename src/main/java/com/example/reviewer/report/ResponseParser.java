package com.example.reviewer.report;

import com.example.reviewer.model.ReviewResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Turns the raw model response string into a {@link ReviewResult}. */
public final class ResponseParser {

    private final ObjectMapper mapper;

    public ResponseParser() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
    }

    public ReviewResult parse(String raw) throws Exception {
        String json = extractJson(raw);
        return mapper.readValue(json, ReviewResult.class);
    }

    /**
     * Defensively pulls the JSON object out of the response. The prompt asks for bare
     * JSON, but models occasionally wrap it in ```json fences or add a stray sentence,
     * so we slice from the first '{' to the last '}'.
     */
    static String extractJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Model returned no content");
        }
        String s = raw.trim();
        // Strip a leading/trailing markdown fence if present.
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            throw new IllegalArgumentException(
                    "Could not find a JSON object in the model response: " + truncate(s));
        }
        return s.substring(start, end + 1);
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
