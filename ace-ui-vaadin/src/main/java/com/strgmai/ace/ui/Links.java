package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.RouteParam;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Router-link factories (C-2) and run-list helpers, shared by all views. */
public final class Links {

    private Links() {
    }

    public static RouterLink runLink(final String runId) {
        return paramLink(runId, RunDetailView.class, "runId", runId);
    }

    public static RouterLink jobLink(final String jobId) {
        return paramLink(jobId, JobDetailView.class, "jobId", jobId);
    }

    /** A link showing the run id that opens the job detail — how the Jinja queue page links runs. */
    public static RouterLink runToJobLink(final String runId, final String jobId) {
        return paramLink(runId, JobDetailView.class, "jobId", jobId);
    }

    public static RouterLink experimentLink(final String experimentId) {
        // a null id would otherwise silently produce the route param value "null"
        Objects.requireNonNull(experimentId, "experimentId");
        return paramLink(experimentId, ExperimentDetailView.class, "experimentId", experimentId);
    }

    private static RouterLink paramLink(final String text, final Class<? extends Component> target,
            final String paramName, final String paramValue) {
        final var link = new RouterLink();
        link.add(text);
        link.setRoute(target, new RouteParameters(new RouteParam(paramName, paramValue)));
        return link;
    }

    /** Raw service URL for a run file, segment-encoded so names with %, spaces, or non-ASCII survive. */
    static String rawFileUrl(final String baseUrl, final String runId, final String path) {
        return baseUrl + "/api/runs/" + ServiceClient.encodeSegment(runId) + "/files/" + ServiceClient.encodePath(path);
    }

    /** Distinct, sorted, non-null values of a run attribute (used for picker suggestions). */
    public static List<String> distinctRuns(final List<Api.Run> runs, final Function<Api.Run, String> getter) {
        return runs.stream().map(getter).filter(Objects::nonNull).distinct().sorted().toList();
    }

    /** Past runs' own model choices for one specific provider, stripped of its "providerKey/"
     *  prefix - ProviderModelPicker.withPastRunHistory() wants bare model ids to merge into that
     *  ONE provider's own model list, not the full wire string every run's model column now
     *  carries (every run's own model is "providerKey/model" - see V8's backfill for the historical
     *  "omlx/" case). */
    public static List<String> distinctModelsForProvider(final List<Api.Run> runs, final String providerKey) {
        final String prefix = providerKey + "/";
        return runs.stream().map(Api.Run::model).filter(m -> m != null && m.startsWith(prefix))
                .map(m -> m.substring(prefix.length())).distinct().sorted().toList();
    }
}
