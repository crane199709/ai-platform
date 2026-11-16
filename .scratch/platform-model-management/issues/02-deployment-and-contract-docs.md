# 02: 模型凭据的部署与契约文档

**What to build:** 把主密钥接进部署、本地与 CI，并让接口契约文档与实现对齐。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `deploy/kubernetes/secrets.example.yaml` 增加 `AI_MODEL_CREDENTIAL_KEY: REPLACE_MODEL_CREDENTIAL_KEY`
- [ ] 复核 `docs/deployment.md` 的配置表与轮换说明，确保与实现一致
- [ ] 本地 `ai-service/.env.example` 增加主密钥示例；`PlatformIntegrationIT` / `FullStackIT` 用动态属性注入确定性测试值，不依赖真实凭据
- [ ] 删除 `docs/model-api.md` 顶部的“目标契约”提示，确认描述与实现一致
- [ ] 执行 `./mvnw -o -pl ai-service -am install -DskipTests`，跑 `PlatformIntegrationIT` 与 `FullStackIT`

## Comments
