package ai.intelliswarm.intellimailbox.enrichment;

/** What kind of action the email is asking for. */
public enum CtaType {
    REPLY,      // respond with information / decision
    REVIEW,     // read or approve a doc / PR / proposal
    SCHEDULE,   // accept or arrange a meeting / call
    PAY,        // settle an invoice, transfer money
    SUBMIT,     // file a form, expense, report
    READ,       // FYI, read-only
    OTHER
}
