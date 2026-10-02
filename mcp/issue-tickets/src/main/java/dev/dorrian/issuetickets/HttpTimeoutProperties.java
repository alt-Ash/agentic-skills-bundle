package dev.dorrian.issuetickets;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timeouts for outbound ticket-provider HTTP calls. Defaults live here so no properties entry is
 * required; override with {@code issue-tickets.http.connect-timeout} / {@code request-timeout}.
 */
@ConfigurationProperties(prefix = "issue-tickets.http")
public record HttpTimeoutProperties(
    @DefaultValue("10s") Duration connectTimeout,
    @DefaultValue("30s") Duration requestTimeout
) {
}
