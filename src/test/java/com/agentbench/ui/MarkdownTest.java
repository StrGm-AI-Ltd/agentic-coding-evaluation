package com.agentbench.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file viewer's Markdown rendering — commonmark-java's own renderer
 * (GFM tables, escapeHtml, sanitizeUrls, link targets via AttributeProvider).
 */
class MarkdownTest {

    @Test
    void headingsAndInlineMarkup() {
        String html = Markdown.toHtml("# Title\n\nSome **bold** and *em* and `code`.\n");
        assertTrue(html.contains("<h1>Title</h1>"), html);
        assertTrue(html.contains("<strong>bold</strong>"));
        assertTrue(html.contains("<em>em</em>"));
        assertTrue(html.contains("<code>code</code>"));
    }

    @Test
    void gfmTableRendersLikeTheRealPlanFiles() {
        String html = Markdown.toHtml("""
                | Wave | Tasks |
                |------|-------|
                | 1 | **T1** — skeleton |
                | 2 | **T2** (orders) |
                """);
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<th"), "header cells become th");
        assertTrue(html.contains("<td"), "body cells become td");
        assertTrue(html.contains("<strong>T1</strong>"), "inline markup survives inside cells");
        assertTrue(html.contains("skeleton"));
    }

    @Test
    void listsOrderedAndNested() {
        String html = Markdown.toHtml("- one\n- two\n\n3. third\n4. fourth\n");
        assertTrue(html.contains("<ul>\n<li>one</li>"), html);
        assertTrue(html.contains("<ol start=\"3\">"), "the ordered list keeps its start number");
        assertTrue(html.contains("<li>third</li>"));
    }

    @Test
    void fencedCodeRendersEscaped() {
        String html = Markdown.toHtml("```\n<b>&amp;</b>\n```\n");
        assertTrue(html.contains("<pre><code>"), html);
        assertTrue(html.contains("&lt;b&gt;&amp;amp;&lt;/b&gt;"), "code content is escaped");
        assertFalse(html.contains("<b>&amp;"), "no raw tags leak from the fence");
    }

    /** Run files are authored by the agent under test — raw HTML renders as text, never executes. */
    @Test
    void rawHtmlIsEscapedNotPassedThrough() {
        String html = Markdown.toHtml("before\n\n<script>alert(1)</script>\n\nx <img src=x onerror=steal> y\n");
        assertFalse(html.contains("<script>"), "the script tag is escaped");
        assertTrue(html.contains("&lt;script&gt;"), "its source stays visible as text");
        assertFalse(html.contains("<img src=x"), "the onerror img is escaped too");
        assertFalse(html.contains("src=x onerror=steal\""), "no live attributes");
        assertTrue(html.contains("before"));
        assertTrue(html.contains("y"));
    }

    @Test
    void unsafeLinkProtocolsAreSanitized() {
        String html = Markdown.toHtml("[site](https://example.com/x?a=1) and [bad](javascript:alert(1))\n");
        assertTrue(html.contains("href=\"https://example.com/x?a=1\""), "http(s) links survive");
        assertFalse(html.contains("javascript:"), "unsafe protocols are sanitized away");
        assertTrue(html.contains(">bad</a>"), "the link label stays clickable text");
    }

    @Test
    void linksOpenInANewTab() {
        String html = Markdown.toHtml("[site](https://example.com)\n");
        assertTrue(html.contains("target=\"_blank\""), html);
        assertTrue(html.contains("rel=\"noopener noreferrer\""));
    }

    @Test
    void blockquoteAndThematicBreak() {
        String html = Markdown.toHtml("> quoted words\n\n---\n");
        assertTrue(html.contains("<blockquote>"), html);
        assertTrue(html.contains("quoted words"));
        assertTrue(html.contains("<hr />"));
    }
}
