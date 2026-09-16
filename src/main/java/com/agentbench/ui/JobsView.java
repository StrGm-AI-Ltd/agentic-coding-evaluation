package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;

import java.util.List;
import java.util.function.Supplier;

/** Queue view — the UI twin of GET /api/jobs with cancel / requeue / priority actions
 *  (blocked jobs are actionable, like in the service UI). */
@Route(value = "jobs", layout = MainLayout.class)
public class JobsView extends VerticalLayout {

    private final ServiceClient client;

    private final Grid<Api.Job> grid = new Grid<>(Api.Job.class, false);
    private final Span error = new Span();
    private final Span running = new Span();

    public JobsView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        Button refresh = new Button(VaadinIcon.REFRESH.create(), e -> load());
        Button newJob = new Button("New job", VaadinIcon.PLUS.create(),
                e -> getUI().ifPresent(ui -> ui.navigate("jobs/new")));
        running.getStyle().set("color", "var(--lumo-secondary-text-color)");
        error.getStyle().set("color", "var(--lumo-error-color)");

        grid.addColumn(Api.Job::id).setHeader("#").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(job -> Links.runLink(job.run_id())))
                .setHeader("run").setAutoWidth(true);
        grid.addColumn(Api.Job::kind).setHeader("kind").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::statusCell)).setHeader("status").setAutoWidth(true);
        grid.addColumn(Api.Job::priority).setHeader("priority").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(job -> job.arm() == null ? "–"
                : job.arm() + " r" + (job.repeat() == null ? "?" : job.repeat()))
                .setHeader("arm").setAutoWidth(true);
        grid.addColumn(job -> Fmt.when(job.started_at())).setHeader("started").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::actions)).setHeader("actions").setFlexGrow(1);

        add(new H2("Queue"), new HorizontalLayout(refresh, newJob, running, error), grid);
        setSizeFull();
        expand(grid);

        addAttachListener(e -> load());
    }

    /** Status badge with the blocked reason surfaced as a tooltip (V-1). */
    private com.vaadin.flow.component.badge.Badge statusCell(Api.Job job) {
        com.vaadin.flow.component.badge.Badge badge = Badges.status(job.status());
        if ("blocked".equals(job.status()) && job.blocked_reason() != null) {
            badge.getElement().setAttribute("title", job.blocked_reason());
        }
        return badge;
    }

    private HorizontalLayout actions(Api.Job job) {
        HorizontalLayout layout = new HorizontalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        if (JobStatuses.canCancel(job.status(), job.cancel_requested())) {
            layout.add(new Button("Cancel", e -> act(() -> client.cancel(job.id()), job)));
        }
        if (JobStatuses.canRequeue(job.status())) {
            layout.add(new Button("Requeue", e -> act(() -> client.requeue(job.id()), job)));
        }
        layout.add(new Button(VaadinIcon.ARROW_UP.create(),
                e -> act(() -> client.setPriority(job.id(), job.priority() == null ? 1 : job.priority() + 1), job)));
        return layout;
    }

    private void act(Supplier<Api.Job> action, Api.Job job) {
        try {
            Api.Job updated = action.get();
            Notification.show("job #" + job.id() + " → " + updated.status(),
                    3000, Notification.Position.BOTTOM_END);
            load();
        } catch (Exception e) {
            Notification.show(client.errorText(e), 6000, Notification.Position.BOTTOM_END);
        }
    }

    private void load() {
        List<Api.Job> jobs;
        try {
            jobs = client.jobs();
            error.setText("");
        } catch (Exception e) {
            grid.setItems(List.of());
            error.setText(client.errorText(e));
            return;
        }
        grid.setItems(jobs);
        long runningNow = jobs.stream().filter(j -> "running".equals(j.status())).count();
        long blocked = jobs.stream().filter(j -> "blocked".equals(j.status())).count();
        running.setText(jobs.size() + " jobs · " + runningNow + " running"
                + (blocked > 0 ? " · " + blocked + " blocked" : ""));
    }
}
