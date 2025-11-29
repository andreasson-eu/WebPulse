package eu.andreasson.webpulse.config;

import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * Singleton configuration class that loads and provides access to application configuration
 */
public class Config {
    private static Config instance;
    
    @SerializedName("mail")
    private MailConfig mailConfig;
    
    @SerializedName("urls")
    private List<String> monitoredUrls;
    
    @SerializedName("recipient_email")
    private String recipientEmail;
    
    @SerializedName("check_interval_minutes")
    private int checkIntervalMinutes = 5;
    
    @SerializedName("failure_threshold")
    private int failureThreshold = 5;
    
    @SerializedName("alert_cooldown_hours")
    private int alertCooldownHours = 24;

    private Config() {
        // Private constructor for singleton
    }

    /**
     * Load configuration from config.json file
     */
    public static void load(String configPath) throws IOException {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(configPath)) {
            instance = gson.fromJson(reader, Config.class);
        }
        
        if (instance == null) {
            throw new IOException("Failed to load configuration from " + configPath);
        }
        
        // Validate configuration
        instance.validate();
    }

    /**
     * Get the singleton instance of Config
     */
    public static Config getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Configuration not loaded. Call Config.load() first.");
        }
        return instance;
    }

    /**
     * Validate configuration values
     */
    private void validate() {
        if (mailConfig == null) {
            throw new IllegalStateException("Mail configuration is missing");
        }
        if (monitoredUrls == null || monitoredUrls.isEmpty()) {
            throw new IllegalStateException("No URLs configured for monitoring");
        }
        if (recipientEmail == null || recipientEmail.isEmpty()) {
            throw new IllegalStateException("Recipient email is not configured");
        }
        mailConfig.validate();
    }

    public MailConfig getMailConfig() {
        return mailConfig;
    }

    public List<String> getMonitoredUrls() {
        return monitoredUrls;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public int getCheckIntervalMinutes() {
        return checkIntervalMinutes;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public int getAlertCooldownHours() {
        return alertCooldownHours;
    }

    /**
     * Mail service configuration
     */
    public static class MailConfig {
        @SerializedName("smtp_host")
        private String smtpHost;
        
        @SerializedName("smtp_port")
        private int smtpPort;
        
        @SerializedName("username")
        private String username;
        
        @SerializedName("password")
        private String password;
        
        @SerializedName("from_email")
        private String fromEmail;
        
        @SerializedName("from_name")
        private String fromName;
        
        @SerializedName("enable_tls")
        private boolean enableTls = true;

        private void validate() {
            if (smtpHost == null || smtpHost.isEmpty()) {
                throw new IllegalStateException("SMTP host is not configured");
            }
            if (username == null || username.isEmpty()) {
                throw new IllegalStateException("SMTP username is not configured");
            }
            if (password == null || password.isEmpty()) {
                throw new IllegalStateException("SMTP password is not configured");
            }
            if (fromEmail == null || fromEmail.isEmpty()) {
                throw new IllegalStateException("From email is not configured");
            }
        }

        public String getSmtpHost() {
            return smtpHost;
        }

        public int getSmtpPort() {
            return smtpPort;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getFromEmail() {
            return fromEmail;
        }

        public String getFromName() {
            return fromName != null ? fromName : fromEmail;
        }

        public boolean isEnableTls() {
            return enableTls;
        }
    }
}
