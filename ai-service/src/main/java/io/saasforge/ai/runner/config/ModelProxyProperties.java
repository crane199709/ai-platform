package io.saasforge.ai.runner.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 只有部署显式允许的模型引用可以解引用；目录权限不能读取其他服务环境凭据。 */
@ConfigurationProperties("ai.model-proxy")
public record ModelProxyProperties(@DefaultValue List<String> secretReferences) {
    public ModelProxyProperties {
        secretReferences = secretReferences == null ? List.of() : List.copyOf(secretReferences);
        if (secretReferences.stream().anyMatch(name -> !name.matches("[A-Z][A-Z0-9_]{0,127}"))) {
            throw new IllegalArgumentException("Invalid model secret reference allowlist");
        }
    }
}
