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
    
    @SerializedName("send_test_email_on_startup")
    private boolean sendTestEmailOnStartup = false;

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
        
        // Resolve environment variables
        instance.resolveEnvironmentVariables();
        
        // Validate configuration
        instance.validate();
    }

    /**
     * Resolve environment variable references in configuration
     * Values starting with "env:" will be replaced with the corresponding environment variable
     */
    private void resolveEnvironmentVariables() {
        recipientEmail = resolveEnvVar(recipientEmail);
        
        if (mailConfig != null) {
            mailConfig.resolveEnvironmentVariables();
        }
    }

    /**
     * Resolve a single environment variable reference
     * @param value The value that may contain an env: reference
     * @return The resolved value or the original if not an env reference
     */
    private static String resolveEnvVar(String value) {
        if (value != null && value.startsWith("env:")) {
            String envVarName = value.substring(4);
            String envValue = System.getenv(envVarName);
            if (envValue == null) {
                throw new IllegalStateException("Environment variable not found: " + envVarName);
            }
            return envValue;
        }
        return value;
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

    public boolean isSendTestEmailOnStartup() {
        return sendTestEmailOnStartup;
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

        /**
         * Resolve environment variable references in mail configuration
         */
        private void resolveEnvironmentVariables() {
            smtpHost = Config.resolveEnvVar(smtpHost);
            username = Config.resolveEnvVar(username);
            password = Config.resolveEnvVar(password);
            fromEmail = Config.resolveEnvVar(fromEmail);
            fromName = Config.resolveEnvVar(fromName);
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
