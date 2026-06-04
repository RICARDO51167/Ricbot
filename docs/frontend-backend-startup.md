# 前端后端启动指南

本文用于本地开发 Ricbot 的后端服务和 `frontend/` 下的 Vue Console。推荐先启动后端，再启动前端开发服务器。

## 环境要求

- JDK 17+
- 仓库内 Maven Wrapper：`./mvnw`
- Node.js 和 npm，建议使用 Node.js 20+
- 可选：真实模型调用需要配置 `RICBOT_API_KEY`

## 目录说明

```text
.
├── pom.xml                 # Java 后端
├── config/ricbot.config.json
├── src/main/resources/webui/index.html
└── frontend/               # Vue + Vite 前端开发工程
```

后端 `serve` 会同时提供 API、Webhook、内置 Console 页面和静态 Web UI。`frontend/` 是独立的 Vite 开发入口，适合改 Vue 页面时使用。

## 启动后端

在仓库根目录执行：

```bash
sh ./mvnw -q -DskipTests package
java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
  -c config/ricbot.config.json
```

启动成功后，终端会看到类似输出：

```text
OpenAI 兼容 API 服务已启动，监听：127.0.0.1:8000
Web Console 已启动：http://127.0.0.1:8000/console
Ricbot 正在运行，按 Ctrl+C 停止。
```

后端默认监听 `127.0.0.1:8000`。实际端口来自配置中的 `api.port`；如果 `api.port` 未配置或为 `0`，则使用 `gateway.port`，默认是 `8000`。

常用访问地址：

- `http://127.0.0.1:8000/console`：后端内置 Console
- `http://127.0.0.1:8000/app`：后端打包资源下的静态 Web UI
- `http://127.0.0.1:8000/health`：健康检查
- `http://127.0.0.1:8000/api/console/runtime`：前端开发版会访问的 Console API

## 启动前端开发服务器

打开另一个终端：

```bash
cd frontend
npm ci
npm run dev
```

Vite 默认会启动在：

```text
http://127.0.0.1:5173
```

如果 `5173` 被占用，Vite 可能会自动换到下一个可用端口，请以终端输出为准。

前端接口请求使用相对路径，例如 `/api/console/runtime`。`frontend/vite.config.ts` 已配置代理：

```text
/api         -> http://127.0.0.1:8000
/console/api -> http://127.0.0.1:8000
```

因此开发时需要保持后端服务运行，否则前端会进入 mock preview 或显示 backend unavailable。

## 推荐本地开发顺序

1. 构建并启动后端：

   ```bash
   sh ./mvnw -q -DskipTests package
   java -jar target/Ricbot-1.0-SNAPSHOT.jar serve \
     -c config/ricbot.config.json
   ```

2. 启动前端：

   ```bash
   cd frontend
   npm ci
   npm run dev
   ```

3. 打开 Vite 页面：

   ```text
   http://127.0.0.1:5173
   ```

4. 修改后端代码后，停止后端并重新执行打包和启动命令。

5. 修改前端代码后，Vite 会自动热更新页面。

## 配置和密钥

默认配置文件是：

```text
config/ricbot.config.json
```

其中模型 API key 通过环境变量读取：

```json
"api_key": "${RICBOT_API_KEY}"
```

如果只查看页面、跑本地 smoke eval 或看 mock preview，可以不配置真实 key。需要真正发起模型调用时，先设置：

```bash
export RICBOT_API_KEY="你的 API Key"
```

建议本地开发保持 `api.host` 为 `127.0.0.1`，并且不要配置 `api.bearer_token`。如果绑定到非本地地址，后端会要求配置 bearer token，但当前 `frontend/` 的开发版请求没有自动携带 Authorization header。

## 验证命令

后端测试：

```bash
sh ./mvnw -q test
```

后端打包：

```bash
sh ./mvnw -q -DskipTests package
```

前端测试：

```bash
cd frontend
npm run test
```

前端生产构建：

```bash
cd frontend
npm run build
```

## 常见问题

### 前端显示 Backend unavailable

确认后端已启动，并检查：

```text
http://127.0.0.1:8000/health
```

如果后端端口不是 `8000`，需要同步修改 `frontend/vite.config.ts` 中的代理地址。

### 端口 8000 被占用

在 `config/ricbot.config.json` 增加或修改：

```json
{
  "api": {
    "host": "127.0.0.1",
    "port": 8010
  }
}
```

然后用同一份配置重启后端，并把 `frontend/vite.config.ts` 的代理目标改为 `http://127.0.0.1:8010`。

### 后端启动但模型不可用

先运行配置诊断：

```bash
java -jar target/Ricbot-1.0-SNAPSHOT.jar config doctor \
  -c config/ricbot.config.json
```

如果报告提示 API key 缺失，设置 `RICBOT_API_KEY` 后重启后端。

### 想只看后端内置 Console

不需要启动 `frontend/`。只启动后端后访问：

```text
http://127.0.0.1:8000/console
```

### 想开发 Vue 前端

同时启动后端和前端，然后访问 Vite 地址：

```text
http://127.0.0.1:5173
```
