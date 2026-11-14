package io.saasforge.ai.runner.service;

import java.io.InputStream;
import java.net.URI;

/** 唯一外部模型传输边界；生产实现固定 HTTPS、禁止重定向，不记录请求或凭据。 */
public interface ProviderTransport {
    Response open(URI endpoint, String secret, String body);

    record Response(int status, String contentType, InputStream body) {}
}
