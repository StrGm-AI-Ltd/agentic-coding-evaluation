package com.strgmai.ace.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.shared.communication.PushMode;
import com.vaadin.flow.theme.aura.Aura;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Spring Boot application class doubles as Vaadin's app shell (vaadin-spring),
 * so it implements AppShellConfigurator directly and carries @Push — server push
 * flushes the job page's SSE events to the browser instantly. @StyleSheet(Aura.STYLESHEET) is
 * required explicitly once an AppShellConfigurator is defined (Aura is otherwise loaded
 * automatically, but only in the absence of one - confirmed against the Vaadin 25 docs
 * directly, the same rigor previously applied to Lumo's own equivalent requirement).
 *
 * #529: migrated from Lumo to Aura - Aura's own built-in dark theme now provides the whole
 * "Night operator" look (@ColorScheme(DARK) below works identically for both themes, confirmed
 * against the docs). The hand-built styles.css palette/typography overrides and MainLayout's
 * own hand-styled sidebar CSS (#476/#527/#528) are gone, not retargeted - every var(--lumo-*)
 * customization across the app was removed outright per that decision, relying on Aura's own
 * standard component styling instead of recreating the old Lumo-based custom look on top of it.
 *
 * A new styles.css (META-INF/resources/) was added back afterward, but on Aura's own terms: it
 * only redeclares Aura's documented --aura-* custom properties (accent color, radius, font, etc. -
 * see the Aura theming docs) plus a few ::part()-based component tweaks, not Lumo-era hand-rolled
 * component CSS.
 */
@Push(PushMode.AUTOMATIC)
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("styles.css")
@ColorScheme(ColorScheme.Value.DARK)
@SpringBootApplication
@ConfigurationPropertiesScan
public class AceUiVaadinApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(AceUiVaadinApplication.class, args);
    }

    // favicon.ico (under META-INF/resources/) already covers the browser's own implicit
    // /favicon.ico request; this explicit PNG link is what modern browsers actually prefer
    // and render crisper in the tab
    @Override
    public void configurePage(final AppShellSettings settings) {
        settings.addFavIcon("icon", "icons/icon-32.png", "32x32");
    }
}
