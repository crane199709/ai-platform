# Forge 前端构建输入

三个 tarball 从 saas-forge `5a6fd0fa` 的 `0.2.0-SNAPSHOT` 前端包构建，供 sub-app-kit、SDK、UI 配套使用，未发布到 npm。根 pnpm-workspace.yaml 固定这些文件，锁文件记录完整性；升级时三个包一起重新打包。Java 不使用这些文件，CI 必须从 GitHub Maven Packages 下载已发布快照。
