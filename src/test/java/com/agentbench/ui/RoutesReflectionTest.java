package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import com.vaadin.flow.router.Route;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route wiring without a servlet container (the Karibu-for-Flow-25 caveat):
 * every @Route-annotated view is registered with the expected URL under MainLayout.
 * Catches renamed/duplicated/orphaned routes. (Tab coverage needs a live Flow
 * runtime — RouterLink requires a VaadinService — so it stays covered by the
 * @Route registration set above plus manual smoke of the nav.)
 */
class RoutesReflectionTest {

    private static final Map<String, Class<?>> EXPECTED = java.util.Map.ofEntries(
            java.util.Map.entry("", RootView.class),
            java.util.Map.entry("runs", RunsView.class),
            java.util.Map.entry("runs/:runId", RunDetailView.class),
            java.util.Map.entry("groups", GroupsView.class),
            java.util.Map.entry("compare", CompareView.class),
            java.util.Map.entry("jobs", JobsView.class),
            java.util.Map.entry("jobs/:jobId", JobDetailView.class),
            java.util.Map.entry("jobs/new", JobNewView.class),
            java.util.Map.entry("experiments", ExperimentsView.class),
            java.util.Map.entry("experiments/:experimentId", ExperimentDetailView.class),
            java.util.Map.entry("experiments/new", ExperimentNewView.class),
            java.util.Map.entry("preflight", PreflightView.class));

    private static Set<Class<?>> scanRouteClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Route.class));
        Set<Class<?>> views = new HashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.agentbench.ui")) {
            views.add(Class.forName(candidate.getBeanClassName()));
        }
        return views;
    }

    @Test
    void allRouteClassesRegisteredWithExpectedValues() throws Exception {
        Map<String, Class<?>> found = new HashMap<>();
        for (Class<?> view : scanRouteClasses()) {
            Route route = view.getAnnotation(Route.class);
            assertEquals(MainLayout.class, route.layout(), view + " must use MainLayout");
            found.put(route.value(), view);
        }
        assertEquals(EXPECTED, found, "the registered route set must match the expected set exactly");
    }
}
