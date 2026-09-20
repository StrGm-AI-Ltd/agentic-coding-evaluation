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

import java.util.Comparator;
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
    private final Span emptyState = new Span();

    public JobsView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        Button refresh = new Button(VaadinIcon.REFRESH.create(), e -> load());
        refresh.getElement().setAttribute("aria-label", "refresh the queue");
        Button newJob = new Button("New job", VaadinIcon.PLUS.create(),
                e -> getUI().ifPresent(ui -> ui.navigate("jobs/new")));
        running.getStyle().set("color", "var(--lumo-secondary-text-color)");
        error.getStyle().set("color", "var(--lumo-error-color)");
        emptyState.getStyle().set("color", "var(--lumo-secondary-text-color)");
        emptyState.setVisible(false);

        grid.addColumn(Api.Job::id).setHeader("#").setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(job -> Links.runToJobLink(job.run_id(), job.id())))
                .setHeader("run").setAutoWidth(true)
                .setSortable(true).setComparator(Fmt.nullsLast(Api.Job::run_id));
        grid.addColumn(Api.Job::kind).setHeader("kind").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::statusCell)).setHeader("status").setAutoWidth(true)
                .setSortable(true).setComparator(Fmt.nullsLast(Api.Job::status));
        grid.addColumn(Api.Job::priority).setHeader("priority").setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);
        grid.addColumn(job -> job.arm() == null ? "–"
                : job.arm() + " r" + (job.repeat() == null ? "?" : job.repeat()))
                .setHeader("arm").setAutoWidth(true);
        grid.addColumn(job -> Fmt.when(job.started_at())).setHeader("started").setAutoWidth(true)
                .setComparator(Fmt.comparingTime(Api.Job::started_at));
        grid.addColumn(new ComponentRenderer<>(this::actions)).setHeader("actions").setFlexGrow(1);

        add(new H2("Queue"), new HorizontalLayout(refresh, newJob, running, error), emptyState, grid);
        setSizeFull();
        expand(grid);

        addAttachListener(e -> load());
    }

    /** Status badge with the blocked reason inline (keyboard/touch discoverable, not tooltip-only). */
    private com.vaadin.flow.component.html.Div statusCell(Api.Job job) {
        com.vaadin.flow.component.html.Div cell = new com.vaadin.flow.component.html.Div();
        com.vaadin.flow.component.badge.Badge badge = Badges.status(job.status());
        if ("blocked".equals(job.status()) && job.blocked_reason() != null) {
            badge.getElement().setAttribute("title", job.blocked_reason());
            Span reason = new Span(job.blocked_reason());
            reason.getStyle().set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "0.75em").set("white-space", "normal");
            cell.add(badge, reason);
            return cell;
        }
        cell.add(badge);
        return cell;
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
        // priority only reorders the queue: meaningless for running/terminal jobs
        if (!JobStatuses.isTerminal(job.status()) && !"running".equals(job.status())) {
            Button raise = new Button(VaadinIcon.ARROW_UP.create(),
                    e -> act(() -> client.setPriority(job.id(), job.priority() == null ? 1 : job.priority() + 1), job));
            raise.getElement().setAttribute("aria-label", "raise priority");
            layout.add(raise);
        }
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
            emptyState.setVisible(false);
            return;
        }
        grid.setItems(jobs);
        emptyState.setText("The queue is empty — queue a run with “New job” or an experiment.");
        emptyState.setVisible(jobs.isEmpty());
        long runningNow = jobs.stream().filter(j -> "running".equals(j.status())).count();
        long blocked = jobs.stream().filter(j -> "blocked".equals(j.status())).count();
        running.setText(jobs.size() + " jobs · " + runningNow + " running"
                + (blocked > 0 ? " · " + blocked + " blocked" : ""));
    }
}
