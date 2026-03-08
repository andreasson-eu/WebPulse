package eu.andreasson.webpulse.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ConsoleLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ConsoleLogger() {
    }

    public static void log(String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        System.out.println("[" + timestamp + "] " + message);
    }

    public static void log() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        System.out.println("[" + timestamp + "]");
    }
}