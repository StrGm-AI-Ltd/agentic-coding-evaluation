package com.strgmai.ace.ui;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Run file browser — the Vaadin twin of the Jinja2 run page's file section:
 * lists the run's results dir (top level + one subdir level, excluding workspace/,
 * mirroring run_files() in the service's api.py). Clicking a row opens the file in a new
 * tab: text files formatted at the file-view route, everything else raw from the service.
 */
@CssImport("./styles/clickable-grid.css")
public class FilesBrowser extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(FilesBrowser.class);

    private static final Set<String> TEXT_SUFFIXES =
            Set.of(".md", ".log", ".json", ".jsonl", ".txt", ".yaml", ".yml");

    private final ServiceClient client;
    private final String runId;

    public FilesBrowser(final ServiceClient client, final String runId, final String resultsDir) {
        this.client = client;
        this.runId = runId;
        setPadding(false);
        setSpacing(false);

        add(Panels.sectionTitle("Files"));

        final var files = listFiles(resultsDir);
        if (files.isEmpty()) {
            add(new Div("no result files"));
            return;
        }

        final var grid = new Grid<>(String.class, false);
        grid.addColumn(name -> name).setHeader("path").setFlexGrow(1);
        grid.addColumn(name -> sizeOf(resultsDir, name)).setHeader("size")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setComparator(Comparator.comparingLong(name -> sizeOr(resultsDir, name)));
        grid.addClassName("clickable-rows");   // #39: the row itself opens the file, no separate link
        grid.addItemClickListener(e -> getUI().ifPresent(
                ui -> ui.getPage().open(fileUrl(client, runId, e.getItem()), "_blank")));
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

    static String sizeOf(final String resultsDir, final String name) {
        try {
            final var size = sizeBytes(resultsDir, name);
            return size < 0 ? "–" : size < 1024 ? size + " B"
                    : size < 1024 * 1024 ? String.format("%.1f KiB", size / 1024.0)
                    : String.format("%.1f MiB", size / 1024.0 / 1024.0);
        } catch (final IOException e) {
            log.debug("could not stat {}/{}: {}", resultsDir, name, e.toString());
            return "–";
        }
    }

    private static long sizeBytes(final String resultsDir, final String name) throws IOException {
        return Files.size(Path.of(resultsDir, name.split("/")));
    }

    /** Sort key for the size column: actual bytes, missing files last. */
    static long sizeOr(final String resultsDir, final String name) {
        try {
            return sizeBytes(resultsDir, name);
        } catch (final IOException e) {
            log.debug("could not stat {}/{} for sorting: {}", resultsDir, name, e.toString());
            return Long.MAX_VALUE;
        }
    }

    /** Mirrors run_files() in the service's api.py: top-level files plus one level of subdirs. */
    static List<String> listFiles(final String resultsDir) {
        if (resultsDir == null || resultsDir.isBlank()) {
            return List.of();
        }
        final var base = Path.of(resultsDir);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        // returned as List<String>; empty-diamond under var would infer ArrayList<Object>
        final List<String> files = new ArrayList<>();
        try (var top = Files.list(base)) {
            for (final var path : top.sorted().toList()) {
                final var name = path.getFileName().toString();
                if (Files.isRegularFile(path)) {
                    files.add(name);
                } else if (Files.isDirectory(path) && !"workspace".equals(name)) {
                    try (var inner = Files.list(path)) {
                        inner.sorted()
                                .filter(Files::isRegularFile)
                                .forEach(child -> files.add(name + "/" + child.getFileName()));
                    }
                }
            }
        } catch (final IOException e) {
            // the directory itself was already confirmed to exist above, so this is a genuine
            // read failure (permissions, a TOCTOU removal) - the browser then shows "no files",
            // indistinguishable from a truly empty run, unless this is logged
            log.warn("could not list files under {}: {}", resultsDir, e.toString());
            return List.of();
        }
        return files;
    }
}
