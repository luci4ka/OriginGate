package io.github.origingate.core;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Small text helpers shared by logs, commands, and messages. */
public final class Text {
    private Text() { }

    /** The value, or "-" when unknown. */
    public static String dash(String value) {
        return value == null ? "-" : value;
    }

    /** The underlying cause, without CompletableFuture wrappers. */
    public static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** A readable message for an error, never null. */
    public static String message(Throwable error) {
        Throwable cause = unwrap(error);
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
