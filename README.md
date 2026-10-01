# Roamly 智能旅行规划 Agent

`travel-agent` 是一个前后端一体的单仓项目：Vue 3 前端位于 `frontend/`，Java 17 + Spring Boot 后端和全部 Maven 模块位于仓库根目录。前后端 Git 历史均保留在当前仓库中。

## 文档入口

- [当前 API 合约](doc/API-CURRENT.md)：按 Controller 与 `frontend/src/api` 对齐的接口索引、请求体、SSE、权限和版权治理规则。
- [当前架构说明](doc/ARCHITECTURE-CURRENT.md)：前后端边界、Maven 模块、数据所有权与可靠性约束。
- [核心请求链路](doc/REQUEST-FLOWS.md)：规划、SSE、笔记编辑、社区复制和举报审核时序。
- [部署说明](doc/DEPLOYMENT.md)：本地与容器化部署参考。
- [扩展 API 示例](API.md)：更完整的请求示例；若与当前合约冲突，以源码和 `doc/API-CURRENT.md` 为准。
- [前端说明](frontend/README.md)：页面路由、Vite 代理和编辑器数据边界。
- [测试收尾报告](doc/TEST_REPORT.md)：2026-09-27 后端测试、前端构建、迁移验证和剩余验收门禁。
- [双层 Loop 架构 PRD](doc/DUAL_LOOP_AI_APPLICATION_PRD.md)：内层旅行规划业务闭环、外层回归控制面、质量门禁、修复、灰度和回滚设计。

## 项目结构

```text
travel-agent/
├── frontend/                      # Vue 3 + Vite 前端
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── vite.config.js
├── travel-common/                 # 公共响应、异常、限流和工具
├── travel-module-auth-biz/        # 认证、Token 和邮箱验证码
├── travel-module-user-biz/        # 用户、偏好、社区和版权治理
├── travel-module-note-biz/        # 数据库笔记与图片
├── travel-module-agent-biz/       # 对话、问卷 Agent 和工具编排
├── travel-module-itinerary-biz/   # 结构化旅行计划
├── travel-module-mcp-biz/         # MCP 协议、客户端和工具服务
├── travel-a2a-runtime/             # A2A Host 与子 Agent
├── travel-web/                     # 浏览器面向的聚合服务（8080）
├── data/                           # Schema、migration 和种子数据
├── doc/                            # API、架构、链路与部署文档
├── docker-compose.yml
└── pom.xml
```

## 本地开发

### 环境要求

| 组件 | 最低版本 | 推荐版本 |
|---|---:|---:|
| JDK | 17 | 17 LTS |
| Maven | 3.8 | 3.9+ |
| Node.js | 18 | 20 LTS |
| MySQL | 8.0 | 8.0.33+ |
| Redis | 6 | 7+ |

### 1. 配置后端

```bash
cp .env.example .env
```

按需填写数据库、Redis、邮箱、天气、高德和 LLM 配置。新数据库先执行 `data/schema_clean.sql`；已有数据库只执行对应 migration，不要重复运行包含 `DROP TABLE` 的纯净 Schema。

### 2. 启动后端

```bash
mvn spring-boot:run -pl travel-web -am
```

也可使用当前 macOS 本地启动脚本：

```bash
./start-backend.sh
```

后端聚合入口默认是 `http://localhost:8080`。

### 3. 启动前端

另开一个终端，在仓库根目录运行：

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1
```

前端地址为 `http://localhost:5173`。Vite 会将 `/api`、`/a2a` 和 `/uploads` 转发到 `http://localhost:8080`。

## 构建与验证

```bash
# 后端测试
mvn test

# 前端类型检查和生产构建
npm --prefix frontend ci
npm --prefix frontend run build

# 后端回归、前端构建并生成带 Git 基线的验收证据
./scripts/acceptance-loop.sh
```

登录后重点回归 `/explore`、`/chat`、`/notes`、`/profile` 与 `/users/search`。SSE 规划需同时验证增量文字、外部数据告警、取消和重试状态。

## Docker Compose

```bash
cp .env.example .env
docker compose up --build
```

容器前端默认发布到 `http://localhost:3000`，并由 Nginx 把 `/api`、`/a2a`、`/uploads` 转发给 `travel-web:8080`。

## 主要技术栈

- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus。
- 后端：Java 17、Spring Boot 3.2.4、MyBatis-Plus、MySQL、Redis。
- Agent：Spring AI、A2A 编排、MCP 天气/POI/餐饮/预算工具、SSE 流式输出。

## 数据边界

- 数据库笔记通过 `/api/notes` 保存，可分享和发布。
- 本地文件工作区只保存路径、文件句柄和编辑快照；只有用户主动“写回文件”才修改源文件。
- 社区复制和版权追踪使用帖子 ID，不使用聊天 session。
- 发布前按相似度与信誉分进入自动放行、Agent 自动退回或人工审核流程。
