package com.strgmai.ace.ui;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;

/** #473: the landing page every operator (and every hackathon judge) sees first - the project's
 *  goal, then a quick-orientation list of real, working links into the UI itself, then a link out
 *  to the full wiki for anyone who wants the deep documentation instead. */
@Route(value = "info", layout = MainLayout.class)
public class InfoView extends Div {

    public InfoView() {
        getStyle().set("padding", "var(--lumo-space-m)").set("max-width", "720px");

        add(new H2("ACE"));
        final var goal = new Paragraph();
        goal.add(new Span("ACE (Agentic Coding Evaluation) is a reproducible framework for evaluating AI "
                + "coding agents, models, and agent harnesses under controlled conditions. It drives a "
                + "coding agent through a frozen, non-trivial build "), taskLadderLink("task"), new Span(", then "
                + "scores the result with an oracle that never trusts the agent's own word for anything."));
        add(goal);

        add(new H3("User manual"));
        add(new Paragraph(new Span("See the "), taskLadderLink("Task Ladder"),
                new Span(" for what each task actually asks the agent to build.")));
        final var list = new UnorderedList();
        list.add(manualItem(new RouterLink("Runs", RunsView.class), "browse every run and its full scorecard"));
        list.add(manualItem(new RouterLink("Groups", GroupsView.class), "the pooled leaderboard, comparable runs ranked together"));
        list.add(manualItem(new RouterLink("Compare", CompareView.class), "compare two runs or two models task-by-task"));
        list.add(manualItem(new RouterLink("Queue", JobsView.class), "start a new benchmark run and watch it live"));
        list.add(manualItem(new RouterLink("Experiments", ExperimentsView.class), "run several configurations as one batch"));
        list.add(manualItem(new RouterLink("Preflight", PreflightView.class), "a one-click readiness check before trusting any run"));
        list.add(manualItem(new RouterLink("LLM Providers", LlmProvidersView.class), "configure which models ACE can target"));
        add(list);

        add(new H3("Full documentation"));
        final var wiki = new Anchor("https://github.com/StrGm-AI-Ltd/agentic-coding-evaluation/wiki",
                "ACE Wiki — architecture, design decisions, and a screenshot-driven manual");
        wiki.setTarget("_blank");
        add(new Paragraph(wiki));
    }

    private static ListItem manualItem(final RouterLink link, final String description) {
        final var item = new ListItem();
        item.add(link, new Span(" — " + description));
        return item;
    }

    /** A fresh Anchor instance every call - the same Vaadin component can't be attached to the
     *  component tree twice, and this is used twice on this page (the goal paragraph and the
     *  User manual section's own lead-in), each with its own link text. */
    private static Anchor taskLadderLink(final String text) {
        final var link = new Anchor("https://github.com/StrGm-AI-Ltd/agentic-coding-evaluation/wiki/Task-Ladder", text);
        link.setTarget("_blank");
        return link;
    }
}
