package com.strgmai.ace.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
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
}
