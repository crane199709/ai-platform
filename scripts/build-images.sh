#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
: "${AI_IMAGE_TAG:?设置不可变版本tag，例如 v0.1.0-g<commit>}"
registry=${AI_IMAGE_REGISTRY:-ai-platform}
revision=$(git rev-parse HEAD)
image_evidence="$root/target/images"
mkdir -p "$image_evidence"
runner_arch=${AI_RUNNER_ARCH:-$(node -p 'process.arch')}
case "$runner_arch" in
  arm64) docker_arch=arm64 ;;
  x64) docker_arch=amd64 ;;
  *) echo 'AI_RUNNER_ARCH只支持arm64或x64' >&2; exit 1 ;;
esac
platform="linux/$docker_arch"
[[ "$AI_IMAGE_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ && "$AI_IMAGE_TAG" != latest ]] || { echo '必须使用有效的不可变tag，不能为latest' >&2; exit 1; }
flags=(-U)
[[ "${AI_MAVEN_OFFLINE:-false}" == true ]] && flags=(-o)
[[ -n "${AI_MAVEN_REPOSITORY:-}" ]] && flags+=("-Dmaven.repo.local=$AI_MAVEN_REPOSITORY")
# 所有三工程、DSH契约与平台/E2E门禁必须先通过；此处仅打包已验收源码。
(cd ai-service && ./mvnw --batch-mode -s .mvn/settings.xml "${flags[@]}" -DskipTests package)
python3 - <<'ARTIFACT'
from pathlib import Path
from zipfile import ZipFile
jar=Path('ai-service/target/ai-service-0.1.0-SNAPSHOT.jar')
with ZipFile(jar) as archive:
 names=archive.namelist()
 assert 'BOOT-INF/classes/io/saasforge/ai/AiApplication.class' in names, '未生成可执行Spring Boot生产包'
 entries=[name for name in names if name.startswith('BOOT-INF/classes/')]
 forbidden_names=('FullStackIT','PlatformIntegrationIT','PlatformEnvironment','FakeAgentRuntime','V9001__','fullstack/')
 assert not any(any(marker in name for marker in forbidden_names) for name in entries), '生产包包含测试类或fixture'
 forbidden_values=(b'deterministic-local-model-key',b'test-provider-secret',b'fullstack-runner-service-identity')
 assert not any(any(marker in archive.read(name) for marker in forbidden_values) for name in entries if not name.endswith('/')), '生产包包含测试密钥'
 print('Production jar packaging verified:',jar)
ARTIFACT
docker build --platform "$platform" --metadata-file "$image_evidence/ai-service.json" -f deploy/ai-service.Dockerfile --build-arg "VERSION=$AI_IMAGE_TAG" --build-arg "REVISION=$revision" -t "$registry/ai-service:$AI_IMAGE_TAG" .
pnpm --filter @ai-platform/runner build
rm -rf "$root/runner/target/image"
# 宿主可以是macOS；可选native包必须按最终Linux/musl目标解析，而不是复制Darwin依赖。
pnpm --filter @ai-platform/runner deploy "$root/runner/target/image" --legacy --prod --os linux --cpu "$runner_arch" --libc musl
# deploy --prod可能裁剪工作区开发依赖；恢复正常开发工作区，不影响已隔离的生产包。
pnpm install --frozen-lockfile
docker build --platform "$platform" --metadata-file "$image_evidence/runner.json" -f deploy/runner.Dockerfile --build-arg "VERSION=$AI_IMAGE_TAG" --build-arg "REVISION=$revision" -t "$registry/runner:$AI_IMAGE_TAG" .
docker image inspect "$registry/ai-service:$AI_IMAGE_TAG" "$registry/runner:$AI_IMAGE_TAG" --format '{{.RepoTags}} {{.Id}}'
