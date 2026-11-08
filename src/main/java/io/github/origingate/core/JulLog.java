package io.github.origingate.core;

import java.util.logging.*;

public final class JulLog implements Log {
    private final Logger logger;
    public JulLog(Logger logger) { this.logger = logger; }
    @Override public void info(String message) { logger.info(message); }
    @Override public void warn(String message, Throwable cause) { logger.log(Level.WARNING, message, cause); }
    @Override public void error(String message, Throwable cause) { logger.log(Level.SEVERE, message, cause); }
    @Override public void debug(String message) { logger.info(message); }
}
