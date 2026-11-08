package io.github.origingate.core.storage;

import java.sql.SQLException;

/** The systems the bundled SQLite runs on. {@link IsolatedDrivers} loads it. */
final class BundledSqlite {
    /**
     * The systems whose SQLite native library the plugin JARs include. The build leaves out the others to keep every
     * JAR under 10 MB; keep this in step with the excludes in build.gradle.kts and with docs/compatibility.md.
     */
    static final String SUPPORTED = "Linux (x86_64 and aarch64, glibc and musl), Windows (x86_64 and aarch64), "
            + "and macOS (x86_64 and aarch64)";

    private BundledSqlite() { }

    /**
     * A {@link SqliteUnavailableException} with a clear message when SQLite has no native library for this system;
     * other errors are returned unchanged.
     */
    static SQLException explain(SQLException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            if (cause.getClass().getSimpleName().equals("NativeLibraryNotFoundException") || cause instanceof UnsatisfiedLinkError
                    || message.contains("No native library found")) {
                return new SqliteUnavailableException("SQLite cannot run on this system (" + System.getProperty("os.name") + ", "
                        + System.getProperty("os.arch") + "). OriginGate includes SQLite for " + SUPPORTED
                        + ". Set storage.type to mysql to use MySQL or MariaDB instead.", error);
            }
        }
        return error;
    }
}
