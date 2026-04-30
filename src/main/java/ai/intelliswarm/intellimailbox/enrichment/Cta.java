package ai.intelliswarm.intellimailbox.enrichment;

/**
 * A concrete call-to-action extracted from one email.
 *
 * <p>Designed to survive being deserialized from an LLM response: every field
 * except {@link #text} is nullable, types/priorities are stored as raw strings
 * and validated post-parse against {@link CtaType} / {@link Priority}.
 *
 * @param text         the action restated in 1 short sentence (≤200 chars)
 * @param dueDate      ISO-8601 date string ("YYYY-MM-DD") if a deadline is mentioned, else null
 * @param type         {@link CtaType} name, lower-cased on output for the UI
 * @param priority     {@link Priority} name, lower-cased on output for the UI
 * @param sourceQuote  short verbatim snippet from the email that justifies this CTA, or null
 */
public record Cta(
        String text,
        String dueDate,
        String type,
        String priority,
        String sourceQuote
) {
    public CtaType typeEnum() {
        return parseEnumOrDefault(type, CtaType.OTHER, CtaType.class);
    }

    public Priority priorityEnum() {
        return parseEnumOrDefault(priority, Priority.MEDIUM, Priority.class);
    }

    private static <E extends Enum<E>> E parseEnumOrDefault(String s, E fallback, Class<E> cls) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Enum.valueOf(cls, s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
