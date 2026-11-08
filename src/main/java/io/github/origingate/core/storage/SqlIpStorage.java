package io.github.origingate.core.storage;

import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lookup.IpInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;

/**
 * Lookups saved in the {@code origingate_ip_cache} table, in SQLite or MySQL/MariaDB.
 * One connection per operation; callers bound concurrency with their worker pool. The table is created
 * on the first connection that works, so a database that is down at startup is set up once it answers.
 */
public final class SqlIpStorage implements IpStorage {
    public static final String TABLE = "origingate_ip_cache";
    private static final String COLUMNS =
            "ip,provider,organisation,operator_name,city,region,country,country_code,asn,vpn,proxy,type,checked_at";
    private static final String CREATE_COLUMNS = "ip VARCHAR(45) NOT NULL PRIMARY KEY, provider VARCHAR(255), "
            + "organisation VARCHAR(255), operator_name VARCHAR(255), city VARCHAR(255), region VARCHAR(255), "
            + "country VARCHAR(255), country_code CHAR(2), asn VARCHAR(32), vpn BOOLEAN NOT NULL, "
            + "proxy BOOLEAN NOT NULL, type VARCHAR(64), checked_at BIGINT NOT NULL";

    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }

    private final Connections connections;
    private final boolean mysql;
    private volatile boolean ready;

    private SqlIpStorage(Connections connections, boolean mysql) {
        this.connections = connections;
        this.mysql = mysql;
    }

    /**
     * Opens storage and creates the table. When the database cannot be reached at all and
     * {@code allowUnreachable} is true, storage is returned unready and set up on first use.
     * A reachable database with a setup problem (for example missing permissions) always throws.
     */
    public static SqlIpStorage open(Connections connections, boolean mysql, boolean allowUnreachable) throws SQLException {
        SqlIpStorage storage = new SqlIpStorage(connections, mysql);
        Connection connection;
        try {
            connection = connections.open();
        } catch (SQLException ex) {
            if (allowUnreachable) return storage;
            throw ex;
        } catch (ServiceConfigurationError | LinkageError ex) {
            if (allowUnreachable) return storage;
            throw new SQLException("The database driver could not be loaded: " + ex, ex);
        }
        try (Connection opened = connection) {
            storage.setup(connection);
        }
        return storage;
    }

    public static SqlIpStorage sqlite(Path file) throws SQLException {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute.getParent());
        } catch (IOException ex) {
            throw new SQLException("Cannot create the folder for " + absolute, ex);
        }
        String url = "jdbc:sqlite:" + absolute;
        return open(() -> {
            Connection connection;
            try {
                connection = IsolatedDrivers.sqlite().connect(url, new Properties());
            } catch (SQLException ex) {
                throw BundledSqlite.explain(ex);
            } catch (LinkageError ex) {
                // After a first failed load (such as on reload), sqlite-jdbc throws the link error itself.
                throw BundledSqlite.explain(new SQLException("SQLite could not be loaded", ex));
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
            } catch (SQLException ex) {
                connection.close();
                throw ex;
            }
            return connection;
        }, false, false);
    }

    /** MySQL/MariaDB. An unreachable database does not fail startup; see {@link #ready()}. */
    public static SqlIpStorage mysql(OriginGateConfig.Mysql config) throws SQLException {
        return open(() -> openMysql(config), true, true);
    }

    /** False until the table has been set up, for example while the database was unreachable at startup. */
    public boolean ready() {
        return ready;
    }

    private static Connection openMysql(OriginGateConfig.Mysql config) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("sslMode", config.sslMode());
        properties.setProperty("connectTimeout", String.valueOf(config.connectTimeoutMillis()));
        properties.setProperty("socketTimeout", String.valueOf(config.socketTimeoutMillis()));
        properties.setProperty("allowLocalInfile", "false");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("maxQuerySizeToLog", "0");
        Connection connection = IsolatedDrivers.mariadb().connect(
                "jdbc:mariadb://" + config.host() + ":" + config.port() + "/" + config.database(), properties);
        if (connection == null) throw new SQLException("The MariaDB driver did not accept the connection settings");
        return connection;
    }

    private void setup(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (mysql) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (" + CREATE_COLUMNS
                        + ", INDEX " + TABLE + "_checked_at (checked_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            } else {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (" + CREATE_COLUMNS + ")");
                statement.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_checked_at ON " + TABLE + " (checked_at)");
            }
        }
        ready = true;
    }

    /** A connection, with the table set up first if that has not happened yet. */
    private Connection connection() throws SQLException {
        Connection connection = connections.open();
        if (!ready) {
            try {
                setup(connection);
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.close();
                } catch (SQLException closing) {
                    ex.addSuppressed(closing);
                }
                throw ex;
            }
        }
        return connection;
    }

    @Override public Optional<IpInfo> find(String ip, Instant notBefore) throws SQLException {
        Objects.requireNonNull(ip);
        try (Connection connection = connection(); PreparedStatement query = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE ip=? AND checked_at>=?")) {
            query.setString(1, ip);
            query.setLong(2, notBefore.getEpochSecond());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new IpInfo(row.getString("ip"), row.getString("provider"), row.getString("organisation"),
                        row.getString("operator_name"), row.getString("city"), row.getString("region"),
                        row.getString("country"), row.getString("country_code"), row.getString("asn"),
                        row.getBoolean("vpn"), row.getBoolean("proxy"), row.getString("type"),
                        Instant.ofEpochSecond(row.getLong("checked_at"))));
            }
        }
    }

    @Override public void save(IpInfo info) throws SQLException {
        // REPLACE is an atomic upsert in both SQLite and MySQL, so several proxies can share one table.
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "REPLACE INTO " + TABLE + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, info.ip());
            text(statement, 2, info.provider(), 255);
            text(statement, 3, info.organisation(), 255);
            text(statement, 4, info.operatorName(), 255);
            text(statement, 5, info.city(), 255);
            text(statement, 6, info.region(), 255);
            text(statement, 7, info.country(), 255);
            text(statement, 8, info.countryCode() != null && info.countryCode().length() == 2 ? info.countryCode() : null, 2);
            text(statement, 9, info.asn(), 32);
            statement.setBoolean(10, info.vpn());
            statement.setBoolean(11, info.proxy());
            text(statement, 12, info.type(), 64);
            statement.setLong(13, info.checkedAt().getEpochSecond());
            statement.executeUpdate();
        }
    }

    @Override public int deleteOlderThan(Instant cutoff) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE checked_at<?")) {
            statement.setLong(1, cutoff.getEpochSecond());
            return statement.executeUpdate();
        }
    }

    @Override public int delete(String ip) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE ip=?")) {
            statement.setString(1, ip);
            return statement.executeUpdate();
        }
    }

    @Override public int deleteAll() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            return statement.executeUpdate("DELETE FROM " + TABLE);
        }
    }

    private static void text(PreparedStatement statement, int index, String value, int max) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value.length() > max ? value.substring(0, max) : value);
    }
}
