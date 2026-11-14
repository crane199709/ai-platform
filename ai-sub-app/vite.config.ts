import { defineSubAppConfig } from '@crane199709/sub-app-kit/vite'

// 宿主只代理用户请求；后端服务凭证由各服务独立注入，约定见 docs/standards/sub-app.md。
// 构建产物暴露 ./sub-app 的 remoteEntry.js，入口地址登记到 App Registry。
export default defineSubAppConfig({
  appCode: 'ai',
  port: 5180,
})
