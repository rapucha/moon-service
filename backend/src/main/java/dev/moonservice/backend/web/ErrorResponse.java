package dev.moonservice.backend.web;

record ErrorResponse(
        String status,
        String generatedAt,
        String message
) {
}
