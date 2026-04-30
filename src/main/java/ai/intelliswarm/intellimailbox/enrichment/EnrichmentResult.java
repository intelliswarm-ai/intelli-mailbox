package ai.intelliswarm.intellimailbox.enrichment;

import java.util.List;

/**
 * Raw LLM output for one email — we ask the model to return this whole shape
 * in a single call (cheaper than separate badges + CTAs + summary calls).
 *
 * <p>Mutable POJO (not a record) on purpose: Spring AI's
 * {@code BeanOutputConverter} works most smoothly with bean-style POJOs and
 * setter-injected fields when the LLM's JSON schema is nested.
 */
public class EnrichmentResult {

    /** 1–2 sentence summary, plain prose, no markdown. */
    public String summary;

    /** Subset of {@link Badge} names that apply, e.g. {@code ["MEETING", "VIP"]}. */
    public List<String> badges;

    /** Concrete actions extracted from the body. Empty list if nothing actionable. */
    public List<Cta> ctas;

    /** True iff the email looks like a phishing attempt (case still flagged in badges as RISK). */
    public Boolean phishingSuspected;
}
