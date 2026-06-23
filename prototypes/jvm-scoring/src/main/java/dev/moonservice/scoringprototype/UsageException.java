package dev.moonservice.scoringprototype;

public final class UsageException extends RuntimeException {
    public UsageException(String message) {
        super(message);
    }

    public UsageException(String message, Throwable cause) {
        super(message, cause);
    }
}
