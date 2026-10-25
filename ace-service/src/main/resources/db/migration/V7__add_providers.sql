-- The "LLM Providers" registry, replacing the hardcoded ProviderConfig enum: an operator-editable
-- list of external model providers (name, the stable "provider/model" prefix other config strings
-- use, base URL, API key, and an optional default model so a bare provider key alone - e.g. just
-- "nebius" - resolves to something runnable without typing the full provider/model string every
-- time). api_key stores the real secret value directly now, not an env-var name: this is a
-- single-operator local tool (same risk class as today's .env/shell-profile convention), not a
-- shared multi-tenant service needing key-vault machinery.
CREATE TABLE providers (
    id             UUID PRIMARY KEY,   -- assigned in Java (SQLite has no UUID generator)
    name           TEXT NOT NULL,
    provider_key   TEXT NOT NULL UNIQUE,
    base_url       TEXT NOT NULL,
    api_key        TEXT,
    default_model  TEXT,
    created_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seeded with every provider ProviderConfig already knew about - provider_key matches its own
-- providerName exactly, so any "provider/model" string an operator already typed keeps resolving
-- the same way. Fixed literal UUIDs (not random) so this migration is reproducible. api_key starts
-- NULL for all five; ProviderKeySeeder backfills it from each provider's own legacy env var on
-- first boot, once, only while the column is still blank.
INSERT INTO providers (id, name, provider_key, base_url, default_model) VALUES
    ('a1a1a1a1-0001-4000-8000-000000000001', 'OpenAI',     'openai',     'https://api.openai.com/v1', NULL),
    ('a1a1a1a1-0001-4000-8000-000000000002', 'OpenRouter', 'openrouter', 'https://openrouter.ai/api/v1', NULL),
    ('a1a1a1a1-0001-4000-8000-000000000003', 'Anthropic',  'anthropic',  'https://api.anthropic.com/v1', NULL),
    ('a1a1a1a1-0001-4000-8000-000000000004', 'Gemini',     'gemini',     'https://generativelanguage.googleapis.com/v1beta/openai', NULL),
    ('a1a1a1a1-0001-4000-8000-000000000005', 'Nebius',     'nebius',     'https://api.tokenfactory.us-central1.nebius.com/v1', 'zai-org/GLM-5.3-Flash');
