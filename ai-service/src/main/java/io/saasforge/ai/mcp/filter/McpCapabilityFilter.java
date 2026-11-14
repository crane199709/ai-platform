package io.saasforge.ai.mcp.filter;

import io.saasforge.ai.mcp.model.McpToolCallRequest;
import io.saasforge.ai.mcp.service.McpErrorCode;
import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.EnvelopeAccessDeniedHandler;
import io.saasforge.sdk.security.EnvelopeAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.json.JsonMapper;

/** 精确工具代理链先验证能力并恢复真实身份，成员数据权限不会落入 SDK 的系统放行分支。 */
public class McpCapabilityFilter extends OncePerRequestFilter {
    private final RunnerCapabilityService capabilities;
    private final EnvelopeAuthenticationEntryPoint entry;
    private final EnvelopeAccessDeniedHandler denied;
    private final HandlerExceptionResolver errors;
    private final JsonMapper json = JsonMapper.builder().build();

    public McpCapabilityFilter(
            RunnerCapabilityService capabilities,
            EnvelopeAuthenticationEntryPoint entry,
            EnvelopeAccessDeniedHandler denied,
            HandlerExceptionResolver errors) {
        this.capabilities = capabilities;
        this.entry = entry;
        this.denied = denied;
        this.errors = errors;
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] bytes = request.getInputStream().readNBytes(65537);
        McpToolCallRequest body;
        try {
            if (bytes.length > 65536) {
                throw new IllegalArgumentException();
            }
            body = json.readValue(bytes, McpToolCallRequest.class);
            if (body.name() == null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException error) {
            errors.resolveException(request, response, null, new ForgeException(McpErrorCode.INVALID_CATALOG));
            return;
        }
        RunnerCapabilityService.GrantedContext granted;
        try {
            String authorization = request.getHeader("Authorization");
            String token =
                    authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
            granted = capabilities.authorize(
                    token,
                    UUID.fromString(request.getHeader("X-AI-Tenant-Id")),
                    UUID.fromString(request.getHeader("X-AI-Session-Id")),
                    "tool:" + body.name());
        } catch (ForgeException error) {
            if (error.errorCode().httpStatus() == 403) {
                denied.handle(request, response, new AccessDeniedException("Runner scope denied"));
            } else {
                entry.commence(request, response, new InsufficientAuthenticationException("Invalid Runner capability"));
            }
            return;
        } catch (IllegalArgumentException | NullPointerException error) {
            entry.commence(request, response, new InsufficientAuthenticationException("Invalid Runner capability"));
            return;
        }
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(granted.authentication());
        SecurityContextHolder.setContext(context);
        var cached = new CachedRequest(request, bytes);
        new RequestAttributeSecurityContextRepository().saveContext(context, cached, response);
        cached.setAttribute(RunnerCapabilityService.REQUEST_ATTRIBUTE, granted);
        try (var ignored = CurrentTenant.bind(granted.tenant().tenantId())) {
            chain.doFilter(cached, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static final class CachedRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;

        CachedRequest(HttpServletRequest request, byte[] bytes) {
            super(request);
            this.bytes = bytes;
        }

        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                public int read() {
                    return input.read();
                }

                public boolean isFinished() {
                    return input.available() == 0;
                }

                public boolean isReady() {
                    return true;
                }

                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Blocking JSON request");
                }
            };
        }
    }
}
