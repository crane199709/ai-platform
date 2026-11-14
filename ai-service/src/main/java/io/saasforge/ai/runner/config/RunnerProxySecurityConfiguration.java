package io.saasforge.ai.runner.config;

import io.saasforge.ai.runner.filter.RunnerCapabilityFilter;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.sdk.security.EnvelopeAccessDeniedHandler;
import io.saasforge.sdk.security.EnvelopeAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration(proxyBeanMethods = false)
public class RunnerProxySecurityConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    SecurityFilterChain runnerModelSecurity(
            HttpSecurity http,
            RunnerCapabilityService capabilities,
            EnvelopeAuthenticationEntryPoint entry,
            EnvelopeAccessDeniedHandler denied)
            throws Exception {
        return http.securityMatcher("/internal/runner/chat/completions")
                .csrf(c -> c.disable())
                .securityContext(
                        context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new RunnerCapabilityFilter(capabilities, entry, denied), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(entry).accessDeniedHandler(denied))
                .build();
    }
}
