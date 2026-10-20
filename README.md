# AiNexus AI Gateway

企业内部 AI 大模型网关：纯 HTTP 透明代理（OpenAI 兼容），提供统一鉴权、动态路由与多渠道容灾，带管理员/内部用户控制台；**无 Token 计费、无 C 端用户管理**。

> 设计禁忌：不引入 Spring AI / LangChain4j，不引入 WebFlux/Reactor。只做 HTTP 报文与原始 JSON/SSE 流的透传。

---

## 1. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言/运行时 | JDK 21 (LTS) | 虚拟线程完整支持 |
| Web | Spring Boot 3.3.5 + Servlet(Tomcat) | `spring.threads.virtual.enabled=true`，请求处理跑虚拟线程 |
| 上游转发 | OkHttp3 4.12 | 双实例连接池 + 流式 ResponseBody，见 `OkHttpConfig` |
| 持久层 | MySQL + MyBatis-Plus 3.5.7 | 渠道主表 `channel` + 模型绑定表 `channel_model` |
| 缓存/并发 | Redisson 3.34 | `RouteCacheService` 版本化 Token/路由缓存、渠道轮询 RAtomicLong、渠道并发 RSemaphore |
| 鉴权 | HandlerInterceptor | `Authorization: Bearer {Internal-Key}`，Mock 白名单 + Redis 集合 |

## 2. 架构

```
客户端(OpenAI SDK/curl)
   │  POST /v1/chat/completions   Authorization: Bearer {Internal-Key}
   ▼
InternalAuthInterceptor ── RouteCacheService 查 Redis Token 缓存，401 阻断无效 Token
   ▼
ChatController ── 仅解析 {model, stream} 两个字段, 请求体原文 byte[]
   ▼
ChannelService ── RouteCacheService 查 Redis model → 候选渠道缓存
   │    启动/每 60 秒由 MySQL channel + channel_model + gateway_token 预热；请求期不查 MySQL
   │    Redis 挂时使用本机快照；一个渠道可绑定多个模型；同模型多渠道先按 priority，再按 weight 分配
   │    Redis RAtomicLong 做跨实例轮询，RSemaphore 控制渠道 maxConcurrent
   ▼
ProxyService ── 透明透传
   ├─ 替换 Base URL + Authorization: Bearer {真实厂商 Key}
   ├─ 剥离 hop-by-hop 头(Host/Connection/Content-Length/Transfer-Encoding...)
   ├─ 流式: Accept: text/event-stream, 逐 chunk 读上游 → write → flush 回下游
   ├─ 多渠道容灾: 连接失败/5xx/429 → 自动切换下一渠道
   └─ 客户端断开/上游中断 → call.cancel() 归还连接
```

### 降级链路（高可用第一原则）

| 组件故障 | 行为 |
|---|---|
| MySQL 不可用 | `initialization-fail-timeout=-1` 启动不快速失败；路由降级到本机快照/配置静态渠道，健康检查不判死 |
| Redis 不可用 | `RedisSupport` 警告后进入降级模式：鉴权与路由走本机快照、轮询走本地计数器、分布式限流与熔断暂时关闭；后台每 15s 自动重连恢复 |
| 上游渠道挂 | 连接失败 / HTTP 5xx / 429 → 自动 failover 到下一渠道，同时累计连续失败；达到 5 次后 Redis 熔断 5 分钟 |

### 流量控制与审计

- `gateway_token.rate_limit_config` 使用 JSON 配置应用级分布式限流，例如 `{"permitsPerSecond":5}`。限流范围默认为 `app_name`，命中时返回 OpenAI 风格 `429 rate_limit_exceeded`。
- 熔断状态保存在 Redis；渠道连续失败阈值为 5，冷却期为 300 秒。`channel.failure_count` 由虚拟线程异步回写，供运营观测，Redis 状态才是实时选路权威。
- `request_audit_log` 记录应用、模型、最终尝试渠道、近似 prompt token、状态码、耗时与截断错误摘要。日志 insert 在虚拟线程执行，绝不阻塞 SSE 响应。
- 审计 `prompt_tokens` 是纯文本消息的 `字符数 / 4` 近似值；复杂多模态请求记录为 `NULL`，不伪造厂商精确 token。

## 3. 快速开始

```bash
# 默认 java 是 1.8 的话, 先切 JDK21
set JAVA_HOME=C:\Program Files\Java\jdk-21
set PATH=%JAVA_HOME%\bin;%PATH%

# 不用 MySQL/Redis 也能跑: 自动进入降级模式, 使用配置里的静态渠道 mock-model → http://127.0.0.1:9999
mvn -DskipTests spring-boot:run
```

### 模型目录

网关提供本地 OpenAI 兼容模型目录，不会查询上游厂商，数据来自当前预热的路由缓存：

```bash
curl http://localhost:8080/v1/models \
  -H "Authorization: Bearer sk-ainexus-dev-team-a"
```

返回格式为 OpenAI 兼容的 `{ "object": "list", "data": [...] }`；仅展示启用渠道中已绑定的模型及静态降级模型。

### 本地连通性验证（无需真实厂商 Key）

1. 启动 Mock 上游（模拟 OpenAI 兼容 SSE 服务）：
   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File tools\mock-upstream.ps1
   ```
2. 非流式：
   ```bash
   curl http://localhost:8080/v1/chat/completions \
     -H "Authorization: Bearer sk-ainexus-dev-team-a" -H "Content-Type: application/json" \
     -d "{\"model\":\"mock-model\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
   ```
3. SSE 流式：
   ```bash
   curl -N http://localhost:8080/v1/chat/completions \
     -H "Authorization: Bearer sk-ainexus-dev-team-a" -H "Content-Type: application/json" \
     -d "{\"model\":\"mock-model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
   ```
   应看到多个 `data: {...}` chunk 依次到达（Mock 每 800ms 一块，网关逐块 flush 透传）。

### 接真实厂商

1. 新数据库执行 `src/main/resources/sql/schema.sql`；已有数据库按版本顺序执行 `src/main/resources/sql/migration/` 中尚未执行的脚本。升级 GitHub Star 准入时，必须执行 `V15__github_oauth_refresh_tokens.sql`；升级 Token 用量统计口径时，必须执行 `V16__normalize_audit_token_accounting.sql`。若需要 GitHub OAuth 自动续期，还需在 GitHub OAuth App 的设置中开启 **Expire user authorization tokens**，然后让已绑定用户重新授权一次，以获取 refresh token。
2. 一个渠道可绑定多个模型。例如 BigModel 对接智谱并绑定三个 GLM 模型：
   ```sql
   INSERT INTO channel
       (name, provider, protocol, base_url, api_key, priority, weight, max_concurrent, default_test_model, status)
   VALUES
       ('BigModel-ZhiPu-Primary', 'zhipu', 'openai',
        'https://open.bigmodel.cn/api/paas/v4', 'sk-real-key',
        100, 10, 50, 'GLM-5.3', 1);

   SET @channel_id = LAST_INSERT_ID();
   INSERT INTO channel_model (channel_id, model_name, upstream_model_name, status) VALUES
       (@channel_id, 'GLM-5.3',       NULL, 1),
       (@channel_id, 'GLM-5.3-FLASH', NULL, 1),
       (@channel_id, 'GLM-5.2',       NULL, 1);
   ```
   `upstream_model_name` 可为空（原样透传），也可设置为厂商实际模型名实现内部模型别名映射。模型名可能区分大小写：例如对外暴露 `GLM-5.3` 时，可映射为厂商实际值 `glm-5.3`。
3. `priority` 数值越大越优先；只在相同优先级内按 `weight` 加权分配；`max_concurrent=0` 表示不限并发。渠道级 Semaphore 会覆盖整个 SSE 生命周期。
4. 渠道可多选“支持协议”，并从中指定一个 `protocol` 作为**主协议**。主协议是生产路由实际使用的报文格式、默认路径与鉴权方式；支持协议列表仅记录该渠道能力，不会在请求时盲目轮询或自动猜测。`protocol_config.requestPath` 仍可覆盖主协议默认路径：

   | protocol | 上游路径 | 鉴权 | 网关行为 |
   |---|---|---|---|
   | `openai-completions` | `/v1/chat/completions` | `Authorization: Bearer {apiKey}` | 原始 JSON/SSE 透明转发 |
   | `openai-responses` | `/v1/responses` | `Authorization: Bearer {apiKey}` | Chat Completions 请求转为 Responses，非流式响应转回 Chat Completions |
   | `anthropic-messages` | `/v1/messages` | `x-api-key: {apiKey}` + `anthropic-version` | Chat Completions 请求转为 Anthropic Messages，非流式响应转回 Chat Completions |

   当前 SSE 只支持 `openai-completions`；其它两种协议携带 `stream=true` 时会明确返回 `400 stream_not_supported_for_protocol`，不会返回错误格式的伪流。协议覆盖可放到 `protocol_config` JSON，例如 `{"requestPath":"/chat/completions","authHeader":"Authorization","authPrefix":"Bearer ","headers":{"X-Vendor":"value"}}`。
5. 内部 Token 统一维护于 `gateway_token`。字段 `rate_limit_config` 可保存应用级限流 JSON（例如 `{"permitsPerSecond":20,"burst":40}`，当前作为配置预留）；写库后调用 `RouteCacheService.refresh()`，或等待最长 60 秒预热刷新。Redis 中仅保存 Token 的 SHA-256 摘要元数据，不保存 Token 原文。

## 4. 核心实现要点

### SSE 流式无阻塞转发（ProxyService.relayOnce）

```java
try (InputStream in = body.byteStream();
     OutputStream out = response.getOutputStream()) {
    byte[] buffer = new byte[8192];
    int read;
    while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
        out.flush();   // 每个 chunk 立即刷回下游 — SSE 实时性的核心
    }
    out.flush();
}
```

- 请求运行在虚拟线程，阻塞读写成本极低；
- `X-Accel-Buffering: no` 通知 Nginx 等中间层不缓冲 SSE；
- 客户端断开时 `call.cancel()` 立即归还 OkHttp 连接，防止连接池泄漏。

### 透明的含义（byte-level）

- 请求体只在控制器里以 `byte[]` 读一次（为取 model/stream），**原文透传**上游，绝不重建业务 JSON；
- 响应体逐字节复制，`Copy-Response-Headers` 剥离 hop-by-hop 头；
- 已验证：直连 Mock 与经网关响应的 SHA256 完全一致（非流式 + SSE 含 UTF-8 中文）。

## 5. 控制台账号与权限

- 登录入口为前端根页面。首次启动会在 `account` 表中创建管理员：用户名 `admin`，密码 `change_me`；生产环境必须通过 `GATEWAY_BOOTSTRAP_ADMIN_PASSWORD` 覆盖初始密码，并设置 `GATEWAY_CONSOLE_SESSION_SECRET`。
- 密码以 PBKDF2-HMAC-SHA256 哈希保存，登录会话为 12 小时签名令牌；禁用账号后会话在下一次请求立即失效。
- 管理员端包含：概览、账号池、模型组、用户、用户组、用量统计、调用日志、网关设置、系统设置。管理员可在顶部切换至用户端。
- 用户端包含：模型广场、API Keys、用量统计。普通用户无法访问 `/api/admin/**`。
- 管理员“用户”页面管理普通 `USER` 账号，新增时必须填写用户名、密码、确认密码和备注；密码最少 8 位，服务端再次校验两次密码一致。
- 用户端 API Key 写入 `gateway_token.account_id`，创建与撤销后立即刷新 Token 缓存；明文 Key 仅在创建响应中返回一次。

## 6. Admin 管理台

### 后端 Admin API

所有接口前缀为 `/api/admin`，请求必须包含 `Admin-Authorization` Header。默认开发口令是 `change_me`；生产环境必须通过 `GATEWAY_ADMIN_SECRET` 环境变量覆盖，绝不将真实口令写回配置文件或前端源码。

| 资源 | 接口 |
|---|---|
| 概览 | `GET /api/admin/overview?range=today|24h|7d|30d` |
| 渠道 | `GET/POST /api/admin/channels`，`GET/PUT/DELETE /api/admin/channels/{id}`，`PATCH /api/admin/channels/{id}/status`，`POST /api/admin/channels/{id}/test` |
| 模型组 | `GET/POST /api/admin/model-groups`，`PUT/DELETE /api/admin/model-groups/{id}`，`GET /api/admin/model-groups/available-models` |
| 用户组 | `GET/POST /api/admin/user-groups`，`PUT/DELETE /api/admin/user-groups/{id}`，`GET /api/admin/user-groups/available-members`，`GET /api/admin/user-groups/available-model-groups` |
| Token | `GET/POST /api/admin/tokens`，`PUT/DELETE /api/admin/tokens/{id}`，`PATCH /api/admin/tokens/{id}/status` |
| 用量统计 | `GET /api/admin/usage?user=&provider=&apiType=`，`GET /api/admin/usage/filters` |
| 审计日志 | `GET /api/admin/logs?page=1&size=20&appName=&statusCode=`，`GET /api/admin/logs/{id}` |
| 网关设置 | `GET/PUT /api/admin/gateway-settings` |

- 管理端“概览”通过 ECharts 展示实时活跃用户、流式请求、网关并发和等待队列，列出用户组与上游账号并发及健康状态；按今日、近 24 小时、近 7 天、近 30 天查看 Token/请求/用户总量和并发时间轴。并发历史为本机内存按分钟采样，网关重启后重新积累；用量聚合来自持久化审计日志。
- 管理端“用量统计”支持按用户、供应商和 API 类型筛选，展示请求量、输入/输出/总/缓存 Token，并提供供应商、上游账号、模型组、模型、用户组、用户六个分布维度。V11 后的非流式响应会从上游 `usage` 字段记录真实 Token；流式或上游未返回 usage 时对应输出/缓存 Token 为 0。历史审计记录按原 `prompt_tokens` 回填输入和总 Token。
- 调用日志在 V12 后记录 UUID Request ID、用户/用户组/API Key/模型组快照、供应商和上游账号、请求/上游模型、流式类型、上游端点和协议路径，以及上游状态和时延。`first_token_ms` 记录收到上游响应的时间；SSE 首个事件级精确首字未额外解析。
- 网关设置由 `gateway_runtime_setting` 单例持久化并立即更新运行时快照：接入地址规范为以 `/v1` 结尾；开启协议转换时仅在无可用 `openai-completions` 渠道时回退到 Responses/Anthropic 渠道，原生协议始终优先；全局并发/队列、非流式超时、流式空闲超时和最大报文大小从下一请求起生效。
- 渠道更新后立即调用 `RouteCacheService.refresh()`，无需等待周期刷新。
- Token 明文只在 `POST /api/admin/tokens` 的创建响应中返回一次；列表只返回掩码。
- 渠道 API 将 `channel` 与 `channel_model` 模型绑定作为一个资源返回，支持一个渠道配置多个模型。`supported_protocols` 保存多选协议 JSON，`protocol` 保持为生产主协议；旧数据未设置 `supported_protocols` 时自动回退为主协议。
- 渠道列表不展开模型名，只展示可点击的“已绑定 N 个”标签；点击后在只读弹窗查看对外模型名、上游模型映射和绑定状态。
- 新增/编辑渠道抽屉提供“获取上游模型”：新渠道调用 `POST /api/admin/channels/discover-models` 使用当前表单配置；已保存渠道在 API Key 留空时调用 `POST /api/admin/channels/{id}/discover-models`，由服务器安全使用已保存密钥。两种方式均只返回模型目录，不会回传真实 Key。
- 模型组使用 `model_group` 与 `model_group_model` 关联表，包含名称、从当前可路由模型目录选择的一个或多个模型、备注；删除组时同步清理关联模型，不影响渠道或 `/v1` 路由。
- 用户组使用 `user_group`、`user_group_member`、`user_group_model_group` 三张表，支持普通用户成员选择、授权模型组、组/单 API Key/成员并发限制及组/成员 Token 配额。配额周期只允许 `DAY`、`WEEK`、`MONTH`、`YEAR`，所有配额或并发值 `0` 表示不限制。
- 普通用户的模型广场、其 API Key 的 `GET /v1/models` 和 `/v1/chat/completions` 均强制只允许该用户所属用户组授权模型组内的当前可路由模型；多组成员资格按模型并集授权。未归属任何用户组或没有模型组授权的普通用户无模型可用，调用未授权模型会返回 `403 model_not_authorized`。管理员控制台和未归属控制台账号的内部服务 Token 保持全模型访问。
- 模型发现拒绝回环、私网和链路本地地址，防止管理端被用作 SSRF 探针；本机 Mock 渠道请手工添加模型绑定。
- 渠道列表的“测试”支持一个或多个已绑定模型，向上游发送非流式短对话；每个模型单独报告可用性、HTTP 状态、耗时和回答/错误摘要。单批最多 20 个模型、最多 4 个并发测试，不进入生产路由、内部 Token 限流、审计日志或熔断计数。

### Vue 管理台

前端位于 `ui/`，使用 Vue 3 Composition API、TypeScript、Vite、Element Plus 和 Axios：

```powershell
cd ui
pnpm install
pnpm dev
```

打开 `http://127.0.0.1:5173/`。Vite 将 `/api` 代理到 `http://127.0.0.1:8080`，管理员口令保存在浏览器 `sessionStorage` 并由 Axios 拦截器自动写入 `Admin-Authorization` Header。

生产构建：

```powershell
cd ui
pnpm build
```

## 6. 目录结构

```
src/main/java/com/ainexus/gateway/
├── GatewayApplication.java        # @EnableScheduling @MapperScan
├── auth/InternalAuthInterceptor.java
├── common/  BizException / ErrorResponses / GlobalExceptionHandler
├── config/  GatewayProperties / OkHttpConfig / WebConfig
├── controller/ ChatController        # /v1/chat/completions + /v1/** 兜底
├── entity/  Channel                  # MyBatis-Plus 实体
├── mapper/  ChannelMapper
├── redis/   RedisSupport             # Redisson 降级式连接管理
├── route/   ChannelRoute             # 运行时路由对象(不含日志泄露)
└── service/ InternalAuthService / ChannelService / ProxyService
src/main/resources/
├── application.yml
└── sql/schema.sql
tools/mock-upstream.ps1               # 本地 Mock 上游(SSE)
```

## 7. 已实现 / 规划

**已实现**
- [x] 统一 OpenAI 入口：`POST /v1/chat/completions`（及 `/v1/**` 兜底透传）
- [x] 内部鉴权 Mock：Bearer Key 白名单 / Redis 集合，401/429 统一 OpenAI 错误格式
- [x] 动态路由：model → 渠道（DB + 静态降级），Redis 轮询（RAtomicLong）
- [x] 透明透传：替换 URL/Key、剥离 hop-by-hop 头、字节级一致
- [x] SSE 流式无阻塞转发：逐 chunk flush、X-Accel-Buffering、断连 cancel
- [x] 多渠道容灾：连接失败/5xx/429 自动 failover
- [x] Redis/MySQL 全故障降级运行

**规划（下一阶段）**
- [ ] 渠道健康探测 + 熔断（失败标记摘除/恢复）
- [ ] 限流细化：按内部应用/按 model 的独立配额
- [ ] 请求日志落库（调用量、延迟、状态码）与审计
- [ ] 管理 API：渠道/Key 在线增删改查（无需重启）
- [ ] 非 OpenAI 协议适配层（`protocol=native` 预留）