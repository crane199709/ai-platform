#!/usr/bin/env bash
# 固定未发布候选；Java 只复用第三方缓存，前端继续使用仓库内已核对的原始 tarball。
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
candidate=${1:?用法: verify-candidate-sdk.sh <候选目录> [新证据目录]}
output=${2:-$(mktemp -d "${TMPDIR:-/tmp}/ai-candidate.XXXXXX")}
mkdir -p "$output"
output=$(cd "$output" && pwd)
python3 - "$root" "$candidate" "$output" <<'PYTHON'
from pathlib import Path
import hashlib, json, shutil, sys
import xml.etree.ElementTree as ET
root, candidate, output = map(Path, sys.argv[1:])
pinned = json.loads((root / '.forge-packages/candidate.json').read_text())
manifest_bytes = (candidate / 'candidate.json').read_bytes()
assert hashlib.sha256(manifest_bytes).hexdigest() == pinned['manifestSha256'], '候选清单与工程固定输入不同'
manifest = json.loads(manifest_bytes)
assert manifest['sourceCommit'] == pinned['sourceCommit']
assert manifest['sourceVersion'] == pinned['sourceVersion']
for name, digest in manifest['artifacts'].items():
    path = (candidate / name).resolve()
    assert path.is_relative_to(candidate.resolve()), '候选路径越界'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, f'候选内容不匹配: {name}'
for name, digest in pinned['tarballs'].items():
    assert hashlib.sha256((root / '.forge-packages' / name).read_bytes()).hexdigest() == digest, f'工程前端包与候选不匹配: {name}'
cache = output / 'm2'
assert not cache.exists(), '请使用新的证据目录，避免旧 Forge 缓存参与验收'
source = Path.home() / '.m2/repository'
def ignore(directory, names):
    return [name for name in names if name.endswith('.lastUpdated') or (Path(directory) == source / 'io' and name == 'saasforge')]
if source.exists():
    shutil.copytree(source, cache, ignore=ignore)
else:
    cache.mkdir()
settings = ET.Element('settings')
mirror = ET.SubElement(ET.SubElement(settings, 'mirrors'), 'mirror')
for key, value in {'id':'forge-candidate','mirrorOf':'forge-release','url':(candidate / 'maven-repository').resolve().as_uri()}.items():
    ET.SubElement(mirror, key).text = value
ET.ElementTree(settings).write(output / 'settings.xml', encoding='unicode')
(output / 'candidate.json').write_text(json.dumps(pinned, indent=2) + '\n')
print('候选及工程 tarball 摘要通过；Maven 缓存不含本机 Forge 制品', flush=True)
PYTHON
(cd "$root/ai-service" && ./mvnw --batch-mode -nsu -s "$output/settings.xml" "-Dmaven.repo.local=$output/m2" verify)
python3 "$root/.forge-packages/permission-catalog.py" --manifest "$root/ai-service/target/resource-manifest.json" --expected-service ai-service --policy "$root/.forge-packages/permission-policy.json" --previous "$root/.forge-packages/empty-catalog.json" --output "$root/ai-service/target/permission-catalog.json"
python3 - "$candidate" "$output" <<'PYTHON'
from pathlib import Path
import hashlib, json, sys
candidate, output = map(Path, sys.argv[1:])
manifest = json.loads((candidate / 'candidate.json').read_text())
expected = {digest for name, digest in manifest['artifacts'].items() if name.endswith(('.jar', '.pom'))}
artifacts = {}
for path in (output / 'm2/io/saasforge').rglob('*'):
    if path.is_file() and path.suffix in ('.jar', '.pom'):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest in expected, f'Maven 解析了非候选 Forge 文件: {path.name}'
        artifacts[str(path.relative_to(output / 'm2'))] = digest
assert any('/forge-sdk-scheduling/' in name for name in artifacts), '未消费 scheduling 候选'
(output / 'resolved-forge.json').write_text(json.dumps(artifacts, indent=2) + '\n')
print(f'已核对 {len(artifacts)} 个实际解析的 Forge JAR/POM，全部来自固定候选')
PYTHON
printf '候选验收完成，独立 Maven 缓存和摘要：%s\n' "$output"
