#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
: "${AI_FORGE_RUNTIME_REPOSITORY:?指向包含三个真实Forge应用jar的Maven目录}"
: "${AI_FORGE_SOURCE:?指向与运行包配套的SaaS Forge工作区，用于构建真实tenant-app}"
[[ -f "$AI_FORGE_SOURCE/frontend/apps/tenant-app/package.json" ]] || { echo '缺少真实Tenant Shell源码' >&2; exit 1; }
for module in forge-iam forge-platform forge-gateway; do
  [[ -f "$AI_FORGE_RUNTIME_REPOSITORY/io/saasforge/$module/0.2.0-SNAPSHOT/$module-0.2.0-SNAPSHOT.jar" ]] || { echo "缺少运行制品: $module" >&2; exit 1; }
done
cd "$root"
out=${AI_E2E_OUTPUT:-$root/ai-service/target/fullstack}
mkdir -p "$out"
out=$(cd "$out" && pwd)
export AI_E2E_OUTPUT="$out"
python3 - "$root" "$AI_FORGE_SOURCE" "$out" <<'EVIDENCE'
import json, pathlib, subprocess, sys
ai, forge, out = sys.argv[1:]
def commit(path): return subprocess.check_output(['git','-C',path,'rev-parse','HEAD'], text=True).strip()
pathlib.Path(out,'source.json').write_text(json.dumps({'aiCommit':commit(ai),'platformCommit':commit(forge),'platformSource':forge,'dshVersion':'0.2.2-rc.1','nodeVersion':subprocess.check_output(['node','--version'],text=True).strip()}, indent=2))
EVIDENCE
export AI_E2E_TLS_KEY="$out/provider-key.pem" AI_E2E_TLS_CERT="$out/provider-cert.pem"
export AI_E2E_PROVIDER_READY="$out/provider-ready.json" AI_E2E_PROVIDER_EVIDENCE="$out/provider-evidence.json"
rm -f "$AI_E2E_PROVIDER_READY"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -keyout "$AI_E2E_TLS_KEY" -out "$AI_E2E_TLS_CERT" -subj /CN=localhost -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' > "$out/tls.log" 2>&1
rm -f "$out/trust.p12"
keytool -importcert -noprompt -alias local-e2e -file "$AI_E2E_TLS_CERT" -keystore "$out/trust.p12" -storepass local-e2e-trust >> "$out/tls.log" 2>&1
node e2e/fullstack/provider.mjs > "$out/provider.log" 2>&1 &
provider_pid=$!
trap 'kill "$provider_pid" 2>/dev/null || true' EXIT
for attempt in {1..100}; do [[ -f "$AI_E2E_PROVIDER_READY" ]] && break; kill -0 "$provider_pid"; sleep 0.1; done
[[ -f "$AI_E2E_PROVIDER_READY" ]] || { echo '本地HTTPS提供方未启动' >&2; exit 1; }
pnpm --filter @ai-platform/runner build
pnpm --filter @forge/ai-sub-app build
(cd "$AI_FORGE_SOURCE/frontend" && pnpm --filter @crane199709/sdk --filter @crane199709/ui build && pnpm --filter @forge/tenant-app build)
flags=(-U)
[[ "${AI_MAVEN_OFFLINE:-false}" == true ]] && flags=(-o)
[[ -n "${AI_MAVEN_REPOSITORY:-}" ]] && flags+=("-Dmaven.repo.local=$AI_MAVEN_REPOSITORY")
(cd ai-service && ./mvnw --batch-mode -s .mvn/settings.xml "${flags[@]}" test -Dtest=FullStackIT -DforkCount=1 "-DargLine=-Djavax.net.ssl.trustStore=$out/trust.p12 -Djavax.net.ssl.trustStorePassword=local-e2e-trust")
