package dev.moonservice.backend.opportunity;

public final class InvalidOpportunitySearchRequestException extends RuntimeException {
    public InvalidOpportunitySearchRequestException(String message) {
        super(message);
    }

    public InvalidOpportunitySearchRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
