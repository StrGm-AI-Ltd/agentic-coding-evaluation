package com.agentbench.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.Route;
import tools.jackson.databind.JsonNode;

/**
 * Standalone file viewer (no app layout) opened by the file browser's "view" links
 * in a new tab: fetches the run file through the service and renders it full-page —
 * JSON pretty-printed, everything else verbatim.
 */
@Route(value = "file-view")
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
            add(new H3("File viewer"), Panels.error("URL needs ?run=<run id>&path=<file>"));
            return;
        }
        try {
            String content = client.runFileText(runId, path);
            add(new FileViewerContent(runId, path, content));
        } catch (Exception e) {
            add(new H3(runId + " — " + path));
            add(Panels.error(client.errorText(e)));
        }
    }

    /** The content block: header with a raw link back to the service, then the (formatted) file. */
    private final class FileViewerContent extends VerticalLayout {
        FileViewerContent(String runId, String path, String content) {
            setPadding(false);
            setSpacing(false);
            setSizeFull();

            H3 title = new H3(runId + " — " + path);
            title.getStyle().set("margin", "0 0 4px 0").set("font-size", "1.2em");
            add(title);

            Anchor raw = new Anchor(client.baseUrl() + "/runs/" + runId + "/files/" + path,
                    "raw (unformatted) at the service");
            raw.getElement().setAttribute("target", "_blank");
            raw.getElement().setAttribute("rel", "noopener");
            raw.getStyle().set("font-size", "0.85em");
            add(raw);

            Span spacer = new Span();
            spacer.getStyle().set("flex", "0 0 8px");
            add(spacer);

            Div contentDiv = Panels.mono(format(content));
            contentDiv.getStyle().set("flex-grow", "1").set("overflow", "auto");
            add(contentDiv);
            expand(contentDiv);
        }
    }

    /** JSON files render pretty-printed; anything that does not parse renders verbatim. */
    static String format(String content) {
        try {
            JsonNode node = Json.MAPPER.readTree(content);
            if (node == null || node.isMissingNode()) { // Jackson 3: empty input yields no node
                return content;
            }
            return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return content;
        }
    }

    /** First value of a query parameter, null when absent. */
    static String queryParam(Location location, String name) {
        return location.getQueryParameters().getSingleParameter(name).orElse(null);
    }
}
