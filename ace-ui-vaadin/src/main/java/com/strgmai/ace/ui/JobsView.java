package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Queue view — the UI twin of GET /api/jobs with cancel / requeue / priority actions
 *  (blocked jobs are actionable, like in the service UI). */
@Route(value = "jobs", layout = MainLayout.class)
public class JobsView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(JobsView.class);

    private final ServiceClient client;

    private final Grid<Api.Job> grid = new Grid<>(Api.Job.class, false);
    private final Span error = new Span();
    private final Span running = new Span();
    private final Span emptyState = new Span();

    public JobsView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        final var refresh = new Button(VaadinIcon.REFRESH.create(), e -> load());
        refresh.getElement().setAttribute("aria-label", "refresh the queue");
        final var newJob = new Button("New job", VaadinIcon.PLUS.create(),
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
        // #202: Requeue/raise-priority were unreachable below ~1700px - the grid scrolled
        // horizontally, but nothing ever brought this column, the only way to recover a blocked
        // job, back into view. Frozen to the end: always visible regardless of scroll position.
        // flexGrow(0) + autoWidth(true) instead of the old flexGrow(1): a frozen column must size
        // to its own content (confirmed live: 3 buttons need ~260px) rather than stretch/shrink -
        // flexGrow(1) left it squeezed to ~100px, clipping Requeue/raise-priority all over again,
        // just inside the now-frozen column instead of outside the viewport.
        grid.addColumn(new ComponentRenderer<>(this::actions)).setHeader("actions").setFlexGrow(0)
                .setAutoWidth(true).setFrozenToEnd(true);

        add(new H2("Queue"), new HorizontalLayout(refresh, newJob, running, error), emptyState, grid);
        setSizeFull();
        expand(grid);

        addAttachListener(e -> load());
    }

    /** Status badge with the blocked reason inline (keyboard/touch discoverable, not tooltip-only).
     *  #193: at realistic blocked-job volume the full reason text blew out the column and crowded
     *  the row - now a one-line, ellipsis-truncated button (not a plain Span: a Button stays
     *  keyboard-focusable and clickable, preserving the original "not tooltip-only" guarantee for
     *  the full text, which a hover-only title attribute does not) that opens a dialog with the
     *  untruncated reason on click. The truncation CSS lives on an inner Span, not the Button's own
     *  host element: vaadin-button's internal label is flex-laid-out, and text-overflow:ellipsis
     *  does not render its "…" glyph on a flex child - only on a plain block/inline-block box. */
    private com.vaadin.flow.component.html.Div statusCell(final Api.Job job) {
        final var cell = new com.vaadin.flow.component.html.Div();
        final var badge = Badges.status(job.status());
        if ("blocked".equals(job.status()) && job.blocked_reason() != null) {
            badge.getElement().setAttribute("title", job.blocked_reason());
            final var text = new Span(job.blocked_reason());
            text.getStyle().set("display", "block").set("max-width", "320px")
                    .set("overflow", "hidden").set("text-overflow", "ellipsis").set("white-space", "nowrap");
            final var reason = new Button(text, e -> openBlockedReasonDialog(job.blocked_reason()));
            reason.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            reason.getStyle().set("color", "var(--lumo-secondary-text-color)").set("max-width", "320px");
            cell.add(badge, reason);
            return cell;
        }
        cell.add(badge);
        return cell;
    }

    /** Full, unclipped blocked reason - same Dialog pattern as RunDetailView.openCheckDialog. */
    private void openBlockedReasonDialog(final String reason) {
        final var dialog = new Dialog();
        dialog.setHeaderTitle("blocked reason");
        dialog.setWidth("min(600px, 90vw)");
        dialog.add(Panels.mono(reason));
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));
        dialog.open();
    }

    private HorizontalLayout actions(final Api.Job job) {
        final var layout = new HorizontalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        if (JobStatuses.canCancel(job.status(), job.cancel_requested())) {
            layout.add(new Button("Cancel", e -> act(() -> client.cancel(job.id()), job)));
        }
        if (JobStatuses.canPause(job.status(), job.cancel_requested(), job.pause_requested())) {
            layout.add(new Button("Pause", e -> act(() -> client.pause(job.id()), job)));
        }
        if (JobStatuses.canRequeue(job.status())) {
            // "paused" reads as Resume - same requeue endpoint either way
            layout.add(new Button("paused".equals(job.status()) ? "Resume" : "Requeue", e -> act(() -> client.requeue(job.id()), job)));
        }
        // priority only reorders the queue: meaningless for running/terminal jobs
        if (!JobStatuses.isTerminal(job.status()) && !"running".equals(job.status())) {
            final var raise = new Button(VaadinIcon.ARROW_UP.create(),
                    e -> act(() -> client.setPriority(job.id(), job.priority() == null ? 1 : job.priority() + 1), job));
            raise.getElement().setAttribute("aria-label", "raise priority");
            layout.add(raise);
        }
        return layout;
    }

    private void act(final Supplier<Api.Job> action, final Api.Job job) {
        try {
            final var updated = action.get();
            Notification.show("job #" + job.id() + " → " + updated.status(),
                    3000, Notification.Position.BOTTOM_END);
            load();
        } catch (final Exception e) {
            log.warn("could not act on job {}: {}", job.id(), e.toString());
            Notification.show(client.errorText(e), 6000, Notification.Position.BOTTOM_END);
        }
    }

    private void load() {
        final List<Api.Job> jobs;   // assigned exactly once below; a legal blank final
        try {
            jobs = client.jobs();
            error.setText("");
        } catch (final Exception e) {
            log.warn("could not load jobs: {}", e.toString());
            grid.setItems(List.of());
            error.setText(client.errorText(e));
            emptyState.setVisible(false);
            return;
        }
        grid.setItems(jobs);
        emptyState.setText("The queue is empty — queue a run with “New job” or an experiment.");
        emptyState.setVisible(jobs.isEmpty());
        final var runningNow = jobs.stream().filter(j -> "running".equals(j.status())).count();
        final var blocked = jobs.stream().filter(j -> "blocked".equals(j.status())).count();
        running.setText(jobs.size() + " jobs · " + runningNow + " running"
                + (blocked > 0 ? " · " + blocked + " blocked" : ""));
    }
}
