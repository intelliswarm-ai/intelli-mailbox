/**
 * Wire types for /api/settings. Mirrors the JSON produced by
 * SettingsController.get() / save(). The modal uses these directly —
 * no DTO mapping in between.
 */

export interface ProviderEntry {
  id: string;
  label: string;
  description: string;
  transport: string;
  kind: string;
  isLocal: boolean;
  requiresApiKey: boolean;
  defaultBaseUrl: string;
  defaultModel: string;
  suggestedModels: string[];
  homepage: string;
  signupUrl: string;
  modelCatalogUrl: string;
  icon: string;
  setupNotes: string;
  envVars: { baseUrl: string; apiKey: string; model: string };
  config: ProviderUserConfig;
}

export interface ProviderUserConfig {
  baseUrl: string;
  model: string;
  apiKeyMasked: string;
  apiKeySet: boolean;
  configured: boolean;
}

export interface ActiveSummary {
  id: string;
  label: string;
  transport: string;
  kind: string;
  isLocal: boolean;
  icon: string;
  baseUrl: string;
  model: string;
  apiKeySet: boolean;
  ready: boolean;
}

export interface LanguageOption {
  code: string;
  label: string;
}

export interface SettingsResponse {
  activeProvider: string;
  active: ActiveSummary;
  providers: ProviderEntry[];
  language: string;
  languageCatalog: LanguageOption[];
  debugMode: boolean;
  ollamaBackgroundPriority: boolean;
  settingsPath: string;
  activeProfile: string;
  system: Record<string, unknown>;
}

/**
 * Draft the modal mutates while open. We send back only what the server
 * needs: activeProvider + per-provider patches (only id+changed fields)
 * + language + debug. The masked api key sentinel ("…") is preserved
 * verbatim for any provider the user didn't edit, signalling "keep what
 * you already have on disk".
 */
export interface SaveDraft {
  activeProvider: string;
  providers: Record<string, { baseUrl: string; model: string; apiKey: string }>;
  language: string;
  debugMode: boolean;
  ollamaBackgroundPriority: boolean;
}

export interface SaveResponse {
  ok: boolean;
  reason?: string;
  restartRequired?: boolean;
  savedTo?: string;
  active?: ActiveSummary;
}
