package com.agentbench.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Run file browser — the Vaadin twin of the Jinja2 run page's file section:
 * lists the run's results dir (top level + one subdir level, excluding workspace/,
 * mirroring run_files() in the service's api.py). Text files open formatted in
 * a new tab at the file-view route; binary files link out to the service.
 */
public class FilesBrowser extends VerticalLayout {

    private static final Set<String> TEXT_SUFFIXES =
            Set.of(".md", ".log", ".json", ".jsonl", ".txt", ".yaml", ".yml");

    private final ServiceClient client;
    private final String runId;

    public FilesBrowser(ServiceClient client, String runId, String resultsDir) {
        this.client = client;
        this.runId = runId;
        setPadding(false);
        setSpacing(false);

        H4 title = new H4("Files");
        title.getStyle().set("margin", "16px 0 4px 0");
        add(title);

        List<String> files = listFiles(resultsDir);
        if (files.isEmpty()) {
            add(new Div("no result files"));
            return;
        }

        Grid<String> grid = new Grid<>(String.class, false);
        grid.addColumn(name -> name).setHeader("path").setFlexGrow(1);
        grid.addColumn(name -> sizeOf(resultsDir, name)).setHeader("size")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addComponentColumn(this::fileLink).setFlexGrow(0);
        grid.setItems(files);
        grid.setAllRowsVisible(true);
        grid.setMaxHeight("300px");
        add(grid);
    }

    private Component fileLink(String name) {
        Anchor anchor = isText(name)
                ? new Anchor(viewRoute(runId, name), "view")   // opens the formatted viewer
                : new Anchor(client.baseUrl() + "/runs/" + runId + "/files/" + name, "open");
        anchor.getElement().setAttribute("target", "_blank");
        anchor.getElement().setAttribute("rel", "noopener");
        return anchor;
    }

    /** New-tab viewer URL; the path is segment-encoded so spaces and non-ASCII survive. */
    static String viewRoute(String runId, String path) {
        return "file-view?run=" + ServiceClient.encodeSegment(runId)
                + "&path=" + ServiceClient.encodePath(path);
    }

    static boolean isText(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_SUFFIXES.contains(name.substring(dot));
    }

    private static String sizeOf(String resultsDir, String name) {
        try {
            long size = Files.size(Path.of(resultsDir, name.split("/")));
            return size < 1024 ? size + " B"
                    : size < 1024 * 1024 ? String.format("%.1f KiB", size / 1024.0)
                    : String.format("%.1f MiB", size / 1024.0 / 1024.0);
        } catch (IOException e) {
            return "–";
        }
    }

    /** Mirrors run_files() in the service's api.py: top-level files plus one level of subdirs. */
    static List<String> listFiles(String resultsDir) {
        if (resultsDir == null || resultsDir.isBlank()) {
            return List.of();
        }
        Path base = Path.of(resultsDir);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> top = Files.list(base)) {
            for (Path path : top.sorted().toList()) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path)) {
                    files.add(name);
                } else if (Files.isDirectory(path) && !"workspace".equals(name)) {
                    try (Stream<Path> inner = Files.list(path)) {
                        inner.sorted()
                                .filter(Files::isRegularFile)
                                .forEach(child -> files.add(name + "/" + child.getFileName()));
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return files;
    }
}
