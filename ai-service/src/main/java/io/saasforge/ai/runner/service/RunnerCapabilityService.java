package io.saasforge.ai.runner.service;

import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.MembershipStateKeys;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.TenantContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** 能力只保存于签发实例；未知、失效或已撤销上下文一律拒绝，用户 JWT 从不发送 Runner。 */
@Service
public class RunnerCapabilityService {
    public static final String REQUEST_ATTRIBUTE = RunnerCapabilityService.class.getName() + ".context";
    private final ConcurrentHashMap<String, GrantedContext> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunnerCapabilityService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String issue(UUID sessionId, UUID turnId, UUID modelId, Set<String> scopes) {
        tokens.entrySet().removeIf(e -> !Instant.now().isBefore(e.getValue().expiresAt()));
        var tenant = TenantContext.current();
        var auth = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        var expiry = Instant.now().plusSeconds(150);
        if (auth.getToken().getExpiresAt().isBefore(expiry)) {
            expiry = auth.getToken().getExpiresAt();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new GrantedContext(tenant, auth, sessionId, turnId, modelId, Set.copyOf(scopes), expiry));
        return token;
    }

    public GrantedContext authorize(String token, UUID tenantId, UUID sessionId, String scope) {
        var context = token == null ? null : tokens.get(token);
        if (context == null
                || !Instant.now().isBefore(context.expiresAt())
                || !context.tenant().tenantId().equals(tenantId)
                || !context.sessionId().equals(sessionId)) {
            throw new ForgeException(RunnerErrorCode.INVALID_CAPABILITY);
        }
        var tenant = context.tenant();
        try {
            String version =
                    redis.opsForValue().get(MembershipStateKeys.version(tenant.tenantId(), tenant.membershipId()));
            String status = redis.opsForValue().get(MembershipStateKeys.tenantStatus(tenant.tenantId()));
            String raw = redis.opsForValue()
                    .get(MembershipStateKeys.grants(tenant.tenantId(), tenant.membershipId(), tenant.version()));
            if (!Long.toString(tenant.version()).equals(version)
                    || !MembershipStateKeys.accessible(status)
                    || raw == null
                    || !json.readValue(raw, MembershipGrants.class)
                            .permissions()
                            .contains("ai:conversation:use")) {
                throw new ForgeException(RunnerErrorCode.INVALID_CAPABILITY);
            }
        } catch (ForgeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ForgeException(RunnerErrorCode.INVALID_CAPABILITY);
        }
        if (!context.scopes().contains(scope)) {
            throw new ForgeException(RunnerErrorCode.SCOPE_DENIED);
        }
        return context;
    }

    public void revoke(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    public record GrantedContext(
            TenantContext tenant,
            Authentication authentication,
            UUID sessionId,
            UUID turnId,
            UUID modelId,
            Set<String> scopes,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "GrantedContext[redacted]";
        }
    }
}
