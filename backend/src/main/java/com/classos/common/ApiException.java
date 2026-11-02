package com.classos.common;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/** An error with an HTTP status and optional extra JSON fields (e.g. the list of conflicts). */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public HttpStatus getStatus() { return status; }
    public Map<String, Object> getProperties() { return properties; }
}
