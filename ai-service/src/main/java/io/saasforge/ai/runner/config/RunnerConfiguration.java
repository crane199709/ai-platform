package io.saasforge.ai.runner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RunnerProperties.class, ModelProxyProperties.class})
public class RunnerConfiguration {}
