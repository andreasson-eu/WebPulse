package eu.andreasson.webpulse;

import java.io.File;

import eu.andreasson.webpulse.config.Config;
import eu.andreasson.webpulse.monitor.WebPulse;

/**
 * Main application entry point for WebPulse Health Monitor
 */
public class Application {
    private static final String DEFAULT_CONFIG_PATH = "config.json";

    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("    WebPulse Health Monitor v1.0.2");
        System.out.println("===========================================");
        System.out.println();

        // Determine config file path
        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG_PATH;
        
        try {
            // Validate config file exists
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                System.err.println("Error: Configuration file not found: " + configPath);
                System.err.println();
                System.err.println("Please create a config.json file based on config.json.example");
                System.err.println("Usage: java -jar webpulse.jar [config-file-path]");
                System.exit(1);
            }

            // Load configuration
            System.out.println("Loading configuration from: " + configPath);
            Config.load(configPath);
            System.out.println("Configuration loaded successfully");
            System.out.println();

            // Test email service if enabled
            if (Config.getInstance().isSendTestEmailOnStartup()) {
                System.out.println("Testing email service...");
                eu.andreasson.webpulse.mail.MailClient testMailClient = new eu.andreasson.webpulse.mail.MailClient();
                testMailClient.sendTestEmail();
                System.out.println();
            }

            // Create and start monitor
            WebPulse monitor = new WebPulse();
            
            // Add shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println();
                monitor.stop();
                System.out.println("WebPulse Health Monitor stopped");
            }));

            // Start monitoring
            monitor.start();
            
            // Keep the application running
            synchronized (Application.class) {
                try {
                    Application.class.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
