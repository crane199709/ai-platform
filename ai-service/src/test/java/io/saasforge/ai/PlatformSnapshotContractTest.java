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
                "ai-service", "1", "io.saasforge.ai", getClass().getClassLoader());
        Files.writeString(
                Path.of("target/resource-manifest.json"),
                JsonMapper.builder().build().writeValueAsString(manifest));
        assertThat(manifest.permissions())
                .extracting(permission -> permission.code())
                .containsExactly("ai:conversation:use");
        assertThat(manifest.menus()).singleElement().satisfies(menu -> {
            assertThat(menu.app()).isEqualTo("ai");
            assertThat(menu.path()).isEqualTo("home");
        });
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
