package com.example.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId,
        List<Detail> details) {

    public record Detail(String field, String message) {
    }
}