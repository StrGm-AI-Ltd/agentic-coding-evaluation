package com.agentbench.ui;

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

    public static RouterLink runLink(String runId) {
        return paramLink(runId, RunDetailView.class, "runId", runId);
    }

    public static RouterLink jobLink(String jobId) {
        return paramLink(jobId, JobDetailView.class, "jobId", jobId);
    }

    /** A link showing the run id that opens the job detail — how the Jinja queue page links runs. */
    public static RouterLink runToJobLink(String runId, long jobId) {
        return paramLink(runId, JobDetailView.class, "jobId", String.valueOf(jobId));
    }

    public static RouterLink experimentLink(Long experimentId) {
        return paramLink(String.valueOf(experimentId), ExperimentDetailView.class,
                "experimentId", String.valueOf(experimentId));
    }

    private static RouterLink paramLink(String text, Class<? extends Component> target,
            String paramName, String paramValue) {
        RouterLink link = new RouterLink();
        link.add(text);
        link.setRoute(target, new RouteParameters(new RouteParam(paramName, paramValue)));
        return link;
    }

    /** Distinct, sorted, non-null values of a run attribute (used for picker suggestions). */
    public static List<String> distinctRuns(List<Api.Run> runs, Function<Api.Run, String> getter) {
        return runs.stream().map(getter).filter(Objects::nonNull).distinct().sorted().toList();
    }
}
