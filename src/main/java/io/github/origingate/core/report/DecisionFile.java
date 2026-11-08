package io.github.origingate.core.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Daily log files named {@code yyyy-MM-dd.log}. Old files are deleted by {@link #deleteOlderThan}. */
public final class DecisionFile {
    private static final Pattern NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.log");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Path directory;
    private final Clock clock;

    public DecisionFile(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    public synchronized void write(String line) throws IOException {
        LocalDateTime now = LocalDateTime.now(clock);
        Files.createDirectories(directory);
        Path file = directory.resolve(now.toLocalDate() + ".log");
        Files.writeString(file, "[" + TIME.format(now) + "] " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Deletes daily files whose date is more than {@code days} days ago. */
    public synchronized int deleteOlderThan(int days) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        LocalDate oldestKept = LocalDate.now(clock).minusDays(days);
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.log")) {
            for (Path file : files) {
                Matcher name = NAME.matcher(file.getFileName().toString());
                if (!name.matches()) continue;
                try {
                    if (LocalDate.parse(name.group(1)).isBefore(oldestKept) && Files.deleteIfExists(file)) deleted++;
                } catch (DateTimeParseException ignored) {
                    // Not one of our files.
                }
            }
        }
        return deleted;
    }
}
