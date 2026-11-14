package io.saasforge.ai.runner.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ai.runner")
public record RunnerProperties(
        URI baseUri,
        String serviceToken,
        @DefaultValue("130s") Duration timeout) {
    public RunnerProperties {
        if (baseUri == null
                || baseUri.getHost() == null
                || !("http".equals(baseUri.getScheme()) || "https".equals(baseUri.getScheme()))
                || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null
                || baseUri.getFragment() != null
                || serviceToken == null
                || serviceToken.isBlank()
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()) {
            throw new IllegalArgumentException("Runner endpoint, service token and timeout are required");
        }
    }

    @Override
    public String toString() {
        return "RunnerProperties[redacted]";
    }
}
