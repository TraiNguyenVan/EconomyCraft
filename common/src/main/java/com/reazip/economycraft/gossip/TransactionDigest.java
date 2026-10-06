package com.reazip.economycraft.gossip;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Intermediate representation of notable economic events prepared for the Gemini prompt.
 */
public record TransactionDigest(
        Duration lookbackWindow,
        int eventCount,
        double currentInflation,
        List<String> formattedLines
) {
    public TransactionDigest {
        if (lookbackWindow == null) {
            lookbackWindow = Duration.ofHours(24);
        }
        if (formattedLines == null) {
            formattedLines = List.of();
        } else {
            formattedLines = Collections.unmodifiableList(formattedLines);
        }
    }

    public static TransactionDigest empty(double currentInflation) {
        return new TransactionDigest(Duration.ofHours(24), 0, currentInflation, List.of());
    }

    /**
     * Converts this digest into a structured prompt context string for Gemini.
     */
    public String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Current server inflation multiplier: %.4fx.\n", currentInflation));
        sb.append(String.format(Locale.ROOT, "Recent notable economic events (past %d hours, %d events):\n",
                lookbackWindow.toHours(), eventCount));

        if (formattedLines.isEmpty()) {
            sb.append("- The market has been relatively quiet with standard trade activity.\n");
        } else {
            for (String line : formattedLines) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
