package ru.terracraft.origingate.core.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A YAML map with typed, bounded getters. Errors name the full key path. */
final class YamlSection {
    private static final int MAX_FILE_BYTES = 64 * 1024;
    private final Map<?, ?> values;
    private final String path;

    private YamlSection(Map<?, ?> values, String path) {
        this.values = values;
        this.path = path;
    }

    static YamlSection load(Path file) throws ConfigException {
        String name = file.getFileName().toString();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigException(name + " must be a regular file");
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new ConfigException(name + " is larger than 64 KiB");
            try (InputStream input = Files.newInputStream(file)) {
                return parse(input, name);
            }
        } catch (IOException | RuntimeException ex) {
            throw new ConfigException("Cannot read " + name + ": " + ex.getMessage(), ex);
        }
    }

    /** A file bundled in the plugin JAR, such as the default messages.yml. */
    static YamlSection resource(String name) throws ConfigException {
        try (InputStream input = ConfigLoader.bundled(name)) {
            if (input == null) throw new ConfigException("Missing bundled " + name);
            return parse(input, name);
        } catch (IOException | RuntimeException ex) {
            throw new ConfigException("Cannot read the bundled " + name + ": " + ex.getMessage(), ex);
        }
    }

    private static YamlSection parse(InputStream input, String name) throws ConfigException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(10);
        options.setCodePointLimit(MAX_FILE_BYTES);
        Object raw = new Yaml(new SafeConstructor(options)).load(input);
        if (!(raw instanceof Map<?, ?>)) throw new ConfigException(name + " must contain a YAML map");
        return new YamlSection((Map<?, ?>) raw, "");
    }

    String key(String name) { return path.isEmpty() ? name : path + "." + name; }

    void allowOnly(String... keys) throws ConfigException {
        Set<String> allowed = Set.of(keys);
        for (Object key : values.keySet()) {
            if (!(key instanceof String) || !allowed.contains(key)) {
                throw new ConfigException("Unknown setting: " + key(String.valueOf(key)));
            }
        }
    }

    boolean has(String name) { return values.containsKey(name); }

    YamlSection section(String name) throws ConfigException {
        if (!(required(name) instanceof Map<?, ?>)) throw new ConfigException(key(name) + " must be a section");
        return new YamlSection((Map<?, ?>) required(name), key(name));
    }

    String text(String name, int min, int max) throws ConfigException {
        if (!(required(name) instanceof String) || ((String) required(name)).length() < min || ((String) required(name)).length() > max) {
            throw new ConfigException(key(name) + " must be text with " + min + " to " + max + " characters");
        }
        return (String) required(name);
    }

    boolean bool(String name) throws ConfigException {
        if (!(required(name) instanceof Boolean)) throw new ConfigException(key(name) + " must be true or false");
        return (Boolean) required(name);
    }

    int integer(String name, int min, int max) throws ConfigException {
        if (!(required(name) instanceof Integer) || (Integer) required(name) < min || (Integer) required(name) > max) {
            throw new ConfigException(key(name) + " must be a whole number from " + min + " to " + max);
        }
        return (Integer) required(name);
    }

    /** A list of text values. Scalars such as numbers are accepted and read as text. */
    List<String> list(String name, int maxEntries) throws ConfigException {
        if (!(required(name) instanceof List<?>)) throw new ConfigException(key(name) + " must be a list, for example []");
        List<?> raw = (List<?>) required(name);
        if (raw.size() > maxEntries) throw new ConfigException(key(name) + " allows at most " + maxEntries + " entries");
        List<String> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof String || item instanceof Number) || item.toString().isBlank()
                    || item.toString().length() > 256) {
                throw new ConfigException(key(name) + " contains an invalid entry: " + item);
            }
            result.add(item.toString().trim());
        }
        return List.copyOf(result);
    }

    <E extends Enum<E>> E choice(String name, Class<E> type) throws ConfigException {
        // YAML reads bare words such as off or yes as true/false, so anything that is not text lands here too.
        if (required(name) instanceof String) {
            for (E option : type.getEnumConstants()) {
                if (option.name().equalsIgnoreCase((String) required(name))) return option;
            }
        }
        throw new ConfigException(key(name) + " must be one of " + options(type));
    }

    private static <E extends Enum<E>> String options(Class<E> type) {
        List<String> names = new ArrayList<>();
        for (E option : type.getEnumConstants()) names.add(option.name().toLowerCase(java.util.Locale.ROOT));
        return String.join(", ", names);
    }

    private Object required(String name) throws ConfigException {
        if (!values.containsKey(name)) throw new ConfigException("Missing setting: " + key(name));
        return values.get(name);
    }
}