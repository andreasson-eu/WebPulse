# WebPulse

A Java-based health check monitoring system that monitors websites and sends email alerts when services go down.

## Features

- **Automatic Health Monitoring**: Checks configured URLs every 5 minutes
- **Smart Failure Detection**: 
  - Detects HTTP non-200 responses
  - Detects nginx default backend pages
- **Escalation System**: 
  - Switches to 1-minute checks after first failure
  - Sends email alert after 5 consecutive failures
- **Email Notifications**: 
  - Alert emails when services go down
  - Alert emails is sent every 24 hour until serice is recoverd 
  - Recovery emails when services come back online
- **Gmail Integration**: Uses Gmail SMTP for sending notifications
- **Configurable**: All settings managed through `config.json`

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Gmail account with App Password enabled

## Setup

### 1. Configure Gmail App Password

To use Gmail for sending emails, you need to create an App Password:

1. Go to your Google Account settings
2. Select Security
3. Under "Signing in to Google," select "2-Step Verification" (enable if not already)
4. At the bottom, select "App passwords"
5. Select "Mail" and your device
6. Copy the generated 16-character password

### 2. Create Configuration File

Create `config.json` with your settings

```json
{
  "mail": {
    "smtp_host": "smtp.gmail.com",
    "smtp_port": 587,
    "username": "your-email@gmail.com",
    "password": "your-16-char-app-password",
    "from_email": "your-email@gmail.com",
    "from_name": "WebPulse Health Monitor",
    "enable_tls": true
  },
  "urls": [
    "https://mail.andreasson.eu",
    "https://andreasson.eu",
    "https://crumbleworld.com"
  ],
  "recipient_email": "alerts@andreasson.eu",
  "check_interval_minutes": 5,
  "failure_threshold": 5
}
```

**Configuration Options:**
- `smtp_host`: Gmail SMTP server (smtp.gmail.com)
- `smtp_port`: Port 587 for TLS
- `username`: Your Gmail address
- `password`: Your Gmail App Password (NOT your regular password)
- `from_email`: Email address for the sender
- `from_name`: Display name for emails
- `urls`: List of URLs to monitor
- `recipient_email`: Email address to receive alerts
- `check_interval_minutes`: Normal check interval (default: 5)
- `failure_threshold`: Number of failures before alerting (default: 5)

## Building

Build the project using Maven:

```bash
mvn clean package
```

This creates `target/webpulse-1.0.0.jar`

## Running

Run the application:

```bash
java -jar target/webpulse-1.0.0.jar
```

Or specify a custom config file:

```bash
java -jar target/webpulse-1.0.0.jar /path/to/config.json
```

## How It Works

1. **Normal Operation**: WebPulse checks each configured URL every 5 minutes
2. **First Failure**: If a URL fails (non-200 or nginx default backend), it switches to 1-minute checks
3. **Escalation**: After 5 consecutive failures, an alert email is sent
4. **Continued Monitoring**: Checks continue every minute while in failure state
5. **Recovery**: When the service recovers, a recovery email is sent and normal 5-minute checks resume

## Project Structure

```
WebPulse/
├── src/main/java/eu/andreasson/webpulse/
│   ├── Application.java           # Main entry point
│   ├── config/
│   │   └── Config.java            # Configuration singleton
│   ├── mail/
│   │   └── MailClient.java        # Email notification client
│   └── monitor/
│       └── WebPulse.java          # Health check monitor
├── pom.xml                         # Maven configuration
├── config.json.example             # Example configuration
└── README.md                       # This file
```

## Dependencies

- **Gson**: JSON parsing for configuration
- **JavaMail**: Email sending via SMTP
- **Apache HttpClient 5**: HTTP requests for health checks

## Troubleshooting

### Email Not Sending

1. Verify you're using an App Password, not your regular Gmail password
2. Check that 2-Step Verification is enabled on your Google account
3. Verify SMTP settings in config.json
4. Check application logs for specific error messages

### URL Check Failures

1. Verify URLs are accessible from your network
2. Check for firewall or proxy restrictions
3. Ensure URLs include the protocol (https:// or http://)

## License

See LICENSE file for details.
