package com.strgmai.ace.service;

import com.strgmai.ace.service.backend.LocalModelBackend;
import com.strgmai.ace.service.backend.OmlxBackend;
import com.strgmai.ace.service.metrics.StatsService;
import com.strgmai.ace.service.reference.TradingService;
import com.strgmai.ace.service.runner.ContextProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

/** The Java/Spring/LangChain4j port of agentbench-trading: one runnable app serving the run queue,
 *  results store, oracle and the LangChain4j reference agent. The Python original's CLI
 *  (runner/run_bench.py) maps onto the queued job path: the worker runs benchmarks in-process. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AceServiceApplication {
    public static void main(final String[] args) {
        SpringApplication.run(AceServiceApplication.class, args);
    }

    /** TradingController's bean: the correct-by-default contract (no injected bugs), unlike
     *  ReferenceServer's standalone calibration twin, which takes a `bugs` set on purpose. */
    @Bean
    public TradingService tradingService() { return new TradingService(); }

    /** #65: the local model server's own adapter - oMLX today, the only implementation. Its root
     *  directory defaults to `~/.omlx`; ContextProbe goes through this instead of inlining oMLX's
     *  own paths/CLI/log format directly. */
    @Bean
    public LocalModelBackend localModelBackend() { return new OmlxBackend(Path.of(System.getProperty("user.home"), ".omlx")); }

    /** RunBench's model-server probe: stateless (no config it needs at construction beyond the
     *  backend), so a plain instance is enough - ExperimentsService already builds its own the same way. */
    @Bean
    public ContextProbe contextProbe(final LocalModelBackend localModelBackend) { return new ContextProbe(localModelBackend); }

    /** BenchController's bootstrap/comparison bridge: stateless, same reasoning as ContextProbe
     *  above - ExperimentsService already carries its own private instance the same way. */
    @Bean
    public StatsService statsService() { return new StatsService(); }
}
