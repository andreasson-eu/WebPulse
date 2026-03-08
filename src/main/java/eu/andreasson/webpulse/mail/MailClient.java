package eu.andreasson.webpulse.mail;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import eu.andreasson.webpulse.config.Config;
import eu.andreasson.webpulse.logging.ConsoleLogger;

/**
 * Mail client for sending alert notifications via Gmail SMTP
 */
public class MailClient {
    private final Config.MailConfig mailConfig;
    private final Session session;

    public MailClient() {
        this.mailConfig = Config.getInstance().getMailConfig();
        this.session = createSession();
    }

    /**
     * Create and configure mail session
     */
    private Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", mailConfig.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(mailConfig.getSmtpPort()));
        
        // Port 465 requires SSL, port 587 uses STARTTLS
        if (mailConfig.getSmtpPort() == 465) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        } else {
            props.put("mail.smtp.starttls.enable", String.valueOf(mailConfig.isEnableTls()));
            props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");
        }

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                    mailConfig.getUsername(),
                    mailConfig.getPassword()
                );
            }
        });
    }

    /**
     * Send an alert email
     * 
     * @param subject Email subject
     * @param body Email body (plain text)
     * @throws MessagingException if email sending fails
     */
    public void sendAlert(String subject, String body) throws MessagingException {
        try {
            Message message = new MimeMessage(session);
            
            // Set headers
            message.setFrom(new InternetAddress(
                mailConfig.getFromEmail(),
                mailConfig.getFromName()
            ));
            message.setRecipients(
                Message.RecipientType.TO,
                InternetAddress.parse(Config.getInstance().getRecipientEmail())
            );
            message.setSubject(subject);
            message.setText(body);
            message.setSentDate(new Date());

            // Send message
            Transport.send(message);
            
            ConsoleLogger.log("Alert email sent successfully to: " + 
                Config.getInstance().getRecipientEmail());
        } catch (UnsupportedEncodingException e) {
            throw new MessagingException("Failed to set email headers", e);
        }
    }

    /**
     * Send a health check failure alert
     * 
     * @param url The URL that failed
     * @param failureCount Number of consecutive failures
     * @param lastError Description of the last error
     */
    public void sendHealthCheckAlert(String url, int failureCount, String lastError) {
        try {
            String subject = String.format("[WebPulse Alert] %s is DOWN", url);
            
            StringBuilder body = new StringBuilder();
            body.append("WebPulse Health Check Alert\n");
            body.append("===========================\n\n");
            body.append("URL: ").append(url).append("\n");
            body.append("Status: DOWN\n");
            body.append("Consecutive Failures: ").append(failureCount).append("\n");
            body.append("Last Error: ").append(lastError).append("\n");
            body.append("Time: ").append(new Date()).append("\n\n");
            body.append("Please investigate immediately.\n");
            
            sendAlert(subject, body.toString());
        } catch (MessagingException e) {
            System.err.println("Failed to send alert email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Send a recovery notification
     * 
     * @param url The URL that recovered
     * @param downtimeStartTime Timestamp when the server went down
     * @param recoveryTime Timestamp when the server came back up
     * @param errorCause The error that caused the downtime
     */
    public void sendRecoveryAlert(String url, long downtimeStartTime, long recoveryTime, String errorCause) {
        try {
            String subject = String.format("[WebPulse Recovery] %s is UP", url);
            
            Date downtime = new Date(downtimeStartTime);
            Date uptime = new Date(recoveryTime);
            long durationMillis = recoveryTime - downtimeStartTime;
            String duration = formatDuration(durationMillis);
            
            StringBuilder body = new StringBuilder();
            body.append("WebPulse Health Check Recovery\n");
            body.append("==============================\n\n");
            body.append("URL: ").append(url).append("\n");
            body.append("Status: UP\n\n");
            body.append("Downtime Details:\n");
            body.append("-----------------\n");
            body.append("Server went down: ").append(downtime).append("\n");
            body.append("Server came back up: ").append(uptime).append("\n");
            body.append("Total downtime: ").append(duration).append("\n");
            body.append("Error cause: ").append(errorCause).append("\n\n");
            body.append("Service has recovered.\n");
            
            sendAlert(subject, body.toString());
        } catch (MessagingException e) {
            System.err.println("Failed to send recovery email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Send batched health check failure alerts for multiple URLs
     * 
     * @param failures List of failure information
     */
    public void sendBatchHealthCheckAlert(List<FailureInfo> failures) {
        if (failures.isEmpty()) {
            return;
        }
        
        try {
            String subject;
            if (failures.size() == 1) {
                subject = String.format("[WebPulse Alert] %s is DOWN", failures.get(0).url);
            } else {
                subject = String.format("[WebPulse Alert] %d URLs are DOWN", failures.size());
            }
            
            StringBuilder body = new StringBuilder();
            body.append("WebPulse Health Check Alert\n");
            body.append("===========================\n\n");
            body.append("Multiple services have failed health checks:\n\n");
            
            for (FailureInfo failure : failures) {
                body.append("URL: ").append(failure.url).append("\n");
                body.append("  Status: DOWN\n");
                body.append("  Consecutive Failures: ").append(failure.failureCount).append("\n");
                body.append("  Last Error: ").append(failure.lastError).append("\n");
                body.append("  Time: ").append(new Date()).append("\n\n");
            }
            
            body.append("Please investigate immediately.\n");
            
            sendAlert(subject, body.toString());
        } catch (MessagingException e) {
            System.err.println("Failed to send batch alert email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Send batched recovery notifications for multiple URLs
     * 
     * @param recoveries List of recovery information
     */
    public void sendBatchRecoveryAlert(List<RecoveryInfo> recoveries) {
        if (recoveries.isEmpty()) {
            return;
        }
        
        try {
            String subject;
            if (recoveries.size() == 1) {
                subject = String.format("[WebPulse Recovery] %s is UP", recoveries.get(0).url);
            } else {
                subject = String.format("[WebPulse Recovery] %d URLs are UP", recoveries.size());
            }
            
            StringBuilder body = new StringBuilder();
            body.append("WebPulse Health Check Recovery\n");
            body.append("==============================\n\n");
            body.append("Multiple services have recovered:\n\n");
            
            for (RecoveryInfo recovery : recoveries) {
                Date downtime = new Date(recovery.downtimeStartTime);
                Date uptime = new Date(recovery.recoveryTime);
                long durationMillis = recovery.recoveryTime - recovery.downtimeStartTime;
                String duration = formatDuration(durationMillis);
                
                body.append("URL: ").append(recovery.url).append("\n");
                body.append("  Status: UP\n");
                body.append("  Server went down: ").append(downtime).append("\n");
                body.append("  Server came back up: ").append(uptime).append("\n");
                body.append("  Total downtime: ").append(duration).append("\n");
                body.append("  Error cause: ").append(recovery.errorCause).append("\n\n");
            }
            
            body.append("Services have recovered.\n");
            
            sendAlert(subject, body.toString());
        } catch (MessagingException e) {
            System.err.println("Failed to send batch recovery email: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Format duration in milliseconds to a human-readable string
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            hours = hours % 24;
            minutes = minutes % 60;
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            minutes = minutes % 60;
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            seconds = seconds % 60;
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Send a test email to verify mail service is working
     */
    public void sendTestEmail() {
        try {
            String subject = "[WebPulse Test] Email Service Test";
            
            StringBuilder body = new StringBuilder();
            body.append("WebPulse Health Monitor Email Test\n");
            body.append("===================================\n\n");
            body.append("This is a test email to verify that the email service is configured correctly.\n\n");
            body.append("Time: ").append(new Date()).append("\n\n");
            body.append("If you received this message, your email configuration is working properly.\n");
            
            sendAlert(subject, body.toString());
            ConsoleLogger.log("Test email sent successfully");
        } catch (MessagingException e) {
            System.err.println("Failed to send test email: " + e.getMessage());
            System.err.println("Please check your email configuration in config.json");
            e.printStackTrace();
        }
    }

    /**
     * Information about a service failure for batch alerts
     */
    public static class FailureInfo {
        public final String url;
        public final int failureCount;
        public final String lastError;
        
        public FailureInfo(String url, int failureCount, String lastError) {
            this.url = url;
            this.failureCount = failureCount;
            this.lastError = lastError;
        }
    }

    /**
     * Information about a service recovery for batch alerts
     */
    public static class RecoveryInfo {
        public final String url;
        public final long downtimeStartTime;
        public final long recoveryTime;
        public final String errorCause;
        
        public RecoveryInfo(String url, long downtimeStartTime, long recoveryTime, String errorCause) {
            this.url = url;
            this.downtimeStartTime = downtimeStartTime;
            this.recoveryTime = recoveryTime;
            this.errorCause = errorCause;
        }
    }
}
