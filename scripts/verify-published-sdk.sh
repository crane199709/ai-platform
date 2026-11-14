#!/usr/bin/env bash
# Never let installed Forge artifacts satisfy the published-SDK acceptance check.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d "${TMPDIR:-/tmp}/ai-sdk-integration.XXXXXX")
python3 - "$work/m2" <<'PY'
from pathlib import Path
import shutil
import sys
source = Path.home() / '.m2/repository'
def ignore(directory, names):
    ignored = [name for name in names if name.endswith('.lastUpdated')]
    if Path(directory) == source / 'io':
        ignored.append('saasforge')
    return ignored
if source.exists():
    shutil.copytree(source, sys.argv[1], ignore=ignore)
PY
cd "$root/ai-service"
./mvnw --batch-mode -U -s .mvn/settings.xml "-Dmaven.repo.local=$work/m2" verify
python3 "$root/.forge-packages/permission-catalog.py" --manifest "$root/ai-service/target/resource-manifest.json" --expected-service ai-service --policy "$root/.forge-packages/permission-policy.json" --previous "$root/.forge-packages/empty-catalog.json" --output "$root/ai-service/target/permission-catalog.json"
