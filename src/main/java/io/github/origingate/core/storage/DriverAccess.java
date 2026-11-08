package io.github.origingate.core.storage;

import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;

/** Loaded with the isolated drivers so DriverManager's caller visibility check permits deregistration. */
public final class DriverAccess {
    private DriverAccess() { }

    /** A new {@code type} driver. Other plugins never reach it through DriverManager. */
    public static Driver create(String type) throws Exception {
        Driver driver = (Driver) Class.forName(type).getDeclaredConstructor().newInstance();
        Enumeration<Driver> registered = DriverManager.getDrivers();
        while (registered.hasMoreElements()) {
            Driver candidate = registered.nextElement();
            if (candidate.getClass().getClassLoader() == DriverAccess.class.getClassLoader()) DriverManager.deregisterDriver(candidate);
        }
        // Reads the driver's version file now, while the JAR is surely in place.
        driver.getMajorVersion();
        return driver;
    }
}
