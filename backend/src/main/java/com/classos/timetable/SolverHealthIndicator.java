package com.classos.timetable;

import com.classos.config.ClassosProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Shows whether the C++ service answers. Reported as UNKNOWN (not DOWN) when it doesn't,
 * because the backend still works without it: only timetable generation stops, and the
 * grading queue falls back to FCFS. So a solver outage never marks the whole backend unhealthy.
 */
@Component("solver")
public class SolverHealthIndicator implements HealthIndicator {

    private final RestClient rest;

    public SolverHealthIndicator(ClassosProperties props) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(1));
        f.setReadTimeout(Duration.ofSeconds(2));
        this.rest = RestClient.builder().baseUrl(props.solver().url()).requestFactory(f).build();
    }

    @Override
    public Health health() {
        try {
            rest.get().uri("/health").retrieve().toBodilessEntity();
            return Health.up().build();
        } catch (RuntimeException e) {
            return Health.unknown().build();
        }
    }
}
