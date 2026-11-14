package io.saasforge.ai.runner.filter;

import io.saasforge.ai.runner.service.RunnerCapabilityService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.EnvelopeAccessDeniedHandler;
import io.saasforge.sdk.security.EnvelopeAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/** 只注册于精确模型代理链；恢复已验签真实身份后才允许目录授权与数据策略求值。 */
public class RunnerCapabilityFilter extends OncePerRequestFilter {
    private final RunnerCapabilityService capabilities;
    private final EnvelopeAuthenticationEntryPoint entry;
    private final EnvelopeAccessDeniedHandler denied;

    public RunnerCapabilityFilter(
            RunnerCapabilityService capabilities,
            EnvelopeAuthenticationEntryPoint entry,
            EnvelopeAccessDeniedHandler denied) {
        this.denied = denied;
        this.capabilities = capabilities;
        this.entry = entry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RunnerCapabilityService.GrantedContext granted;
        try {
            String authorization = request.getHeader("Authorization");
            String token =
                    authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
            granted = capabilities.authorize(
                    token,
                    UUID.fromString(request.getHeader("X-AI-Tenant-Id")),
                    UUID.fromString(request.getHeader("X-AI-Session-Id")),
                    "model:chat");
        } catch (ForgeException e) {
            if (e.errorCode().httpStatus() == 403) {
                denied.handle(request, response, new AccessDeniedException("Runner scope denied"));
            } else {
                entry.commence(request, response, new InsufficientAuthenticationException("Invalid Runner capability"));
            }
            return;
        } catch (IllegalArgumentException | NullPointerException e) {
            entry.commence(request, response, new InsufficientAuthenticationException("Invalid Runner capability"));
            return;
        }
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(granted.authentication());
        SecurityContextHolder.setContext(context);
        new RequestAttributeSecurityContextRepository().saveContext(context, request, response);
        request.setAttribute(RunnerCapabilityService.REQUEST_ATTRIBUTE, granted);
        try (var ignored = CurrentTenant.bind(granted.tenant().tenantId())) {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
