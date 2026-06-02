package com.example.reviewer.ai;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * The AI Service interface. langchain4j generates the implementation at runtime via
 * {@link dev.langchain4j.service.AiServices}. The system message is supplied by a
 * {@code systemMessageProvider} (loaded from {@code system-prompt.txt}); here we only
 * declare the user message template.
 *
 * <p>The method returns the raw model response as a String. We deliberately parse the
 * JSON ourselves (with Jackson) rather than letting AiServices coerce it into a POJO,
 * so that we control fence-stripping and error handling and avoid depending on
 * provider-specific structured-output capability flags.
 */
public interface CodeReviewAssistant {

    @UserMessage("""
            Review this Java file. The file path is `{{filename}}`.

            CHANGED LINES (new-file line numbers added or modified in this pull request):
            {{changedLines}}

            If the CHANGED LINES section lists specific lines/ranges, only report issues on
            those lines, or issues elsewhere that these changes directly cause. If it says
            "(whole file)", review the entire file.

            --- BEGIN FILE ---
            {{code}}
            --- END FILE ---

            Return only the JSON object described in your instructions.
            """)
    String review(@V("filename") String filename, @V("code") String code, @V("changedLines") String changedLines);
}
