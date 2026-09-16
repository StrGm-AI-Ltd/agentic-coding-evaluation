package com.agentbench.ui;

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

    public static String toHtml(String markdown) {
        return RENDERER.render(PARSER.parse(markdown == null ? "" : markdown));
    }

    /** Adds target/rel to link attributes through the library's official hook — and
     *  enforces the http(s)-only allowlist for resolved URLs, because commonmark's
     *  sanitizeUrls still lets data: and other non-js schemes through. */
    static final class LinkTargetProvider implements AttributeProvider {

        static final class Factory implements AttributeProviderFactory {
            @Override
            public AttributeProvider create(AttributeProviderContext context) {
                return new LinkTargetProvider();
            }
        }

        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (node instanceof Link) {
                attributes.put("target", "_blank");
                attributes.put("rel", "noopener noreferrer");
                allowHttpOnly(attributes, "href");
            } else if (node instanceof Image) {
                allowHttpOnly(attributes, "src");
            }
        }

        private static void allowHttpOnly(Map<String, String> attributes, String key) {
            String value = attributes.get(key);
            if (value != null) {
                String scheme = value.toLowerCase(java.util.Locale.ROOT);
                if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
                    attributes.put(key, "#");
                }
            }
        }
    }
}
