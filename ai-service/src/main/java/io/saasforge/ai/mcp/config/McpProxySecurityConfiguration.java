package io.saasforge.ai.mcp.config;

import io.saasforge.ai.mcp.filter.McpCapabilityFilter;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.sdk.security.EnvelopeAccessDeniedHandler;
import io.saasforge.sdk.security.EnvelopeAuthenticationEntryPoint;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
public class McpProxySecurityConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    SecurityFilterChain runnerToolSecurity(
            HttpSecurity http,
            RunnerCapabilityService capabilities,
            EnvelopeAuthenticationEntryPoint entry,
            EnvelopeAccessDeniedHandler denied,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
            throws Exception {
        return http.securityMatcher("/internal/runner/tools/call")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(
                        new McpCapabilityFilter(capabilities, entry, denied, errors), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entry).accessDeniedHandler(denied))
                .build();
    }
}
