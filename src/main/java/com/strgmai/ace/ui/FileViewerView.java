package com.strgmai.ace.ui;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone file viewer (no app layout) opened by the file browser's "view" links
 * in a new tab: fetches the run file through the service and renders it full-page —
 * JSON pretty-printed, everything else verbatim.
 */
@Route(value = "file-view")
@CssImport("./styles/md-viewer.css")
public class FileViewerView extends VerticalLayout implements BeforeEnterObserver {

    private final ServiceClient client;

    public FileViewerView(ServiceClient client) {
        this.client = client;
        setPadding(true);
        setSizeFull();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Location location = event.getLocation();
        String runId = queryParam(location, "run");
        String path = queryParam(location, "path");
        render(runId, path);
    }

    private void render(String runId, String path) {
        removeAll();
        if (runId == null || runId.isBlank() || path == null || path.isBlank()) {
            add(new com.vaadin.flow.component.html.H2("File viewer"), Panels.error("URL needs ?run=<run id>&path=<file>"));
            return;
        }
        try {
            String content = client.runFileText(runId, path);
            add(new FileViewerContent(runId, path, content));
        } catch (Exception e) {
            add(new com.vaadin.flow.component.html.H2(runId + " — " + path));
            add(Panels.error(client.errorText(e)));
        }
    }

    /** The content block: header with a raw link back to the service, then the (formatted) file. */
    private final class FileViewerContent extends VerticalLayout {
        FileViewerContent(String runId, String path, String content) {
            setPadding(false);
            setSpacing(false);
            setSizeFull();

            com.vaadin.flow.component.html.H2 title = new com.vaadin.flow.component.html.H2(runId + " — " + path);
            title.getStyle().set("margin", "0 0 4px 0").set("font-size", "1.2em");
            add(title);

            Anchor raw = new Anchor(Links.rawFileUrl(client.baseUrl(), runId, path),
                    "raw (unformatted) at the service");
            raw.getElement().setAttribute("target", "_blank");
            raw.getElement().setAttribute("rel", "noopener noreferrer");
            raw.getStyle().set("font-size", "0.85em");
            add(raw);

            Span spacer = new Span();
            spacer.getStyle().set("flex", "0 0 8px");
            add(spacer);

            Div contentDiv;
            if (isMarkdown(path)) {
                contentDiv = new Div();
                contentDiv.add(new com.vaadin.flow.component.Html(
                        "<div class=\"md-body\">" + Markdown.toHtml(content) + "</div>"));
            } else {
                contentDiv = Panels.mono(format(content));
            }
            contentDiv.getStyle().set("flex-grow", "1").set("overflow", "auto");
            add(contentDiv);
            expand(contentDiv);
        }
    }

    /** Markdown files get the rendered treatment; everything else the mono block. */
    static boolean isMarkdown(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".md");
    }

    /**
     * JSON files render pretty-printed; JSONL files render one pretty record per line
     * (all-or-nothing — one non-JSON line keeps the whole file verbatim); everything
     * else renders verbatim.
     */
    static String format(String content) {
        JsonNode whole = readOrNull(content);
        if (whole != null) {
            return pretty(whole);
        }
        List<JsonNode> records = new ArrayList<>();
        for (String line : content.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode record = readOrNull(line);
            if (record == null) {
                return content; // a non-blank line is not JSON: not a JSONL file
            }
            records.add(record);
        }
        if (records.isEmpty()) {
            return content;
        }
        StringBuilder formatted = new StringBuilder();
        for (JsonNode record : records) {
            if (formatted.length() > 0) {
                formatted.append('\n');
            }
            formatted.append(pretty(record));
        }
        return formatted.toString();
    }

    private static JsonNode readOrNull(String text) {
        try {
            JsonNode node = Json.MAPPER.readTree(text);
            return node == null || node.isMissingNode() ? null : node; // Jackson 3: empty input yields no node
        } catch (Exception e) {
            return null;
        }
    }

    private static String pretty(JsonNode node) {
        return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /** First value of a query parameter, null when absent. */
    static String queryParam(Location location, String name) {
        return location.getQueryParameters().getSingleParameter(name).orElse(null);
    }
}
