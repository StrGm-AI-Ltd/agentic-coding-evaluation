package com.strgmai.ace.service.agent;

import java.util.Locale;
import java.util.Map;

/** #328: hardcoded $/million-token input+output rates for models ACE's ReferenceAgent can call
 *  externally (a self-review or trajectory-review session against a real, paid API) - the schema
 *  PiAgent.java already anticipated (its own "cost" object, permanently zeroed) but never wired to
 *  real numbers, because PiAgent itself never runs against a paid endpoint (see #328's own writeup).
 *
 *  Prices are current as of 2026-10-15 (gpt-4o/Nemotron verified directly against each provider's
 *  own public pricing page that day; the rest from the best publicly available pricing search at
 *  the time) - there is no live pricing API to pull from instead, so this table WILL drift and
 *  needs updating by hand when a provider changes rates. An unknown model reports no cost rather
 *  than guessing one. */
public final class ModelPricing {
    private ModelPricing() {}

    private record Rate(double inputPerM, double outputPerM) {}

    // $ per 1,000,000 tokens, input/output. Keys are the bare model id as ACE's own reviewer string
    // carries it (the part after "provider/" - see Reviews.runReviewerSession()'s own "model"
    // extraction), lowercased for lookup.
    private static final Map<String, Rate> RATES = Map.ofEntries(
            // OpenAI (openai.com/api/pricing, 2026-10-15)
            Map.entry("gpt-4o", new Rate(2.50, 10.00)),
            Map.entry("gpt-4o-mini", new Rate(0.15, 0.60)),
            // Anthropic (anthropic.com/pricing, 2026-10-15) - keyed by this session's own known
            // model ids (claude-opus-5/claude-sonnet-5/claude-haiku-4-5-20251001/claude-fable-5-1)
            Map.entry("claude-fable-5-1", new Rate(10.00, 50.00)),
            Map.entry("claude-opus-5", new Rate(4.00, 20.00)),
            Map.entry("claude-sonnet-5", new Rate(2.00, 10.00)),
            Map.entry("claude-haiku-4-5-20251001", new Rate(1.00, 5.00)),
            // Google Gemini (ai.google.dev/pricing, 2026-10-15)
            Map.entry("gemini-2.5-flash-lite", new Rate(0.10, 0.40)),
            Map.entry("gemini-3.1-pro", new Rate(2.00, 12.00)),
            // Nebius Token Factory (tokenfactory.nebius.com, 2026-10-15) - the NVIDIA Nemotron
            // family ACE's hackathon-reviewer positioning actually targets
            Map.entry("nvidia/nemotron-3.5-lightning-30b-a3b", new Rate(0.06, 0.24)),
            Map.entry("nvidia/nemotron-3-super-120b-a12b", new Rate(0.30, 0.90)),
            Map.entry("nvidia/nemotron-3-ultra-550b-a55b", new Rate(1.00, 3.00)),
            // Nebius Token Factory (nebius.com/services/token-factory/models/glm-models-inference,
            // verified 2026-10-25) - not part of the hackathon-reviewer positioning above, added for
            // cost-estimation against a run already recorded under a different (local) model
            Map.entry("zai-org/glm-5.3-flash", new Rate(0.15, 0.50)));

    /** null when the model has no known rate (don't guess) or usage carries no token counts. */
    public static Double costUsd(final String model, final Map<String, Long> usage) {
        if (model == null || usage == null) return null;
        final Rate r = RATES.get(model.toLowerCase(Locale.ROOT));
        if (r == null) return null;
        final long input = usage.getOrDefault("input", 0L), output = usage.getOrDefault("output", 0L);
        return Math.round((input / 1_000_000.0 * r.inputPerM() + output / 1_000_000.0 * r.outputPerM()) * 1_000_000) / 1_000_000.0;
    }
}
