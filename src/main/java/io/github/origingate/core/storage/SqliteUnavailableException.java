package io.github.origingate.core.storage;

import java.sql.SQLException;

/** SQLite has no native library for this system, so it cannot run here at all. */
public final class SqliteUnavailableException extends SQLException {
    SqliteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
