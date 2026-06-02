package com.example.reviewer.ai;

import com.example.reviewer.model.Confidence;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Builds a {@link CodeReviewAssistant} backed by Claude via langchain4j. */
public final class ReviewerFactory {

    private ReviewerFactory() {
    }

    public static CodeReviewAssistant create(Config config) {
        ChatModel model = AnthropicChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(config.model())
                .maxTokens(config.maxTokens())
                .temperature(0.0)               // deterministic, factual reviews
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .maxRetries(2)
                // The system prompt (the review rubric) is identical for every file, so cache it
                // on Anthropic's side: subsequent files in the same PR read it from cache instead
                // of re-sending ~2 KB of instructions, cutting input cost and latency.
                .cacheSystemMessages(true)
                .logRequests(config.verbose())
                .logResponses(config.verbose())
                .build();

        return AiServices.builder(CodeReviewAssistant.class)
                .chatModel(model)
                .systemMessageProvider(memoryId -> loadSystemPrompt())
                .build();
    }

    private static String loadSystemPrompt() {
        try (InputStream in = ReviewerFactory.class.getResourceAsStream("/system-prompt.txt")) {
            if (in == null) {
                throw new IllegalStateException("system-prompt.txt not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read system-prompt.txt", e);
        }
    }

    /** Immutable runtime configuration, populated from environment variables. */
    public record Config(
            String apiKey,
            String model,
            int maxTokens,
            int timeoutSeconds,
            int concurrency,
            Confidence minConfidence,
            boolean verbose
    ) {
        public static Config fromEnv() {
            String key = getenv("ANTHROPIC_API_KEY", null);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(
                        "ANTHROPIC_API_KEY environment variable is required but not set.");
            }
            return new Config(
                    key,
                    getenv("REVIEWER_MODEL", "claude-sonnet-4-6"),
                    parseInt(getenv("REVIEWER_MAX_TOKENS", "8000"), 8000),
                    parseInt(getenv("REVIEWER_TIMEOUT_SECONDS", "120"), 120),
                    parseInt(getenv("REVIEWER_CONCURRENCY", "4"), 4),
                    parseConfidence(getenv("REVIEWER_MIN_CONFIDENCE", "LOW")),
                    Boolean.parseBoolean(getenv("REVIEWER_VERBOSE", "false"))
            );
        }

        private static String getenv(String name, String fallback) {
            String v = System.getenv(name);
            return (v == null || v.isBlank()) ? fallback : v;
        }

        private static int parseInt(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        private static Confidence parseConfidence(String s) {
            try {
                return Confidence.valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("Unknown REVIEWER_MIN_CONFIDENCE '" + s + "', defaulting to LOW");
                return Confidence.LOW;
            }
        }
    }
}
