# Roamly 智能旅行规划商业闭环 PRD（分阶段落地版）

> **文档版本**：v1.0  
> **适用仓库**：`travel-agent`（Vue 3 前端 + Java 17 / Spring Boot 3.2.4 多模块后端）  
> **实施策略**：分 4 个阶段渐进演进；**第一阶段优先补齐“算得准（物价与预算核算）+ 走得顺（地图距离与交通规划）”并建立外部服务占位层（Placeholder Provider）**，零外部平台依赖即可跑通端到端体验，后续通过策略接口无缝切换真实外部 API。

---

## 1. 背景与目标

### 1.1 现状评估
当前 Roamly 已具备较完整的 AI 行程规划骨架：
- **多 Agent 与工具编排**：`travel-a2a-runtime` + `travel-module-agent-biz` + 5 个 MCP Server（`poi`、`meal`、`weather`、`budget`、`itinerary`）。
- **行程与内容沉淀**：`travel-module-itinerary-biz`、`travel-module-note-biz`、`travel-module-user-biz`。
- **商业化沙箱骨架**：`travel-module-commerce-biz` 已实现 `offers` / `clicks` / `orders` / `pay` / `cancel` 沙箱闭环。

但相比成熟的商用旅行规划助手，仍存在以下核心缺口：
1. **预算失真**：用户需手动盲填总预算，缺乏基于目的地物价水平的智能预算建议与六大类明细账单。
2. **交通割裂**：行程卡片间缺少两点真实距离、通勤耗时、推荐交通方式（步行/地铁/打车）及通勤费用估算，容易出现跨区折返。
3. **交易断层**：缺少出发地大交通（机票/高铁）与住宿（酒店）的推荐、比价及预订履约入口，商业化尚停留在沙箱演示阶段。

### 1.2 商业闭环目标
构建 **「需求采集 → 智能预算与路径决策 → 结构化行程账单 → 机酒票务供给 → 订单/CPS 分佣归因 → 行中履约与复购」** 的完整闭环：
- **第一变现引擎（冷启动低风险）**：OTA 联盟 CPS 分佣（携程联盟、飞猪、美团联盟、Booking/Trip.com Affiliate），通过带参 DeepLink + 点击归因 + 回调对账获取 3%–10% 佣金，零资金牌照与售后垫资风险。
- **第二变现引擎（提升转化率）**：聚合实时比价 + 站内「一键预订清单」（往返大交通 + N 晚酒店 + 必买门票），预订状态自动回写至「我的旅程」。
- **第三变现引擎（提高留存与毛利）**：Pro 会员订阅（降价监控、多人协同、PDF/日历导出）+ 社区达人攻略 CPS 分成（复用现有 `travel-module-note-biz` 版权追踪机制）。

---

## 2. 外部服务占位与解耦架构规范

为避免开发前期被第三方开放平台审批、API 密钥与计费阻塞，所有外部依赖必须严格遵循 **Provider 接口 + Placeholder 占位实现** 模式：

| 抽象接口 | 第一阶段占位实现（内置生效） | 后续真实替换目标 |
| :--- | :--- | :--- |
| `CityPriceBenchmarkProvider` | 内置国内/海外热门城市分档物价库 + 淡旺季系数表 | 外部物价指数 / 自建历史订单均值统计 |
| `TransportRoutingProvider` | `PlaceholderTransportRoutingProvider`（基于城市特征与 POI 规则生成距离、耗时、步行/地铁/打车建议与费用） | 高德地图路径规划 v5 API（驾车/公交/步行/距离矩阵） |
| `BookingSupplyProvider` | `PlaceholderBookingSupplyProvider`（按出发地、目的地、天数和预算档位生成机票/高铁/酒店/门票占位报价与预订卡片） | 携程联盟 / 飞猪 / 美团联盟 / Amadeus / Duffel |
| `PaymentGateway` | 现有 `SandboxPaymentGateway` | 支付宝 / 微信支付 / 联盟跳转收银台 |

> **统一标识规范**：所有占位数据在响应中必须显式携带 `dataSource: "PLACEHOLDER"` 或 `priceSource: "CITY_BENCHMARK" / "PLACEHOLDER_QUOTE"` 字段，前端清晰提示用户当前为参考估算，切换真实外部 API 时零字段变更。

---

## 3. 分阶段研发规划

### 第一阶段（Phase 1 · 当前编码阶段）：算得准 + 走得顺引擎与外部服务占位层
**核心目标**：在不接入任何外部付费平台的前提下，让用户在 `/chat` 快速规划页获得完整的**当地物价预算建议、六大类明细账单核算、行程节点间距离与交通规划、以及机酒供给占位预览**。

#### 功能清单
1. **当地物价基准与智能预算推荐（后端 + 前端）**
   - 覆盖一线/新一线/热门旅游城市/海外热门城市的物价档位（`TIER_1` 超一线、`TIER_2` 热门省会与新一线、`TIER_3` 特色旅游城市、`OVERSEAS` 海外目的地）。
   - 根据「目的地 + 出发地（可选）+ 天数 + 同行人数 + 旅行节奏（轻松漫游/深度人文/美食优先/亲子友好）+ 出行月份（淡旺季系数）」自动计算：
     - 推荐总预算区间：`minBudget`（经济档）、`recommendedBudget`（匹配当前偏好的推荐值）、`maxBudget`（舒适轻奢档）。
     - **六大类费用拆解**：大交通（机票/高铁）、住宿、餐饮、景点门票、市内交通、机动预备金（10%）。
     - 预算健康度评估：将用户输入的预算与当地推荐值比对，输出 `AMPLE`（充裕）/ `BALANCED`（合理）/ `TIGHT`（偏紧）/ `INSUFFICIENT`（偏低）及可执行的调整建议。
   - **前端交互**：在 `/chat` 表单下方实时展示「当地物价与推荐预算条」，显示城市消费等级、推荐区间与人均日耗，支持一键点击「采纳推荐预算」。
2. **详细金额核算引擎（后端 + 前端）**
   - 行程生成后，综合行程节点费用与城市基准，输出结构化**详细费用账单**：
     - 六大类分项金额、占比、人均费用、日均费用。
     - 金额来源标注：`ITINERARY_CONFIRMED`（行程节点明确费用）、`CITY_BENCHMARK`（当地物价基准核算）、`PLACEHOLDER_QUOTE`（外部机酒占位报价）。
     - 预算水位进度条与超支预警提示。
3. **地图距离与交通规划占位服务（后端 + 前端）**
   - 定义 `TransportRoutingProvider` 接口及 `PlaceholderTransportRoutingProvider` 占位实现。
   - 接收单日 POI 列表，为每对相邻节点计算：
     - 预估直线/路网距离（km）与通勤耗时（分钟）。
     - 推荐交通方式（`< 1.5km` 步行、`1.5–6km` 地铁/打车、`> 6km` 地铁或网约车）与预计交通费用。
     - 路线合理性诊断：单段通勤超过 45 分钟或日均折返距离过长时，输出路线优化提醒。
   - **前端交互**：在每天的结构化行程卡片之间渲染简洁的「交通衔接条」（如 `🚇 地铁/打车 · 约 4.2 km · 18 分钟 · 预计 ¥18`）。
4. **机票/酒店供给占位服务（后端 + 前端预留）**
   - 定义 `BookingSupplyProvider` 接口及 `PlaceholderBookingSupplyProvider` 占位实现。
   - 根据出发地、目的地、天数、人数和预算档位返回占位推荐卡片（往返大交通方案 + 商圈推荐酒店），标明「占位演示 · 后续接入真实 OTA 报价」，并与现有 `travel-module-commerce-biz` 形成无缝衔接。

---

### 第二阶段（Phase 2）：出发地与日期联动 + 真实高德与机酒检索对接
**核心目标**：将第一阶段的 Placeholder Provider 替换为真实外部数据源。
1. **表单与 Agent 升级**：在快速规划表单和对话问卷中增加「出发城市」与「出发日期」字段，自动联动实时天气预报与淡旺季定价。
2. **真实高德路径规划**：实现 `AmapTransportRoutingProvider`，对接高德 Web 服务 API（地理编码 + 驾车/公交/步行路径规划 + 距离矩阵），并在 Agent 编排层增加地理聚类重排逻辑。
3. **真实机酒报价接入**：实现 `OtaBookingSupplyProvider`，对接机票/高铁查询（如 Amadeus/聚合接口）与商圈酒店实时房价查询，将真实报价注入预算账单。

---

### 第三阶段（Phase 3）：Commerce 升级与真实 CPS 联盟分佣闭环
**核心目标**：跑通“点击 → 跳转/下单 → 成交回调 → 分佣对账 → 行程履约状态同步”的真实商业变现。
1. **联盟商品与 DeepLink 归因**：扩展 `travel-module-commerce-biz`，支持携程/飞猪/美团/Booking 联盟商品映射与带 `planId + userId + subId` 的追踪链接生成。
2. **一键预订清单**：在行程确认页汇总本次旅行的「大交通 + 住宿 + 门票」预订清单，支持逐项跳转/下单并自动更新预订状态（未预订 / 待确认 / 已预订），同步回写到 `JourneyView`。
3. **对账与佣金看板**：新增联盟订单回调 Webhook 与定时拉取对账任务，记录 GMV、预估佣金与已结算佣金。

---

### 第四阶段（Phase 4）：Pro 会员权益与达人攻略分佣飞轮
**核心目标**：建立高毛利订阅收入与 UGC 内容获客飞轮。
1. **Pro 会员体系**：开放机酒降价监控提醒、无限次实时比价、多人协同编辑、PDF/ICS 日历导出。
2. **达人攻略分佣（Creator CPS）**：基于 `travel-module-note-biz` 社区与版权追踪能力，用户“一键复刻他人游记并按自身预算重算”后产生的机酒预订佣金，自动按比例分成给原攻略作者。

---

## 4. 第一阶段核心 API 合约（`/api/planning/*`）

| 方法与路径 | 说明 | 核心入参 | 核心出参 |
| :--- | :--- | :--- | :--- |
| `POST /api/planning/budget/recommend` | 获取当地物价基准与智能预算推荐 | `destination`, `departureCity?`, `days`, `travelers`, `travelStyle`, `budget?`, `travelMonth?` | `cityTier`, `seasonFactor`, `minBudget`, `recommendedBudget`, `maxBudget`, `perPersonBudget`, `budgetHealth`, `advice`, `categories[6]`, `dataSource` |
| `POST /api/planning/bill/calculate` | 根据行程活动与预算核算六大类详细账单 | `destination`, `departureCity?`, `days`, `travelers`, `travelStyle`, `userBudget`, `activities[]` | `totalEstimatedCost`, `perPersonCost`, `perDayCost`, `utilizationRate`, `status`, `categories[6]`, `dataSource` |
| `POST /api/planning/transport/estimate` | 计算单日/多日景点间距离与交通规划（占位） | `destination`, `days: [{ day, stops: [{ name, location, type }] }]` | `days: [{ day, totalDistanceKm, totalDurationMin, totalTransitCost, warning?, segments[] }]`, `dataSource` |
| `POST /api/planning/booking/placeholders` | 获取机票/高铁与酒店占位推荐清单 | `departureCity?`, `destination`, `days`, `travelers`, `travelStyle`, `budget` | `transportOptions[]`, `hotelOptions[]`, `dataSource` |

---

## 5. 验收标准（第一阶段）
1. 后端编译无错误，`/api/planning/*` 四个接口均可独立响应，外部服务均通过 Provider 接口解耦且明确标注占位来源。
2. `travel-mcp-server-budget` 同步升级为六大类城市物价基准核算，兼容既有 `budget.estimate` MCP 工具并新增 `budget.recommend` 工具。
3. 前端 `/chat` 快速规划页在输入目的地、天数、预算、人数、节奏时，自动展示当地物价推荐预算与健康度提示，支持一键采纳；行程生成后展示六大类详细账单、机酒占位推荐卡片以及每日景点间的距离与交通规划条。
4. 前端 `vue-tsc && vite build` 构建通过，原有快速规划、SSE 流式生成、保存至灵感/旅程、沙箱商业化接口 100% 保持兼容。
