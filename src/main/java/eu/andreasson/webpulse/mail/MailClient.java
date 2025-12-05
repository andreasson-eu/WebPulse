package eu.andreasson.webpulse.mail;

import java.io.UnsupportedEncodingException;
import java.util.Date;
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
        props.put("mail.smtp.starttls.enable", String.valueOf(mailConfig.isEnableTls()));
        props.put("mail.smtp.host", mailConfig.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(mailConfig.getSmtpPort()));
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");

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
            
            System.out.println("Alert email sent successfully to: " + 
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
     */
    public void sendRecoveryAlert(String url) {
        try {
            String subject = String.format("[WebPulse Recovery] %s is UP", url);
            
            StringBuilder body = new StringBuilder();
            body.append("WebPulse Health Check Recovery\n");
            body.append("==============================\n\n");
            body.append("URL: ").append(url).append("\n");
            body.append("Status: UP\n");
            body.append("Time: ").append(new Date()).append("\n\n");
            body.append("Service has recovered.\n");
            
            sendAlert(subject, body.toString());
        } catch (MessagingException e) {
            System.err.println("Failed to send recovery email: " + e.getMessage());
            e.printStackTrace();
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
            System.out.println("Test email sent successfully");
        } catch (MessagingException e) {
            System.err.println("Failed to send test email: " + e.getMessage());
            System.err.println("Please check your email configuration in config.json");
            e.printStackTrace();
        }
    }
}
