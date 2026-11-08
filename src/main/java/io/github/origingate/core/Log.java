package io.github.origingate.core;

/** Logging bridge so core has no platform dependency. */
public interface Log {
    void info(String message);

    void warn(String message, Throwable cause);

    /** For problems that leave connections unchecked. Logged as a warning where the platform has no error level. */
    default void error(String message, Throwable cause) { warn(message, cause); }

    /** Step-by-step detail, shown only with {@code console-log: debug}. */
    void debug(String message);

    /** Wraps a log so {@link #debug} is printed only when {@code enabled} is true. */
    static Log withDebug(Log base, boolean enabled) {
        return new Log() {
            @Override public void info(String message) { base.info(message); }
            @Override public void warn(String message, Throwable cause) { base.warn(message, cause); }
            @Override public void error(String message, Throwable cause) { base.error(message, cause); }
            @Override public void debug(String message) { if (enabled) base.info("[debug] " + message); }
        };
    }

    Log NONE = new Log() {
        @Override public void info(String message) { }
        @Override public void warn(String message, Throwable cause) { }
        @Override public void debug(String message) { }
    };
}
