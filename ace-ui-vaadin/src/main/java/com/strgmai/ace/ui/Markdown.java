package com.strgmai.ace.ui;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.AttributeProviderContext;
import org.commonmark.renderer.html.AttributeProviderFactory;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.List;
import java.util.Map;

/**
 * Markdown → HTML via commonmark-java, the standard library path — no custom
 * node rendering. Safety is the library's own configuration: raw HTML escaped
 * (run files are agent-authored — no active content), unsafe URLs sanitized,
 * and http(s) links open in a new tab so the viewer page is not navigated away.
 * GFM tables are registered on BOTH the parser and the renderer.
 *
 * #487, found live: the sanitizer used to require an EXPLICIT http(s):// prefix, which silently
 * neutered every schemeless relative reference to bare "#" too - including TabManual's own
 * cross-page wiki links (rewritten to real absolute URLs upstream, so unaffected by THIS fix, but
 * genuinely broken before it existed) and any same-page "#anchor" link. A relative reference can
 * never execute script via navigation - only an explicit dangerous scheme (javascript:, data:,
 * vbscript:, ...) can - so the real security boundary is "has an explicit scheme that isn't
 * http(s)", not "lacks an explicit http(s) scheme". Verified this reasoning holds for BOTH
 * callers sharing this class: FileViewerView's agent-authored content (an agent could still only
 * ever produce a same-origin relative link or image, not an executable one) and TabManual's
 * operator-curated wiki content.
 */
public final class Markdown {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .attributeProviderFactory(new LinkTargetProvider.Factory())
            .build();

    private Markdown() {
    }

    public static String toHtml(final String markdown) {
        return RENDERER.render(PARSER.parse(markdown == null ? "" : markdown));
    }

    /** Adds target/rel to link attributes through the library's official hook — and blocks any
     *  resolved URL with an explicit non-http(s) scheme, because commonmark's sanitizeUrls still
     *  lets data: and other non-js schemes through. A schemeless value (a relative path, a bare
     *  page name, a "#anchor") is left untouched - see class javadoc, #487. */
    static final class LinkTargetProvider implements AttributeProvider {

        static final class Factory implements AttributeProviderFactory {
            @Override
            public AttributeProvider create(final AttributeProviderContext context) {
                return new LinkTargetProvider();
            }
        }

        @Override
        public void setAttributes(final Node node, final String tagName, final Map<String, String> attributes) {
            if (node instanceof Link) {
                attributes.put("target", "_blank");
                attributes.put("rel", "noopener noreferrer");
                blockDangerousSchemes(attributes, "href");
            } else if (node instanceof Image) {
                blockDangerousSchemes(attributes, "src");
            }
        }

        private static void blockDangerousSchemes(final Map<String, String> attributes, final String key) {
            final var value = attributes.get(key);
            if (value == null) {
                return;
            }
            final String scheme;
            try {
                scheme = new java.net.URI(value).getScheme();
            } catch (final java.net.URISyntaxException e) {
                attributes.put(key, "#");
                return;
            }
            if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                attributes.put(key, "#");
            }
        }
    }
}
