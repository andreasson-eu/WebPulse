package eu.andreasson.webpulse.monitor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import eu.andreasson.webpulse.config.Config;
import eu.andreasson.webpulse.mail.MailClient;

/**
 * WebPulse health check monitor
 * Monitors URLs and sends alerts on failures
 */
public class WebPulse {
    private final Config config;
    private final MailClient mailClient;
    private final ScheduledExecutorService scheduler;
    private final Map<String, UrlMonitor> urlMonitors;

    public WebPulse() {
        this.config = Config.getInstance();
        this.mailClient = new MailClient();
        this.scheduler = Executors.newScheduledThreadPool(config.getMonitoredUrls().size());
        this.urlMonitors = new HashMap<>();
        
        // Initialize monitors for each URL
        for (String url : config.getMonitoredUrls()) {
            urlMonitors.put(url, new UrlMonitor(url));
        }
    }

    /**
     * Start monitoring all configured URLs
     */
    public void start() {
        System.out.println("WebPulse Health Monitor started");
        System.out.println("Monitoring " + config.getMonitoredUrls().size() + " URLs");
        System.out.println("Check interval: " + config.getCheckIntervalMinutes() + " minutes");
        System.out.println("Failure threshold: " + config.getFailureThreshold() + " consecutive failures");
        System.out.println("----------------------------------------");
        
        for (String url : config.getMonitoredUrls()) {
            UrlMonitor monitor = urlMonitors.get(url);
            
            // Schedule initial check immediately
            scheduler.schedule(() -> checkUrl(monitor), 0, TimeUnit.SECONDS);
        }
        
        // Schedule daily status report
        scheduler.scheduleAtFixedRate(this::printDailyStatusReport, 1, 24, TimeUnit.HOURS);
    }

    /**
     * Print daily status report of all monitored URLs
     */
    private void printDailyStatusReport() {
        System.out.println("========================================");
        System.out.println("Daily Status Report - " + new java.util.Date());
        System.out.println("========================================");
        System.out.println("Active sites being monitored: " + config.getMonitoredUrls().size());
        System.out.println();
        
        for (String url : config.getMonitoredUrls()) {
            UrlMonitor monitor = urlMonitors.get(url);
            String status = monitor.isInFailureState() ? "DOWN" : "UP";
            String statusSymbol = monitor.isInFailureState() ? "✗" : "✓";
            
            System.out.println(statusSymbol + " " + url + " - Status: " + status);
            if (monitor.isInFailureState()) {
                System.out.println("  └─ Consecutive failures: " + monitor.getConsecutiveFailures());
                System.out.println("  └─ Last error: " + monitor.getLastError());
            }
        }
        
        System.out.println("========================================");
    }

    /**
     * Stop monitoring
     */
    public void stop() {
        System.out.println("Stopping WebPulse Health Monitor...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check a URL and schedule next check based on status
     */
    private void checkUrl(UrlMonitor monitor) {
        boolean isHealthy = performHealthCheck(monitor);
        
        if (isHealthy) {
            handleHealthyResponse(monitor);
        } else {
            handleUnhealthyResponse(monitor);
        }
    }

    /**
     * Handle a healthy response
     */
    private void handleHealthyResponse(UrlMonitor monitor) {
        if (monitor.isInFailureState()) {
            // Recovery - send recovery notification
            System.out.println("[RECOVERY] " + monitor.getUrl() + " is back online");
            mailClient.sendRecoveryAlert(monitor.getUrl());
        }
        
        monitor.recordSuccess();
        
        // Schedule next check in normal interval
        long delayMinutes = config.getCheckIntervalMinutes();
        scheduler.schedule(() -> checkUrl(monitor), delayMinutes, TimeUnit.MINUTES);
    }

    /**
     * Handle an unhealthy response
     */
    private void handleUnhealthyResponse(UrlMonitor monitor) {
        monitor.recordFailure();
        
        int failureCount = monitor.getConsecutiveFailures();
        
        if (failureCount == 1) {
            // First failure - switch to 1-minute interval
            System.out.println("[WARNING] " + monitor.getUrl() + " failed first check, switching to 1-minute interval");
            scheduler.schedule(() -> checkUrl(monitor), 1, TimeUnit.MINUTES);
        } else if (failureCount < config.getFailureThreshold()) {
            // Continue checking every minute
            System.out.println("[WARNING] " + monitor.getUrl() + " failed " + failureCount + " times");
            scheduler.schedule(() -> checkUrl(monitor), 1, TimeUnit.MINUTES);
        } else if (failureCount >= config.getFailureThreshold()) {
            // Threshold reached - check if we should send alert (cooldown period)
            long currentTime = System.currentTimeMillis();
            long cooldownMillis = config.getAlertCooldownHours() * 60 * 60 * 1000L;
            long timeSinceLastAlert = currentTime - monitor.getLastAlertTime();
            
            if (monitor.getLastAlertTime() == 0 || timeSinceLastAlert >= cooldownMillis) {
                System.out.println("[ALERT] " + monitor.getUrl() + " failed " + failureCount + " consecutive times!");
                mailClient.sendHealthCheckAlert(
                    monitor.getUrl(),
                    failureCount,
                    monitor.getLastError()
                );
                monitor.updateLastAlertTime();
            } else {
                // Only print suppression message every 15 minutes
                long timeSinceLastMessage = currentTime - monitor.getLastSuppressionMessageTime();
                long fifteenMinutesMillis = 15 * 60 * 1000L;
                
                if (monitor.getLastSuppressionMessageTime() == 0 || timeSinceLastMessage >= fifteenMinutesMillis) {
                    long hoursRemaining = (cooldownMillis - timeSinceLastAlert) / (60 * 60 * 1000);
                    System.out.println("[ALERT SUPPRESSED] " + monitor.getUrl() + " still down, but cooldown active (" + hoursRemaining + "h remaining)");
                    monitor.updateLastSuppressionMessageTime();
                }
            }
            // Continue checking every minute
            scheduler.schedule(() -> checkUrl(monitor), 1, TimeUnit.MINUTES);
        }
    }

    /**
     * Perform HTTP health check on a URL
     * 
     * @param monitor The URL monitor
     * @return true if healthy, false otherwise
     */
    private boolean performHealthCheck(UrlMonitor monitor) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(monitor.getUrl());
            request.setHeader("User-Agent", "WebPulse/1.0");
            
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
                String body = EntityUtils.toString(response.getEntity());
                
                // Check for HTTP 200
                if (statusCode != 200) {
                    monitor.setLastError("HTTP " + statusCode);
                    System.out.println("[WARNING] " + monitor.getUrl() + " status code: " + statusCode);
                    return false;
                }
                
                // Check for nginx default backend (common error page)
                if (body.contains("default backend - 404") ||
                    body.contains("404 Not Found") && body.contains("nginx")) {
                    monitor.setLastError("Nginx default backend detected");
                    System.out.println("[WARNING] " + monitor.getUrl() + " returned nginx default backend page");
                    System.out.println("[WARNING] " + monitor.getUrl() + " debug: " + body);
                    return false;
                }
                
                // Success
                return true;
            }
        } catch (IOException e) {
            monitor.setLastError("Connection error: " + e.getMessage());
            System.out.println("[WARNING] " + monitor.getUrl() + " connection error: " + e.getMessage());
            return false;
        } catch (Exception e) {
            monitor.setLastError("Error: " + e.getMessage());
            System.out.println("[WARNING] " + monitor.getUrl() + " Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Monitor state for a single URL
     */
    private static class UrlMonitor {
        private final String url;
        private int consecutiveFailures;
        private String lastError;
        private boolean inFailureState;
        private long lastAlertTime;
        private long lastSuppressionMessageTime;

        public UrlMonitor(String url) {
            this.url = url;
            this.consecutiveFailures = 0;
            this.lastError = "";
            this.inFailureState = false;
            this.lastAlertTime = 0;
            this.lastSuppressionMessageTime = 0;
        }

        public String getUrl() {
            return url;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public String getLastError() {
            return lastError;
        }

        public boolean isInFailureState() {
            return inFailureState;
        }

        public void recordSuccess() {
            consecutiveFailures = 0;
            lastError = "";
            inFailureState = false;
            lastAlertTime = 0;
            lastSuppressionMessageTime = 0;
        }

        public void recordFailure() {
            consecutiveFailures++;
            inFailureState = true;
        }

        public void setLastError(String error) {
            this.lastError = error;
        }

        public long getLastAlertTime() {
            return lastAlertTime;
        }

        public void updateLastAlertTime() {
            this.lastAlertTime = System.currentTimeMillis();
        }

        public long getLastSuppressionMessageTime() {
            return lastSuppressionMessageTime;
        }

        public void updateLastSuppressionMessageTime() {
            this.lastSuppressionMessageTime = System.currentTimeMillis();
        }
    }
}
