package com.strgmai.ace.ui;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Run file browser — the Vaadin twin of the Jinja2 run page's file section:
 * lists the run's results dir (top level + one subdir level, excluding workspace/,
 * mirroring run_files() in the service's api.py). Clicking a row opens the file in a new
 * tab: text files formatted at the file-view route, everything else raw from the service.
 *
 * #270: the listing itself goes through GET /api/runs/{id}/files (ServiceClient.runFiles()),
 * not a direct Files.list() against run.results_dir() - that path is ace-service's OWN,
 * recorded for whatever host it runs on; ace-ui-vaadin is explicitly documented (README.md) to
 * support running against a non-colocated ace-service, where a local filesystem read would
 * silently render "no result files" instead of actually listing anything.
 */
@CssImport("./styles/clickable-grid.css")
public class FilesBrowser extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(FilesBrowser.class);

    private static final Set<String> TEXT_SUFFIXES =
            Set.of(".md", ".log", ".json", ".jsonl", ".txt", ".yaml", ".yml");

    private final ServiceClient client;
    private final String runId;

    public FilesBrowser(final ServiceClient client, final String runId) {
        this.client = client;
        this.runId = runId;
        setPadding(false);
        setSpacing(false);

        add(Panels.sectionTitle("Files"));

        final List<Api.RunFile> files;
        try {
            files = client.runFiles(runId);
        } catch (final Exception e) {
            log.warn("could not list files for run {}: {}", runId, e.toString());
            add(new Div("no result files"));
            return;
        }
        if (files.isEmpty()) {
            add(new Div("no result files"));
            return;
        }

        final var grid = new Grid<>(Api.RunFile.class, false);
        grid.addColumn(Api.RunFile::path).setHeader("path").setFlexGrow(1);
        grid.addColumn(f -> sizeText(f.size())).setHeader("size")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(Comparator.comparingLong(f -> f.size() < 0 ? Long.MAX_VALUE : f.size()));
        grid.addClassName("clickable-rows");   // #39: the row itself opens the file, no separate link
        grid.addItemClickListener(e -> getUI().ifPresent(
                ui -> ui.getPage().open(fileUrl(client, runId, e.getItem().path()), "_blank")));
        grid.setItems(files);
        grid.setAllRowsVisible(true);
        grid.setMaxHeight("300px");
        add(grid);
    }

    /** New tab target for a row click: the formatted viewer for text files, the raw file otherwise. */
    static String fileUrl(final ServiceClient client, final String runId, final String name) {
        return isText(name) ? viewRoute(runId, name) : Links.rawFileUrl(client.baseUrl(), runId, name);
    }

    /** New-tab viewer URL; the path is segment-encoded so spaces and non-ASCII survive. */
    static String viewRoute(final String runId, final String path) {
        return "file-view?run=" + ServiceClient.encodeSegment(runId)
                + "&path=" + ServiceClient.encodePath(path);
    }

    static boolean isText(final String name) {
        final var dot = name.lastIndexOf('.');
        // extensions are case-sensitive in the set, but not on case-insensitive file systems
        return dot >= 0 && TEXT_SUFFIXES.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }

    /** #270: the size column's own text, from the size GET /api/runs/{id}/files already returned -
     *  no local stat call needed any more. -1 (BenchController.fileEntry()'s own marker for a
     *  failed server-side stat) renders the same "–" FilesBrowser always showed for that case. */
    static String sizeText(final long size) {
        return size < 0 ? "–" : size < 1024 ? size + " B"
                : size < 1024 * 1024 ? String.format("%.1f KiB", size / 1024.0)
                : String.format("%.1f MiB", size / 1024.0 / 1024.0);
    }
}
