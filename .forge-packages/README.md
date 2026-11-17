# Forge 固定候选输入

当前三个前端 tarball 是平台提交 `54115992e00691be57b912e46593709c77c344a1` 实际打包的 `0.2.0-SNAPSHOT` 原包，未发布到目标 npm 仓库。来源、候选清单摘要与原包摘要见 [candidate.json](candidate.json)。SDK 原包与升级前一致，UI 与 kit 已更新；业务工程自身版本仍为 `0.1.0`。

根 `pnpm-workspace.yaml` 固定这些文件，`pnpm-lock.yaml` 记录完整性。替换同名 `file:` 快照后须显式刷新锁记录（`pnpm update @crane199709/ui @crane199709/sub-app-kit --filter @forge/ai-sub-app --lockfile-only`），再 `pnpm install --frozen-lockfile`；普通 install 可能继续使用旧内容地址缓存。三个包须配套核对，不以版本字符串相同推断内容相同。

Java 候选验收用 `bash scripts/verify-candidate-sdk.sh <候选目录> <新证据目录>`，先校验固定清单、全部制品及上述本地包，再排除本机全部 Forge 缓存执行 verify；实际解析的 Forge JAR/POM 摘要留在 `resolved-forge.json`。Java 不消费这些 npm 文件。常规 CI 的目标 Maven 快照消费与本地未发布候选验收分开记录，版本发布后仍需从目标仓库只读复验。
