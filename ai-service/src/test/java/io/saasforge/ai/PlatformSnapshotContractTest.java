package io.saasforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.manifest.ResourceManifestCollector;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlatformSnapshotContractTest {
    @Test
    void aiManifestUsesItsOwnPermissionsAndSubApp() throws Exception {
        var manifest = ResourceManifestCollector.collect(
                "ai-service", "8", "io.saasforge.ai", getClass().getClassLoader());
        Files.writeString(
                Path.of("target/resource-manifest.json"),
                JsonMapper.builder().build().writeValueAsString(manifest));
        // Sub-App 规范独立要求草稿使用 update，发布与回滚分别授权。
        assertThat(manifest.permissions())
                .extracting(permission -> permission.code())
                .containsExactlyInAnyOrder(
                        "ai:mcp:read",
                        "ai:mcp:update",
                        "ai:mcp:publish",
                        "ai:mcp:rollback",
                        "ai:configuration:manage",
                        "ai:conversation:use",
                        "ai:skill:read",
                        "ai:skill:update",
                        "ai:skill:publish",
                        "ai:skill:rollback",
                        "ai:prompt:read",
                        "ai:prompt:update",
                        "ai:prompt:publish",
                        "ai:prompt:rollback");
        assertThat(manifest.menus()).singleElement().satisfies(menu -> {
            assertThat(menu.app()).isEqualTo("ai");
            assertThat(menu.path()).isEqualTo("home");
        });
        assertThat(manifest.roles()).extracting(role -> role.code()).containsExactly("ai-user", "ai-admin");
        assertThat(manifest.roles().get(0).permissions()).containsExactly("ai:conversation:use", "ai:mcp:read");
        assertThat(manifest.roles().get(1).permissions())
                .containsExactlyInAnyOrder(
                        "ai:conversation:use",
                        "ai:mcp:read",
                        "ai:mcp:update",
                        "ai:mcp:publish",
                        "ai:mcp:rollback",
                        "ai:configuration:manage",
                        "ai:skill:read",
                        "ai:skill:update",
                        "ai:skill:publish",
                        "ai:skill:rollback",
                        "ai:prompt:read",
                        "ai:prompt:publish",
                        "ai:prompt:rollback",
                        "ai:prompt:update");
    }

    @Test
    void publishedPlanQuotaIsReadableWithoutPlatformImplementationClasses() {
        var json = JsonMapper.builder().build();
        var event = new TenantPlanChanged(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ai",
                List.of("ai:conversation:use"),
                List.of("ai"),
                null,
                null,
                1,
                5_000_000_000L);
        assertThat(json.readValue(json.writeValueAsString(event), TenantPlanChanged.class))
                .isEqualTo(event);
        assertThat(json.readValue("{\"planCode\":\"legacy\",\"version\":1}", TenantPlanChanged.class)
                        .tokenQuota())
                .isNull();
    }
}
