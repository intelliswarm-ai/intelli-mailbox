/**
 * Wire types for /api/inbox + /api/inbox/stream. Mirrors the JSON the
 * server emits — InboxItem (Java record), EnrichedEmail, Cta, ReplyDrafts,
 * PipelineEvent.
 */

export type Badge =
  | 'MEETING' | 'RISK' | 'EXTERNAL' | 'AUTOMATED'
  | 'VIP' | 'FOLLOW_UP' | 'NEWSLETTER' | 'FINANCE';

export const ALL_BADGES: Badge[] = [
  'MEETING', 'RISK', 'EXTERNAL', 'AUTOMATED',
  'VIP', 'FOLLOW_UP', 'NEWSLETTER', 'FINANCE',
];

export type CtaType = 'REPLY' | 'REVIEW' | 'SCHEDULE' | 'PAY' | 'SUBMIT' | 'READ' | 'OTHER';
export type Priority = 'LOW' | 'MEDIUM' | 'HIGH';

export interface Cta {
  text: string;
  dueDate: string | null;
  type: string | null;        // CtaType, but server sends raw
  priority: string | null;    // Priority, but server sends raw
  sourceQuote: string | null;
}

export interface ReplyDrafts {
  formal: string;
  friendly: string;
  brief: string;
}

export interface InboxItem {
  id: string;
  sender: string;
  subject: string;
  snippet: string;
  time: string;
}

export interface EnrichedEmail {
  item: InboxItem;
  summary: string;
  badges: Badge[];
  ctas: Cta[];
  phishingSuspected: boolean;
  needsReply: boolean;
  replyDrafts: ReplyDrafts | null;
  processedMs: number;
  failed: boolean;
}

/** SSE summary_delta event payload (token-by-token streaming). */
export interface SummaryDelta {
  emailId: string;
  text: string;
}

/**
 * SSE email event payload (terminal event per row).
 * Note: server unwraps PipelineEvent.Enriched and sends the EnrichedEmail
 * directly — see InboxController.stream(). Consumers key by item.id.
 */
export type EnrichedEvent = EnrichedEmail;

/** Date-range options for /api/inbox?range=… */
export type Range = '1d' | '2d' | '3d' | '7d' | '14d' | '30d' | 'all';

export const ALLOWED_RANGES: Range[] = ['1d', '2d', '3d', '7d', '14d', '30d', 'all'];

export const RANGE_LABELS: Record<Range, string> = {
  '1d':  'last 24 hours',
  '2d':  'last 2 days',
  '3d':  'last 3 days',
  '7d':  'last week',
  '14d': 'last 2 weeks',
  '30d': 'last month',
  'all': 'current view (no date filter)',
};

/** Per-row UI status. Driven from items + enriched + in-flight set. */
export type RowStatus = 'pending' | 'analyzing' | 'analyzed' | 'failed' | 'excluded';
