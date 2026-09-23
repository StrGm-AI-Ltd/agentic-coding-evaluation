package com.strgmai.ace.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.shared.communication.PushMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Spring Boot application class doubles as Vaadin's app shell (vaadin-spring),
 * so it implements AppShellConfigurator directly and carries @Push — server push
 * flushes the job page's SSE events to the browser instantly.
 */
@Push(PushMode.AUTOMATIC)
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
