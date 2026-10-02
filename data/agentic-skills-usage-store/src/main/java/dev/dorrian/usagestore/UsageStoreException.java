package dev.dorrian.usagestore;

/** Any failure talking to the usage database. Hook callers swallow it; CLI callers report it. */
public final class UsageStoreException extends RuntimeException {
    public UsageStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
