package com.example.eventgateway.dto;

import java.time.Instant;

public class ErrorResponse {
    private String error;
    private String message;
    private Instant timestamp = Instant.now();

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
