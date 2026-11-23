# PRD：邮箱验证码 SMTP 发信故障复盘与可靠性加固方案（Roamly / travel-agent）

- **文档版本**：V1.0
- **记录日期**：2026-11-23
- **适用范围**：`travel-module-auth-biz`、`travel-web`、`frontend`
- **关联凭据配置**：`travel-web/src/main/resources/application-local.yaml`（已加入 `.gitignore`，严禁提交真实授权码）

---

## 1. 背景与目标

`travel-agent`（Roamly 旅行规划助手）使用 QQ 邮箱 SMTPS（`smtp.qq.com:465`）向任意目标邮箱发送 6 位登录/注册/绑定验证码。在本地 macOS 开发环境复测时，出现“**前端提示验证码已发送、后端报错超时、手机端收不到邮件**”的复合故障。

本 PRD 记录完整故障现象、根因定位（核心为本机代理 **TUN 虚拟网卡** 劫持 + `jakarta.mail` TLS 握手问题 + 前端响应拦截器漏判业务错误码）、工程改造方案以及后续本地开发排障 SOP，确保团队和后续迁移时能够一键复现定位并避免踩坑。

---

## 2. 故障现象与日志特征对照

| 现象维度 | 实际表现 / 关键日志 | 误判点 | 真实含义 |
| :--- | :--- | :--- | :--- |
| **启动健康检查** | `MailHealthIndicator : Mail health check failed` -> `SocketTimeoutException: Read timed out`（卡在 `SSLSocketImpl.startHandshake`） | 误以为 QQ 邮箱授权码过期 | 尚未进入认证阶段（授权码错误会报 `535 Authentication failed`），实际在 TLS 握手阶段即被阻断 |
| **发信超时（5s）** | `Could not connect to SMTP host: smtp.qq.com, port: 465` -> `Read timed out`（耗时恰好 5s） | 误以为仅是网络抖动 | 触发 `mail.smtp.timeout: 5000`，底层握手无响应 |
| **切换直连后的精准日志** | 1. `120.233.23.191` / `120.241.140.18`：`SSLHandshakeException - Remote host terminated the handshake`<br>2. `198.18.0.119`：`SocketTimeoutException - Read timed out` | 误以为直连代码无效 | **`198.18.0.119` 是代理 Fake-IP 网段（`198.18.0.0/15`）的铁证**；TUN 虚拟网卡仍在系统路由层接管全部流量 |
| **前端提示异常** | 后端已抛出 `BusinessException(500)`，前端仍弹出“验证码已发送，请查收邮件”并开始 60s 倒计时 | 误以为邮件已经发出 | 后端业务异常返回 HTTP 200 + JSON `code: 500`，前端 Axios 拦截器未校验业务 `code` |

---

## 3. 根因深度分析

### 3.1 核心根因：代理软件 TUN 虚拟网卡（Fake-IP）拦截 SMTP 465 流量（最关键）
1. **L3 路由层劫持无法被代码级 `Proxy.NO_PROXY` 绕过**：macOS 开启 Clash / Mihomo / Surge 等代理软件的 **TUN 模式（虚拟网卡）** 后，所有出站 TCP 流量都会在操作系统路由层被虚拟网卡接管，应用层的 `Proxy.NO_PROXY` 无法绕开。
2. **Fake-IP 污染与海外节点封禁 SMTP**：
   - DNS 解析混入 Fake-IP（`198.18.0.x`），直连该地址在 TLS 握手阶段直接超时（`Read timed out`）。
   - 即便解析出真实腾讯 IP（`120.233.23.191`、`120.241.140.18`），流量仍经虚拟网卡转发至代理节点；绝大多数代理节点为防垃圾邮件默认封禁 `25 / 465 / 587` 端口，导致远端直接掐断 TLS 握手（`SSLHandshakeException: Remote host terminated the handshake`）。
3. **验证结论**：**一旦关闭代理软件的 TUN 虚拟网卡，无需修改任何账号配置，立即恢复 `[DirectSMTPS] TLS handshake OK`，验证码秒达。**

### 3.2 次级根因：`jakarta.mail` SocketFetcher 在 JDK 17 下的 TLS 握手兼容性
- Spring Boot 默认 `JavaMailSenderImpl` 底层依赖 `jakarta.mail` / `angus-mail` 的 `SocketFetcher`。
- 在本机 JDK 17 与多 IP（IPv4/IPv6）解析环境下，默认实现缺乏显式的多地址回退与 `Proxy.NO_PROXY` + 显式 SNI 控制，且排查日志不透明，难以一眼看出实际命中的 IP 是真实 IP 还是 Fake-IP。

### 3.3 辅助问题 1：Actuator `MailHealthIndicator` 启动刷错
- `management.endpoints.web.exposure` 开启 `health` 后，Spring Boot Actuator 默认会执行 `MailHealthIndicator`，持续调用 `JavaMailSenderImpl.testConnection()`（仍走 `jakarta.mail` 路径），导致服务启动及健康检查时反复打印冗长堆栈告警。

### 3.4 辅助问题 2：前后端业务错误契约未对齐（假成功提示）
- 后端 `GlobalExceptionHandler` 处理 `BusinessException` 时返回 **HTTP 200** + `ApiResult.error(code, message)`。
- 前端 `frontend/src/api/index.ts` 原响应拦截器直接 `return response.data`，未判断 `payload.code !== 200`，导致 Axios 将业务失败当成成功 Promise，使 `AuthView.vue` 误报“验证码已发送”。

---

## 4. 解决方案与工程改造清单

### 4.1 后端改造
1. **引入原生 JDK 17 直连 SMTPS 客户端**
   - 文件：`travel-module-auth-biz/src/main/java/com/travel/auth/service/DirectSmtpsClient.java`
   - 核心能力：
     - 强制 `new Socket(Proxy.NO_PROXY)` 绕过系统应用层代理；
     - 遍历 `InetAddress.getAllByName(host)` 实现 DNS 多地址（IPv4/IPv6）自动回退；
     - 显式指定 `TLSv1.3` / `TLSv1.2` 与 `SNIHostName(host)`；
     - 连接超时 `5000ms`、读写超时 `10000ms`；
     - 每次尝试打印目标 IP 及失败异常类型，便于 1 秒识别 `198.18.x.x` Fake-IP 或端口封禁。
2. **改造验证码发信服务**
   - 文件：`travel-module-auth-biz/src/main/java/com/travel/auth/service/EmailVerificationService.java`
   - 改动：优先基于 `JavaMailSenderImpl` 的 `host`、`port`、`username`、`password` 构造 `DirectSmtpsClient` 执行发信；当注入 Mock `JavaMailSender`（如单元测试）时自动回退到 `mailSender.send(message)`。
   - 保持 Redis 原子清理逻辑：发信失败时立即删除验证码 Key 与 60s 冷却 Key，避免僵尸验证码阻塞重试。
3. **关闭 Actuator 默认邮件健康检查**
   - 文件：`travel-web/src/main/resources/application.yml`
   - 新增配置：
     ```yaml
     management:
       health:
         mail:
           enabled: false
     ```

### 4.2 前端改造
- 文件：`frontend/src/api/index.ts`
- 改动：在 Axios 响应拦截器中增加业务状态码校验：当响应体为对象且存在数字类型 `code !== 200` 时，主动 `Promise.reject(err)` 并透传后端 `message`，确保 `AuthView.vue` / `UserProfile.vue` 正确弹出错误提示且不误触发 60s 倒计时。

---

## 5. 本地开发与运维排障 SOP（必读）

> **铁律：遇到 `smtp.qq.com:465` 握手超时或 `Remote host terminated the handshake`，第一步先关虚拟网卡（TUN 模式）！**

### 方式 A：最快解决（推荐本地联调时使用）
1. 打开本机代理客户端（Clash / Mihomo / Surge 等）。
2. **关闭 TUN 模式（虚拟网卡）**（保留普通系统代理即可，或临时退出代理）。
3. 无需重启后端，直接重新点击“发送验证码”。

### 方式 B：需要长期开启 TUN 模式时的分流配置
在代理配置文件中同时添加 **Fake-IP 过滤** 与 **直连规则**：
```yaml
dns:
  fake-ip-filter:
    - "smtp.qq.com"
    - "+.qq.com"

rules:
  - DOMAIN,smtp.qq.com,DIRECT
  - DOMAIN-SUFFIX,qq.com,DIRECT
```

### 快速自检清单
1. 查看后端日志是否出现 `198.18.x.x` -> 若出现，说明 Fake-IP/TUN 仍在生效，立即关闭虚拟网卡。
2. 查看后端日志是否出现 `535` -> 若出现，说明 QQ 邮箱 16 位 SMTP 授权码失效或发件邮箱不匹配，需在 QQ 邮箱网页端重新生成并更新 `travel-web/src/main/resources/application-local.yaml`（切勿提交到 Git）。
3. 查看前端是否报 `Vite proxy error [ECONNREFUSED]` -> 仅表示后端 8080 端口正在重启或未启动，待 Spring Boot 启动完成后刷新页面即可。

---

## 6. 验收标准

- [x] 关闭代理 TUN 虚拟网卡后，控制台输出 `[DirectSMTPS] TLS handshake OK` 与 `[DirectSMTPS] mail accepted by smtp.qq.com:465 -> 250 OK`。
- [x] 目标邮箱（如 `163.com` / `qq.com`）在数秒内收到 6 位验证码，且可正常完成登录。
- [x] 启动后端时不再刷出 `MailHealthIndicator : Mail health check failed` 堆栈告警。
- [x] 模拟发信失败（例如开启 TUN 或填错授权码）时，前端明确弹出红色错误提示，不再误提示“验证码已发送”，且不锁定 60s 冷却按钮。
