package io.github.origingate.core.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The bundled SQLite and MariaDB drivers, created together in their own class loader the first time storage opens (at
 * startup) and kept until the server stops. SQLite needs its own loader because its JNI names cannot be relocated.
 * <p>
 * The plugin JAR may be renamed, deleted, or replaced while the server runs. Classes still load from the JAR file this
 * loader opened at startup, but the drivers also read their META-INF/services files through ServiceLoader on every
 * connection, and ServiceLoader opens the JAR again by its path. So this loader reads those files at startup and
 * serves them from memory.
 */
final class IsolatedDrivers {
    static final String SQLITE = "org.sqlite.JDBC";
    static final String MARIADB = org.mariadb.jdbc.Driver.class.getName();

    private IsolatedDrivers() { }

    static Driver sqlite() throws SQLException {
        return get(SQLITE, "SQLite");
    }

    static Driver mariadb() throws SQLException {
        return get(MARIADB, "MariaDB");
    }

    private static Driver get(String type, String name) throws SQLException {
        // A failed initializer would only be reported once; later calls would throw NoClassDefFoundError instead.
        Object result = Holder.DRIVERS.get(type);
        if (result instanceof Driver) return (Driver) result;
        throw new SQLException("Could not load bundled " + name, (Throwable) result);
    }

    private static final class Holder {
        /** Each driver class name with its Driver, or with what stopped it from loading. */
        static final Map<String, Object> DRIVERS = new HashMap<>();
        static {
            ClassLoader loader = null;
            Throwable failure = null;
            try { loader = loader(); } catch (Exception | LinkageError ex) { failure = ex; }
            for (String type : new String[] {SQLITE, MARIADB}) {
                Object result = failure;
                if (failure == null) {
                    try {
                        result = loader == null ? Class.forName(type).getDeclaredConstructor().newInstance()
                                : Class.forName(DriverAccess.class.getName(), true, loader).getMethod("create", String.class).invoke(null, type);
                    } catch (InvocationTargetException ex) {
                        result = ex.getCause();
                    } catch (Exception | LinkageError ex) {
                        result = ex;
                    }
                }
                DRIVERS.put(type, result);
            }
        }
    }

    /** Null when OriginGate runs from class folders, as in its own tests: the drivers then load normally. */
    private static ClassLoader loader() throws Exception {
        URL location = IsolatedDrivers.class.getProtectionDomain().getCodeSource().getLocation();
        Path jar = Paths.get(location.toURI());
        // A missing JAR (renamed or deleted while running) must fail here, not fall back to loading without isolation.
        if (Files.isDirectory(jar)) return null;
        Map<String, URL> services = services(jar);
        String mariadbPackage = MARIADB.substring(0, MARIADB.lastIndexOf('.') + 1);
        return new URLClassLoader(new URL[] {location}, IsolatedDrivers.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("org.sqlite.") && !name.startsWith(mariadbPackage) && !name.equals(DriverAccess.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
            @Override public URL getResource(String name) {
                if (name.startsWith("org/sqlite/") || name.startsWith("META-INF/maven/org.xerial/sqlite-jdbc/")) {
                    URL resource = findResource(name);
                    if (resource != null) return resource;
                }
                return super.getResource(name);
            }
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                URL service = services.get(name);
                return service == null ? super.getResources(name) : Collections.enumeration(Collections.singletonList(service));
            }
        };
    }

    /** The JAR's META-INF/services files, as URLs that read from memory. */
    private static Map<String, URL> services(Path jar) throws IOException {
        Map<String, URL> services = new HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (Enumeration<JarEntry> entries = file.entries(); entries.hasMoreElements(); ) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("META-INF/services/")) continue;
                byte[] data;
                try (InputStream input = file.getInputStream(entry)) {
                    data = input.readAllBytes();
                }
                services.put(entry.getName(), new URL("origingate-memory", null, -1, "/" + entry.getName(), new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override public void connect() { }
                            @Override public InputStream getInputStream() { return new ByteArrayInputStream(data); }
                        };
                    }
                }));
            }
        }
        return services;
    }
}
