package com.strgmai.ace.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.shared.communication.PushMode;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Spring Boot application class doubles as Vaadin's app shell (vaadin-spring),
 * so it implements AppShellConfigurator directly and carries @Push — server push
 * flushes the job page's SSE events to the browser instantly. @StyleSheet(Lumo.STYLESHEET) is
 * required explicitly on Vaadin 25+ (Lumo is no longer auto-loaded without @Theme/@StyleSheet) -
 * every Badge theme variant and var(--lumo-*) style elsewhere in this app depends on it.
 *
 * #476 (PR 1/2): @ColorScheme(DARK) commits the whole app to Lumo's dark color scheme, always -
 * no light/dark toggle, matching the "Night operator" direction. It sets theme="dark" on the
 * document, which is what makes the [theme~="dark"] selectors in styles.css (loaded right after
 * Lumo itself, so it wins the cascade - see the Vaadin styling docs' own "themes before other
 * styles" rule) apply the brand palette/typography on top of Lumo's own dark grayscale instead of
 * hand-deriving a full contrast scale from scratch.
 */
@Push(PushMode.AUTOMATIC)
@StyleSheet(Lumo.STYLESHEET)
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
