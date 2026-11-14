#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
component=${1:-all}
case "$component" in
  service)
    flags=(-U)
    if [[ "${AI_MAVEN_OFFLINE:-false}" == true ]]; then flags=(-o); fi
    (cd ai-service && ./mvnw --batch-mode -s .mvn/settings.xml "${flags[@]}" verify)
    python3 "$root/.forge-packages/permission-catalog.py" --manifest "$root/ai-service/target/resource-manifest.json" --expected-service ai-service --policy "$root/.forge-packages/permission-policy.json" --previous "$root/.forge-packages/empty-catalog.json" --output "$root/ai-service/target/permission-catalog.json"
    ;;
  runner)
    pnpm --filter @ai-platform/runner typecheck
    pnpm --filter @ai-platform/runner build
    pnpm --filter @ai-platform/runner test
    ;;
  sub-app)
    pnpm --filter @forge/ai-sub-app format:check
    pnpm --filter @forge/ai-sub-app build
    if [[ "${CI:-false}" == true ]]; then
      pnpm --filter @forge/ai-sub-app test:visual --ignore-snapshots
    else
      pnpm --filter @forge/ai-sub-app test:visual
    fi
    ;;
  all)
    bash scripts/check.sh service
    bash scripts/check.sh runner
    bash scripts/check.sh sub-app
    ;;
  *) echo 'Usage: scripts/check.sh [all|service|runner|sub-app]' >&2; exit 1 ;;
esac
