package com.classos.timetable;

import com.classos.common.ApiException;
import com.classos.config.ClassosProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/** HTTP client for the C++ service (timetable solver and grading scheduler). Small so tests can mock it. */
@Component
public class SolverClient {

    private final RestClient rest;
    private final String url;

    public SolverClient(ClassosProperties props) {
        ClassosProperties.Solver cfg = props.solver();
        this.url = cfg.url();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(cfg.readTimeoutSeconds()));
        this.rest = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    public SolverProtocol.Response solve(SolverProtocol.Request request) {
        return post("/v1/timetable/solve", request, SolverProtocol.Response.class);
    }

    public SolverProtocol.ScheduleResponse schedule(SolverProtocol.ScheduleRequest request) {
        return post("/v1/schedule", request, SolverProtocol.ScheduleResponse.class);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            T response = rest.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
            if (response == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "The C++ service returned no result.");
            return response;
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The C++ service is not reachable at " + url + ". Start it and try again.");
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The C++ service rejected the request: " + e.getResponseBodyAsString());
        }
    }
}
