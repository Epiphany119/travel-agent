# Roamly 当前 API 合约

> 版本：2026-09-04
> 适用范围：当前 `travel-agent` 单仓源码；Vue 前端位于 `frontend/`，Spring Boot 后端位于根目录 Maven 模块。
> 这份文档以 Controller、Service 和前端 `frontend/src/api` 的实际实现为准；发现文档与代码不一致时，先修正代码或在这里记录迁移说明。

## 1. 运行地址与通用约定

### 1.1 地址

| 场景 | 地址 | 说明 |
|---|---|---|
| 前端开发 | `http://localhost:5173` | Vite 页面；`/api`、`/a2a`、`/uploads` 代理到后端 |
| Web 聚合服务 | `http://localhost:8080` | `travel-web` 启动入口，聚合认证、用户、笔记、Agent、行程和 A2A Controller |
| A2A 独立配置 | `8086` | `travel-a2a-runtime` 的独立运行配置；前端当前默认走 8080 的聚合入口 |
| MCP 独立配置 | `8081`–`8085` | 天气、POI、餐饮、预算、行程模块的独立服务配置；是否拆进同一进程取决于部署方式 |

本地前端的请求基地址是 `/api`，因此浏览器不会把开发机端口写进业务代码。A2A 先以 `POST /a2a/tasks` 创建，再以 `GET /a2a/tasks/{taskId}/stream` 订阅，图片访问使用 `/uploads/...`。

### 1.2 鉴权

登录或注册成功后，前端把 Token 放进：

```http
Authorization: Bearer <token>
```

`frontend/src/api/index.ts` 会自动注入 Token；收到 HTTP 401 时清理本地登录状态并跳转 `/auth`。

当前版本仍有一部分历史接口使用 `userId`、`reporterId`、`reviewerId` 等请求参数识别操作者，并保留 `user_001` 默认值。这是兼容旧数据的过渡实现，不应视为最终权限模型。新接口应优先从已验证的 Token 解析用户身份，并逐步移除客户端可控的身份参数。

### 1.3 响应格式

认证、用户、笔记、Agent 业务接口使用：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": 1725000000000
}
```

`ApiResult` 的 `code` 约定：`200` 成功，`400` 参数或业务校验失败，`401` 未登录，`403` 无权限，`404` 资源不存在，`409` 状态冲突，`429` 频率限制，`500` 未处理异常。旅行旧接口、POI 图片接口和 A2A 状态接口部分返回裸 `Map`/DTO，不要按 `ApiResult` 二次解包。

### 1.4 SSE 约定

SSE 请求必须设置 `Accept: text/event-stream`，浏览器端用 `AbortController` 取消。代理层关闭响应缓冲，服务端在完成或异常时关闭 `SseEmitter`。

| 流 | 事件 | 用途 |
|---|---|---|
| `/a2a/tasks/stream` | `task_update` | 任务开始、Agent 优化状态 |
|  | `tool_call` | 天气、POI、餐饮、预算或 Agent 工具调用 |
|  | `tool_result` | 工具成功或数据告警 |
|  | `token` | Markdown 计划正文 |
|  | `task_done` | 结构化计划与完成状态 |
|  | `error` | 错误信息 |
| `/api/agent/questionnaire/{id}/answer` | `parsed` | 当前回答被规范化后的字段 |
|  | `next_question` | 下一步问题 |
|  | `tool_call` / `tool_result` | 问卷过程中的工具调用 |
|  | `plan` | 五步问卷完成后的结构化计划 |
|  | `error` | 处理失败 |

## 2. 认证 API `/api/auth`

| 方法 | 路径 | 登录 | 说明 |
|---|---|---:|---|
| `POST` | `/api/auth/register` | 否 | 用户名密码注册；可附邮箱和邮箱验证码 |
| `POST` | `/api/auth/login` | 否 | 用户名密码登录 |
| `POST` | `/api/auth/email/send-code` | 否 | 发送邮箱验证码 |
| `POST` | `/api/auth/email/login` | 否 | 邮箱验证码登录；不存在时自动创建账号 |
| `POST` | `/api/auth/bind-email` | 是 | 为当前账号绑定邮箱 |
| `POST` | `/api/auth/unbind-email` | 是 | 解绑邮箱 |
| `POST` | `/api/auth/logout` | 建议 | 失效当前会话 |
| `GET` | `/api/auth/verify` | 否 | 返回 `{ authenticated, userId? }` |

注册、登录和邮箱登录返回 `AuthTokenResponse`：`token`、`userId`、`username`、`expiresIn`。

## 3. 个人笔记 API `/api/notes`

这是“数据库笔记”链路，数据落在 `note_document`，内容由笔记编辑器维护；不要把本地源文件快照误认为数据库笔记。

AI 计划保存使用 `POST /api/user/ai-plans/save`。该接口从 Token 获取用户，不接受客户端提供的操作者身份，并在一个事务中保存旅行笔记、灵感或旅程及旅程点位。

| 方法 | 路径 | 主要参数 | 说明 |
|---|---|---|---|
| `GET` | `/api/notes?userId=...` | `userId` 可选 | 获取用户笔记列表，不含完整内容块 |
| `GET` | `/api/notes/{id}?userId=...` | `id` | 获取单篇完整笔记 |
| `GET` | `/api/notes/share/{token}` | `token` | 按分享令牌查看链接可见笔记 |
| `POST` | `/api/notes?userId=...` | JSON | 创建笔记 |
| `PUT` | `/api/notes/{id}?userId=...` | JSON | 属性和内容整体覆盖更新 |
| `DELETE` | `/api/notes/{id}?userId=...` | `id` | 删除笔记 |
| `POST` | `/api/notes/upload?userId=...` | multipart `file` | 上传笔记图片，返回 `{ url }` |

创建/更新的核心字段：

```json
{
  "title": "杭州慢旅行",
  "destination": "杭州",
  "coverUrl": "/uploads/note/user_001/cover.jpg",
  "visibility": "private",
  "themeJson": "{\"bg\":\"#f7f3ea\",\"fg\":\"#1d2b27\",\"accent\":\"#164e42\"}",
  "sourceSocialNoteId": 12,
  "content": "# 西湖边的一天\n\n正文……"
}
```

`sourceSocialNoteId` 用于保留社区复制来源；本地编辑工作区的源文件不会调用这些接口写入数据库。

## 4. 用户、资料与社区 API `/api/user`

### 4.1 用户资料、搜索和偏好

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/user/users/search?q=关键词` | 按公开 ID、昵称或内部用户 ID 模糊搜索，最多 20 条 |
| `GET` | `/api/user/users/{userId}/profile` | 获取对外公开资料；不返回旅行偏好 |
| `POST` | `/api/user/users/{id}/friend-request?from=...` | 发送好友申请，body 可含 `message` |
| `GET` | `/api/user/preferences?userId=...` | 获取偏好，并合并认证表中的邮箱/用户名 |
| `PUT` | `/api/user/preferences` | 保存偏好；`userId` 在 body 中，缺省为 `user_001` |
| `GET` | `/api/user/nickname?userId=...` | 获取昵称 |
| `PUT` | `/api/user/nickname?userId=...` | body `{ "nickname": "Kaoyanjuan" }` |
| `GET` | `/api/user/avatar?userId=...` | 获取头像相对路径 |
| `POST` | `/api/user/avatar?userId=...` | multipart `file` 上传头像 |
| `GET` | `/api/user/geocode?address=...` | 地理编码占位接口；当前返回坐标 0 |
| `POST` | `/api/user/upload?category=...` | 通用图片上传 |
| `GET` | `/api/user/test` | 用户模块连通性检查 |

手机号属于 `user_travel_preference.phone`。保存资料时前端必须把当前用户的 `userId` 一并放在 `PUT /api/user/preferences` body 中；刷新时通过 `GET /api/user/preferences?userId=...` 读取，不能只依赖前端内存。

### 4.2 社区帖子与互动

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/user/social/notes?page=0&size=20&q=&tag=&ownerId=` | 社区公开帖子列表；只返回 public + published + approved |
| `GET` | `/api/user/social/notes/{id}` | 公开帖子详情 |
| `POST` | `/api/user/social/notes` | 发布社区帖子；发布前进入版权相似度和信誉分判定 |
| `PUT` | `/api/user/social/notes/{id}?userId=...` | 仅原作者可以更新；更新会生成新的 `stateCode` |
| `POST` | `/api/user/social/notes/{id}/copy?userId=...` | 复制为当前用户私有旅行笔记 |
| `POST` | `/api/user/social/notes/{id}/reaction?type=like\|favorite&userId=...` | 点赞或收藏，再次调用可取消 |
| `GET` | `/api/user/social/notes/{id}/comments` | 评论列表，返回昵称和头像 |
| `POST` | `/api/user/social/notes/{id}/comments?userId=...` | body `{ "content": "..." }` |
| `POST` | `/api/user/social/notes/{id}/reports?reporterId=...` | 提交版权举报，重复举报人/帖子/类型只记录一次 |
| `GET` | `/api/user/reputation?userId=...` | 获取信誉分和发布审核阈值 |

### 4.3 复制、协作 PR 和平台审核

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/user/social/notes/{id}/revisions?userId=...` | 创建 `copy` 存档或 `invite` 协作版本 |
| `GET` | `/api/user/social/notes/{id}/revisions?userId=...` | 原作者和贡献者查看版本日志 |
| `PUT` | `/api/user/social/revisions/{revisionId}/submit?userId=...` | 贡献者提交自己的版本 |
| `PUT` | `/api/user/social/revisions/{revisionId}?userId=...` | 原作者审核 `approved/rejected/merged`；只有 merged 才更新公开帖 |
| `PUT` | `/api/user/social/notes/{id}/platform-review?reviewerId=...` | 平台内部处理 `approved/rejected` 队列 |

版本规则：

- 帖子复制关系通过 `social_note.id` 的 `source_note_id` 追踪，不使用聊天 session 号。
- `social_note.state_code` 和 `social_note_revision.revision_code` 均为 8 位小写字母数字状态码。
- 作者主动更新、贡献者提交、原作者合并都会产生或切换内部版本状态；公开内容只有在发布或合并成功后改变。
- 复制存档的状态是 `archived`，不具备公开发布权限。

### 4.4 版权举报与信誉分当前规则

发布和举报都会写入 Agent 识别流水：

```text
相似度 >= 0.90       -> auto_reject，帖子转 private/rejected
0.70 <= 相似度 < .90 -> manual_review，帖子转 pending_review，进入平台队列
相似度 < 0.70       -> allow
信誉分 < 60 且原本 allow -> 强制 manual_review
```

新用户首次参与社区发布时信誉分为 100。每个唯一版权举报当前扣 5 分，并在 `social_reputation_event` 记录扣分前后分值。信誉分到 60 以下时，即使相似度允许，也必须平台人工审核后才公开。

`POST /api/user/social/notes` 的返回结果包含：`published`、`id`、`stateCode`、`reputationScore`、`moderationDecision`、`similarityScore`、`reviewRequired`、`sourceNoteId`。前端应根据这些字段展示“已发布 / 等待审核 / Agent 自动退回”，不要只根据 HTTP 200 判断发布成功。

## 5. 旅行数据 API

### 5.1 结构化旅行计划 `/api/travel-plans`

`POST /api/travel-plans/generate` 使用固定表单输入，body：

```json
{
  "destination": "杭州",
  "days": 3,
  "budget": 3000,
  "travelers": 2,
  "travelStyle": "轻松漫游",
  "interests": ["美食", "人文"]
}
```

约束：目的地非空，天数 1–14，预算 300–200000，人数 1–12。响应 `data` 是 `TravelPlanResponse`，至少包含 `planId`、`destination`、`days`、`overview`、`dayPlans`、`travelTips`、`packingList`、`dataSources`、`dataWarnings`。

`dataWarnings` 是稳定性的一部分：天气、POI 或餐饮数据缺失时必须明确返回告警，UI 应保留每日计划骨架和文字提示，不能因为某个供应商失败而把整张结果渲染成空白。

### 5.2 旧版旅行工具 API `/api/travel`

| 方法 | 路径 | body/query | 说明 |
|---|---|---|---|
| `POST` | `/api/travel/plan` | body `{ "request": "北京 3 日游……" }` | 旧版文本规划，返回裸 `{ success, data }` |
| `GET` | `/api/travel/weather?city=杭州` | `city` | 天气查询 |
| `GET` | `/api/travel/poi?keywords=西湖&city=杭州` | `keywords`、`city?` | POI 查询 |
| `GET` | `/api/travel/health` | 无 | Agent 工具健康检查 |
| `GET` | `/api/poi/image?name=西湖&city=杭州` | `name`、`city?` | 地点/餐厅图片列表，返回裸 `{ name, city, imageUrls }` |
| `GET` | `/api/ratelimit/status` | 无 | 天气、高德调用计数和剩余额度 |
| `GET` | `/api/ratelimit/health` | 无 | 限流模块健康检查 |

### 5.3 对话 Agent `/api/agent`

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/agent/sessions` | 创建对话会话 |
| `GET` | `/api/agent/sessions?userId=...` | 获取用户会话列表 |
| `GET` | `/api/agent/sessions/{sessionId}/messages` | 获取消息历史 |
| `POST` | `/api/agent/messages` | body `{ sessionId, content }`，发送消息 |
| `POST` | `/api/agent/tool-result?sessionId=&toolCallId=&toolName=` | 提交工具调用结果，body 为工具结果对象 |
| `DELETE` | `/api/agent/sessions/{sessionId}` | 删除会话 |

### 5.4 固定问卷 Agent `/api/agent/questionnaire`

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/agent/questionnaire/start` | 创建五步问卷会话，返回 `sessionId` 和首问题 |
| `POST` | `/api/agent/questionnaire/{sessionId}/answer?step=N&answer=...` | SSE 返回解析结果、下一问题或最终计划 |
| `GET` | `/api/agent/questionnaire/health` | 检查 ChatModel 可用性 |

固定问卷的会话只用于问答上下文；版权复制链路使用 `source_note_id`，两者不要混用。

### 5.5 A2A Agent `/a2a/tasks`

A2A 任务采用“创建一次、订阅多次”的流程。创建接口返回裸 JSON，不使用 `ApiResult` 包装；后续查询、取消和 SSE 订阅都必须由任务所有者发起。

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/a2a/tasks` | 创建或按幂等键复用任务，返回 `{ taskId, status }` |
| `GET` | `/a2a/tasks/{taskId}/status` | 仅任务所有者查询状态 |
| `POST` | `/a2a/tasks/{taskId}/cancel` | 仅任务所有者取消未完成任务 |
| `GET` | `/a2a/tasks/{taskId}/stream` | 订阅已创建任务；可带 `Last-Event-ID` 续读，不会重新启动规划 |

创建请求体是 `TravelPlanRequest` JSON。请求头 `Idempotency-Key` 必填，长度 8–128，仅允许字母、数字、点、下划线、冒号和短横线。同一所有者对相同请求重复提交会复用任务；相同键搭配不同请求体返回 HTTP 409。任务不存在或不属于当前用户时返回 404，避免泄露他人任务是否存在。

SSE 支持 `task_update`、`tool_call`、`tool_result`、`token`、`task_done` 和 `error`。事件短期保存在 Redis，最多保留 24 小时和最近 500 条；客户端以数字 `Last-Event-ID` 从断点续读。事件过期后，已成功任务可从已保存的计划版本重建最终结果。

## 6. 前端调用边界

| 前端位置 | 后端链路 | 说明 |
|---|---|---|
| `src/api/index.ts` | Axios `/api` | 注入认证、处理 401、统一错误出口 |
| `src/api/auth.ts` | `/api/auth/*` | 登录、注册、邮箱绑定 |
| `src/api/note.ts` | `/api/notes/*` | 数据库笔记 CRUD 和图片 |
| `src/api/user.ts` | `/api/user/*` | 资料、社区、灵感、旅程和偏好 |
| `src/api/agent.ts` | `/api/agent/*`、`/api/travel-plans/*`、`/a2a/*` | Axios JSON + fetch SSE |
| `src/utils/editorWorkspace.ts` | localStorage + IndexedDB | 本地源文件工作区、文件句柄、快照；不写 `note_document` |

### 编辑器的两条数据流

```text
数据库笔记：编辑器 -> POST/PUT /api/notes -> note_document/note_block
本地源文件：文件句柄 -> 浏览器编辑器 -> IndexedDB 句柄 + localStorage 快照 -> 可选写回原文件
```

本地工作区只保存“文件路径标签、句柄索引和编辑快照”；数据库笔记保存“可发布内容”。如果后续新增文件类型或工作区操作，必须明确属于哪条数据流，避免把本地源文件误发布或把数据库笔记只保存在浏览器。

## 7. 变更规则

新增或修改接口时同步完成：

1. Controller 的路径、参数和权限注释。
2. `src/api` 的类型和调用封装。
3. 本文的接口表与错误/SSE 说明。
4. 至少一个服务层测试或可重复的 curl 示例。
5. 若涉及数据库字段，更新 `data/*_migration.sql`，并说明旧数据兼容策略。

当前详细示例仍保留在根目录 [`API.md`](../API.md)；它是扩展说明，本文是面向当前源码的入口和合约索引。
