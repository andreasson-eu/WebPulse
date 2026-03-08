package eu.andreasson.webpulse.monitor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import eu.andreasson.webpulse.config.Config;
import eu.andreasson.webpulse.logging.ConsoleLogger;
import eu.andreasson.webpulse.mail.MailClient;

/**
 * WebPulse health check monitor
 * Monitors URLs and sends alerts on failures
 */
public class WebPulse {
    private static final String[] INTERNET_CHECK_URLS = {
        "https://www.google.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace"
    };

    private final Config config;
    private final MailClient mailClient;
    private final ScheduledExecutorService scheduler;
    private final Map<String, UrlMonitor> urlMonitors;
    
    // Alert batching
    private final List<MailClient.FailureInfo> pendingFailureAlerts;
    private final List<MailClient.RecoveryInfo> pendingRecoveryAlerts;
    private ScheduledFuture<?> scheduledFailureBatch;
    private ScheduledFuture<?> scheduledRecoveryBatch;
    private final Object failureLock = new Object();
    private final Object recoveryLock = new Object();

    public WebPulse() {
        this.config = Config.getInstance();
        this.mailClient = new MailClient();
        this.scheduler = Executors.newScheduledThreadPool(config.getMonitoredUrls().size());
        this.urlMonitors = new HashMap<>();
        this.pendingFailureAlerts = new ArrayList<>();
        this.pendingRecoveryAlerts = new ArrayList<>();
        
        // Initialize monitors for each URL
        for (String url : config.getMonitoredUrls()) {
            urlMonitors.put(url, new UrlMonitor(url));
        }
    }

    /**
     * Start monitoring all configured URLs
     */
    public void start() {
        ConsoleLogger.log("WebPulse Health Monitor started");
        ConsoleLogger.log("Monitoring " + config.getMonitoredUrls().size() + " URLs");
        ConsoleLogger.log("Check interval: " + config.getCheckIntervalMinutes() + " minutes");
        ConsoleLogger.log("Failure threshold: " + config.getFailureThreshold() + " consecutive failures");
        ConsoleLogger.log("----------------------------------------");
        
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
        ConsoleLogger.log("========================================");
        ConsoleLogger.log("Daily Status Report - " + new java.util.Date());
        ConsoleLogger.log("========================================");
        ConsoleLogger.log("Active sites being monitored: " + config.getMonitoredUrls().size());
        ConsoleLogger.log();
        
        for (String url : config.getMonitoredUrls()) {
            UrlMonitor monitor = urlMonitors.get(url);
            String status = monitor.isInFailureState() ? "DOWN" : "UP";
            String statusSymbol = monitor.isInFailureState() ? "✗" : "✓";
            
            ConsoleLogger.log(statusSymbol + " " + url + " - Status: " + status);
            if (monitor.isInFailureState()) {
                ConsoleLogger.log("  └─ Consecutive failures: " + monitor.getConsecutiveFailures());
                ConsoleLogger.log("  └─ Last error: " + monitor.getLastError());
            }
        }
        
        ConsoleLogger.log("========================================");
    }

    /**
     * Stop monitoring
     */
    public void stop() {
        ConsoleLogger.log("Stopping WebPulse Health Monitor...");
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
            int minFailuresForRecoveryAlert = Math.max(1, config.getFailureThreshold() - 1);
            int failureCountBeforeRecovery = monitor.getConsecutiveFailures();

            if (failureCountBeforeRecovery >= minFailuresForRecoveryAlert) {
                // Recovery - send recovery notification for significant outages
                ConsoleLogger.log("[RECOVERY] " + monitor.getUrl() + " is back online");
                long downtimeStart = monitor.getDowntimeStartTime();
                long recoveryTime = System.currentTimeMillis();
                String downError = monitor.getLastError();
                queueRecoveryAlert(monitor.getUrl(), downtimeStart, recoveryTime, downError);
            } else {
                ConsoleLogger.log(
                    "[RECOVERY SUPPRESSED] " + monitor.getUrl() +
                    " recovered after " + failureCountBeforeRecovery +
                    " failure(s), below threshold " + minFailuresForRecoveryAlert
                );
            }
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
        if (!hasInternetAccess()) {
            ConsoleLogger.log("[INTERNET] Global connectivity check failed; skipping downtime count for " + monitor.getUrl());
            scheduler.schedule(() -> checkUrl(monitor), 1, TimeUnit.MINUTES);
            return;
        }

        monitor.recordFailure();
        
        int failureCount = monitor.getConsecutiveFailures();
        
        if (failureCount == 1) {
            // First failure - switch to 1-minute interval
            ConsoleLogger.log("[WARNING] " + monitor.getUrl() + " failed first check, switching to 1-minute interval");
            scheduler.schedule(() -> checkUrl(monitor), 1, TimeUnit.MINUTES);
        } else if (failureCount < config.getFailureThreshold()) {
            // Continue checking every minute
            ConsoleLogger.log("[WARNING] " + monitor.getUrl() + " failed " + failureCount + " times");
            scheduler.schedule(() -> checkUrl(monitor), 1, TimeUnit.MINUTES);
        } else if (failureCount >= config.getFailureThreshold()) {
            // Threshold reached - check if we should send alert (cooldown period)
            long currentTime = System.currentTimeMillis();
            long cooldownMillis = config.getAlertCooldownHours() * 60 * 60 * 1000L;
            long timeSinceLastAlert = currentTime - monitor.getLastAlertTime();
            
            if (monitor.getLastAlertTime() == 0 || timeSinceLastAlert >= cooldownMillis) {
                ConsoleLogger.log("[ALERT] " + monitor.getUrl() + " failed " + failureCount + " consecutive times!");
                queueFailureAlert(
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
                    ConsoleLogger.log("[ALERT SUPPRESSED] " + monitor.getUrl() + " still down, but cooldown active (" + hoursRemaining + "h remaining)");
                    monitor.updateLastSuppressionMessageTime();
                }
            }
            // Continue checking every minute
            scheduler.schedule(() -> checkUrl(monitor), 1, TimeUnit.MINUTES);
        }
    }

    /**
     * Verify that global internet connectivity is available.
     * Downtime is only counted when at least one global endpoint is reachable.
     */
    private boolean hasInternetAccess() {
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofSeconds(5))
            .setResponseTimeout(Timeout.ofSeconds(5))
            .build();

        try (CloseableHttpClient httpClient = HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build()) {
            for (String connectivityUrl : INTERNET_CHECK_URLS) {
                try (CloseableHttpResponse response = httpClient.execute(new HttpGet(connectivityUrl))) {
                    int statusCode = response.getCode();
                    if (statusCode >= 200 && statusCode < 400) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        return false;
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
                    ConsoleLogger.log("[WARNING] " + monitor.getUrl() + " status code: " + statusCode);
                    return false;
                }
                
                // Check for nginx default backend (common error page)
                if (body.contains("default backend - 404") ||
                    body.contains("404 Not Found") && body.contains("nginx")) {
                    monitor.setLastError("Nginx default backend detected");
                    ConsoleLogger.log("[WARNING] " + monitor.getUrl() + " returned nginx default backend page");
                    ConsoleLogger.log("[WARNING] " + monitor.getUrl() + " debug: " + body);
                    return false;
                }
                
                // Success
                return true;
            }
        } catch (IOException e) {
            monitor.setLastError("Connection error: " + e.getMessage());
            ConsoleLogger.log("[WARNING] " + monitor.getUrl() + " connection error: " + e.getMessage());
            return false;
        } catch (Exception e) {
            monitor.setLastError("Error: " + e.getMessage());
            ConsoleLogger.log("[WARNING] " + monitor.getUrl() + " Error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Queue a failure alert for batched sending
     */
    private void queueFailureAlert(String url, int failureCount, String lastError) {
        synchronized (failureLock) {
            pendingFailureAlerts.add(new MailClient.FailureInfo(url, failureCount, lastError));
            
            // If no batch send is scheduled, schedule one
            if (scheduledFailureBatch == null || scheduledFailureBatch.isDone()) {
                int delaySeconds = config.getBatchFailureAlertSeconds();
                ConsoleLogger.log("[BATCH] Scheduling failure alert batch send in " + delaySeconds + " seconds");
                scheduledFailureBatch = scheduler.schedule(
                    this::sendBatchedFailureAlerts,
                    delaySeconds,
                    TimeUnit.SECONDS
                );
            } else {
                ConsoleLogger.log("[BATCH] Added to pending failure alerts (will send with existing batch)");
            }
        }
    }

    /**
     * Queue a recovery alert for batched sending
     */
    private void queueRecoveryAlert(String url, long downtimeStartTime, long recoveryTime, String errorCause) {
        synchronized (recoveryLock) {
            pendingRecoveryAlerts.add(new MailClient.RecoveryInfo(url, downtimeStartTime, recoveryTime, errorCause));
            
            // If no batch send is scheduled, schedule one
            if (scheduledRecoveryBatch == null || scheduledRecoveryBatch.isDone()) {
                int delaySeconds = config.getBatchRecoveryAlertSeconds();
                ConsoleLogger.log("[BATCH] Scheduling recovery alert batch send in " + delaySeconds + " seconds");
                scheduledRecoveryBatch = scheduler.schedule(
                    this::sendBatchedRecoveryAlerts,
                    delaySeconds,
                    TimeUnit.SECONDS
                );
            } else {
                ConsoleLogger.log("[BATCH] Added to pending recovery alerts (will send with existing batch)");
            }
        }
    }

    /**
     * Send all pending failure alerts as a batch
     */
    private void sendBatchedFailureAlerts() {
        synchronized (failureLock) {
            if (!pendingFailureAlerts.isEmpty()) {
                ConsoleLogger.log("[BATCH] Sending batched failure alerts for " + pendingFailureAlerts.size() + " URL(s)");
                mailClient.sendBatchHealthCheckAlert(new ArrayList<>(pendingFailureAlerts));
                pendingFailureAlerts.clear();
            }
            scheduledFailureBatch = null;
        }
    }

    /**
     * Send all pending recovery alerts as a batch
     */
    private void sendBatchedRecoveryAlerts() {
        synchronized (recoveryLock) {
            if (!pendingRecoveryAlerts.isEmpty()) {
                ConsoleLogger.log("[BATCH] Sending batched recovery alerts for " + pendingRecoveryAlerts.size() + " URL(s)");
                mailClient.sendBatchRecoveryAlert(new ArrayList<>(pendingRecoveryAlerts));
                pendingRecoveryAlerts.clear();
            }
            scheduledRecoveryBatch = null;
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
        private long downtimeStartTime;

        public UrlMonitor(String url) {
            this.url = url;
            this.consecutiveFailures = 0;
            this.lastError = "";
            this.inFailureState = false;
            this.lastAlertTime = 0;
            this.lastSuppressionMessageTime = 0;
            this.downtimeStartTime = 0;
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
            downtimeStartTime = 0;
        }

        public void recordFailure() {
            consecutiveFailures++;
            // Record the downtime start time on the first failure
            if (consecutiveFailures == 1 && downtimeStartTime == 0) {
                downtimeStartTime = System.currentTimeMillis();
            }
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

        public long getDowntimeStartTime() {
            return downtimeStartTime;
        }
    }
}
