package ai.intelliswarm.intellimailbox.enrichment;

/**
 * Multi-dimensional category for an email. Multiple badges can apply to one
 * email (e.g. a meeting with a VIP). Replaces the single-axis {@code urgency}
 * field with something the UI can filter on.
 *
 * <p>Modeled on the 8 categories used by the enterprise-mailbox-assistant
 * Python project ({@code llm_service.py}).
 */
public enum Badge {
    /** A meeting invite, calendar request, or scheduling discussion. */
    MEETING,
    /** Phishing / suspicious / urgent-warning content — overrides others when set. */
    RISK,
    /** From outside the recipient's organization. */
    EXTERNAL,
    /** No-reply / system-generated / transactional notifications. */
    AUTOMATED,
    /** From or to a high-priority contact (CEO, customer exec, …). */
    VIP,
    /** Awaiting a response, ping, or follow-up. */
    FOLLOW_UP,
    /** Newsletter, digest, marketing — read-only. */
    NEWSLETTER,
    /** Invoices, receipts, banking, payments. */
    FINANCE
}
