package ru.terracraft.origingate.core.config;

public final class ConfigException extends Exception {
    public ConfigException(String message) { super(message); }
    public ConfigException(String message, Throwable cause) { super(message, cause); }
}
