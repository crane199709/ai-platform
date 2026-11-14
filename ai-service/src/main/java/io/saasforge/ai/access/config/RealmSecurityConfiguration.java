package io.saasforge.ai.access.config;

import io.saasforge.sdk.security.CurrentTenantFilter;
import io.saasforge.sdk.security.EnvelopeAccessDeniedHandler;
import io.saasforge.sdk.security.EnvelopeAuthenticationEntryPoint;
import io.saasforge.sdk.security.ForgeSecurityProperties;
import io.saasforge.sdk.security.PlatformJwtValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** 独立的路径与固定 issuer 保证两个 Realm 的 Token 不能互换。 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class RealmSecurityConfiguration {
    @Bean
    @Order(50)
    SecurityFilterChain platformSecurity(
            HttpSecurity http,
            ForgeSecurityProperties properties,
            EnvelopeAuthenticationEntryPoint entryPoint,
            EnvelopeAccessDeniedHandler denied)
            throws Exception {
        var decoder = NimbusJwtDecoder.withJwkSetUri(properties.platform().jwkSetUri())
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.platform().issuer()), new PlatformJwtValidator()));
        return http.securityMatcher("/platform/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .oauth2ResourceServer(server -> server.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied)
                        .jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    @Bean
    @Order(100)
    SecurityFilterChain tenantSecurity(
            HttpSecurity http,
            JwtDecoder decoder,
            EnvelopeAuthenticationEntryPoint entryPoint,
            EnvelopeAccessDeniedHandler denied)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(denied))
                .oauth2ResourceServer(server -> server.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied)
                        .jwt(jwt -> jwt.decoder(decoder)))
                .addFilterAfter(new CurrentTenantFilter(), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
