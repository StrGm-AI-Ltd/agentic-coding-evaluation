package com.agentbench;

import com.agentbench.metrics.StatsService;
import com.agentbench.reference.TradingService;
import com.agentbench.runner.ContextProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The Java/Spring/LangChain4j port of agentbench-trading: one runnable app serving the run queue,
 *  results store, oracle and the LangChain4j reference agent. The Python original's CLI
 *  (runner/run_bench.py) maps onto the queued job path: the worker runs benchmarks in-process. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AgentbenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentbenchApplication.class, args);
    }

    /** TradingController's bean: the correct-by-default contract (no injected bugs), unlike
     *  ReferenceServer's standalone calibration twin, which takes a `bugs` set on purpose. */
    @Bean
    public TradingService tradingService() { return new TradingService(); }

    /** RunBench's model-server probe: stateless (no config it needs at construction), so a plain
     *  instance is enough - ExperimentsService already builds its own the same way. */
    @Bean
    public ContextProbe contextProbe() { return new ContextProbe(); }

    /** BenchController's bootstrap/comparison bridge: stateless, same reasoning as ContextProbe
     *  above - ExperimentsService already carries its own private instance the same way. */
    @Bean
    public StatsService statsService() { return new StatsService(); }
}
