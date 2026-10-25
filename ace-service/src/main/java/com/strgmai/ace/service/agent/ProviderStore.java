package com.strgmai.ace.service.agent;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.strgmai.ace.service.jooq.Tables.PROVIDERS;

/** The "LLM Providers" registry — replaces the old hardcoded `ProviderConfig` enum with a real,
 *  operator-editable table (see migration V7). `byBaseUrl()`/`byKey()`/`allCredentials()` are the
 *  runtime-resolution half (what `ReferenceAgent.apiKeyFor()`, `Reviews`/`Judge`/`TaskMatcher`'s own
 *  external-session plumbing need); `create()`/`update()`/`delete()`/`list()` back the LLM Providers
 *  UI tab's CRUD. */
@Component
public class ProviderStore {
    private final DSLContext dsl;

    public ProviderStore(final DSLContext dsl) { this.dsl = dsl; }

    public record Provider(UUID id, String name, String providerKey, String baseUrl, String apiKey, String defaultModel) {}

    private static Provider from(final org.jooq.Record r) {
        return new Provider(r.get(PROVIDERS.ID), r.get(PROVIDERS.NAME), r.get(PROVIDERS.PROVIDER_KEY),
                r.get(PROVIDERS.BASE_URL), r.get(PROVIDERS.API_KEY), r.get(PROVIDERS.DEFAULT_MODEL));
    }

    public List<Provider> list() {
        return dsl.selectFrom(PROVIDERS).orderBy(PROVIDERS.NAME).fetch().map(ProviderStore::from);
    }

    public Provider byKey(final String providerKey) {
        final var r = dsl.selectFrom(PROVIDERS).where(PROVIDERS.PROVIDER_KEY.eq(providerKey)).fetchOne();
        return r == null ? null : from(r);
    }

    /** Matches a base URL variant (trailing slash, versioned path) the same tolerant way every
     *  provider gets, by comparing just the HOST portion - not used across genuinely different
     *  regional endpoints for the same provider the way the old hardcoded enum's own short
     *  "hostToken" strings were, because a provider's base_url is operator-editable now: if an
     *  operator's own endpoint moves to a different region, they edit this row's base_url directly
     *  rather than needing a tolerant match to paper over a stale hardcoded one. */
    public Provider byBaseUrl(final String url) {
        if (url == null) return null;
        final String host = hostOf(url);
        if (host == null) return null;
        for (final Provider p : list()) {
            final String knownHost = hostOf(p.baseUrl());
            if (knownHost != null && (host.equals(knownHost) || url.contains(knownHost))) return p;
        }
        return null;
    }

    private static String hostOf(final String url) {
        try { return URI.create(url).getHost(); } catch (final Exception e) { return null; }
    }

    /** providerKey -> apiKey, only for providers with a real (non-blank) key configured - the
     *  credential set an external session's own extraEnv map needs. */
    public Map<String, String> allCredentials() {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Provider p : list())
            if (p.apiKey() != null && !p.apiKey().isBlank()) out.put(p.providerKey(), p.apiKey());
        return out;
    }

    /** blank -> localFallback; already "provider/model"-shaped -> returned as-is; a bare known
     *  provider key alone -> that provider's own default model (see V7's seed data - e.g. "nebius"
     *  resolves to "nebius/zai-org/GLM-5.3-Flash"); a bare key with no default model configured, or
     *  an unrecognised bare key, is a clear operator error, not a silent fallback. */
    public String resolveModel(final String raw, final String localFallback) {
        if (raw == null || raw.isBlank()) return localFallback;
        if (raw.contains("/")) return raw;
        final Provider p = byKey(raw);
        if (p == null) throw new IllegalArgumentException("unknown provider \"" + raw + "\" - add it under LLM Providers first, or specify provider/model directly");
        if (p.defaultModel() == null || p.defaultModel().isBlank())
            throw new IllegalArgumentException("provider \"" + raw + "\" has no default model configured - set one under LLM Providers, or specify " + raw + "/<model> directly");
        return p.providerKey() + "/" + p.defaultModel();
    }

    /** The main coding model's own routing target: baseUrl/apiKey to relay through, or null for local. */
    public record Upstream(String baseUrl, String apiKey) {}

    /** model: the bare, upstream-ready model name (prefix stripped when external). upstream: null means local. */
    public record Routed(String model, Upstream upstream) {}

    /** The MAIN model field's own convention - distinct from resolveModel() above (which exists for
     *  the reviewer/judge "blank defaults to local, bare key is shorthand" case): a bare string (no
     *  "/") is always a local model id, unchanged. A "prefix/rest" string whose prefix matches a
     *  REGISTERED provider's own provider_key routes to that provider (model = rest); any other
     *  "/"-containing string (an unknown prefix, or a local model id that just happens to contain a
     *  slash, e.g. an org/Model-style name) stays local, unchanged - tolerant by construction, never
     *  throws, matching the model ComboBox's own long-standing allowCustomValue=true freedom. */
    public Routed route(final String raw) {
        final int slash = raw == null ? -1 : raw.indexOf('/');
        if (slash < 0) return new Routed(raw, null);
        final Provider p = byKey(raw.substring(0, slash));
        return p == null ? new Routed(raw, null) : new Routed(raw.substring(slash + 1), new Upstream(bareBaseUrl(p.baseUrl()), p.apiKey()));
    }

    /** RecordingProxy.forward() relays to upstreamBaseUrl() + path, where path (as seen from the
     *  agent's own request against this proxy) already starts with "/v1" - the same reason
     *  props.upstreamBase() is itself bare, with no "/v1" suffix, today. A provider's own stored
     *  base_url DOES include "/v1" (see V7's seed data - it's handed as-is to the openai-java SDK
     *  elsewhere, e.g. providerModels(), which has no such path-already-has-it quirk) - strip it
     *  here so the proxy relay doesn't end up with ".../v1/v1/chat/completions". */
    private static String bareBaseUrl(final String baseUrl) {
        return baseUrl.endsWith("/v1") ? baseUrl.substring(0, baseUrl.length() - "/v1".length()) : baseUrl;
    }

    public Provider create(final String name, final String providerKey, final String baseUrl, final String apiKey, final String defaultModel) {
        final var rec = dsl.insertInto(PROVIDERS)
                .set(PROVIDERS.ID, UUID.randomUUID())
                .set(PROVIDERS.NAME, name)
                .set(PROVIDERS.PROVIDER_KEY, providerKey)
                .set(PROVIDERS.BASE_URL, baseUrl)
                .set(PROVIDERS.API_KEY, blankToNull(apiKey))
                .set(PROVIDERS.DEFAULT_MODEL, blankToNull(defaultModel))
                .returning()
                .fetchOne();
        return from(rec);
    }

    /** `apiKey == null` leaves the stored key unchanged (the UI never round-trips a secret back,
     *  so "the operator didn't type a new one" and "clear it" must be distinguishable); an explicit
     *  blank string clears it. Returns the row as it now stands, so a caller never needs a separate
     *  re-fetch (particularly useful here since providerKey - the natural "look it up again" field -
     *  may itself be what just changed). */
    public Provider update(final UUID id, final String name, final String providerKey, final String baseUrl,
                            final String apiKey, final String defaultModel) {
        var step = dsl.update(PROVIDERS)
                .set(PROVIDERS.NAME, name)
                .set(PROVIDERS.PROVIDER_KEY, providerKey)
                .set(PROVIDERS.BASE_URL, baseUrl)
                .set(PROVIDERS.DEFAULT_MODEL, blankToNull(defaultModel))
                .set(PROVIDERS.UPDATED_AT, Instant.now().toString());
        if (apiKey != null) step = step.set(PROVIDERS.API_KEY, blankToNull(apiKey));
        final var rec = step.where(PROVIDERS.ID.eq(id)).returning().fetchOne();
        return from(rec);
    }

    public void delete(final UUID id) {
        dsl.deleteFrom(PROVIDERS).where(PROVIDERS.ID.eq(id)).execute();
    }

    private static String blankToNull(final String s) { return s == null || s.isBlank() ? null : s; }
}
