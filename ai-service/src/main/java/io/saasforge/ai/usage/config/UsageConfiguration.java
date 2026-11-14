package io.saasforge.ai.usage.config;

import io.saasforge.sdk.core.BusinessZoneProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BusinessZoneProperties.class)
public class UsageConfiguration {}
