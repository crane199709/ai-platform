package io.saasforge.ai.access.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Management 映射仅在独立内网端口发布，部署网络策略限制采集和探针来源。 */
@Configuration(proxyBeanMethods = false)
public class ManagementSecurityConfiguration {
    @Bean
    @Order(-50)
    SecurityFilterChain managementSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/actuator/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .build();
    }
}
