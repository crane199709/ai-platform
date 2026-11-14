#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
: "${AI_IMAGE_TAG:?设置不可变版本tag，例如 v0.1.0-g<commit>}"
registry=${AI_IMAGE_REGISTRY:-ai-platform}
revision=$(git rev-parse HEAD)
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
(cd ai-service && ./mvnw --batch-mode -s .mvn/settings.xml "${flags[@]}" -DskipTests package spring-boot:repackage)
docker build --platform "$platform" -f deploy/ai-service.Dockerfile --build-arg "VERSION=$AI_IMAGE_TAG" --build-arg "REVISION=$revision" -t "$registry/ai-service:$AI_IMAGE_TAG" .
pnpm --filter @ai-platform/runner build
rm -rf "$root/runner/target/image"
# 宿主可以是macOS；可选native包必须按最终Linux/musl目标解析，而不是复制Darwin依赖。
pnpm --filter @ai-platform/runner deploy --legacy --prod --os linux --cpu "$runner_arch" --libc musl "$root/runner/target/image"
# deploy --prod可能裁剪工作区开发依赖；恢复正常开发工作区，不影响已隔离的生产包。
pnpm install --frozen-lockfile
docker build --platform "$platform" -f deploy/runner.Dockerfile --build-arg "VERSION=$AI_IMAGE_TAG" --build-arg "REVISION=$revision" -t "$registry/runner:$AI_IMAGE_TAG" .
docker image inspect "$registry/ai-service:$AI_IMAGE_TAG" "$registry/runner:$AI_IMAGE_TAG" --format '{{.RepoTags}} {{.Id}}'
