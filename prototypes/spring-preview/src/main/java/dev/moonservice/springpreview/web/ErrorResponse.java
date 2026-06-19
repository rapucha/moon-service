package dev.moonservice.springpreview.web;

record ErrorResponse(
        String status,
        String generatedAt,
        String message
) {
}
