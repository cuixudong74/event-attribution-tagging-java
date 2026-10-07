# 投放事件归因与标签化能力（event-attribution-tagging-java）

曝光、点击、转化事件陆续到达，同一用户跨渠道、跨设备反复出现，且事件乱序、迟到、重复上报。
本服务在这些前提下给出可信、可复现、可追溯的归因结论，并在采集高峰与并发查询下保持稳定。

## 1. 事件与身份关联

- 事件类型：`IMPRESSION`（曝光）、`CLICK`（点击）、`CONVERSION`（转化）。
- 每个事件携带 `eventId`（去重键）、`userId`、`deviceId`、`channel`、`timestamp`（事件时间）、`ingestTime`（到达时间）。
- 身份图（`IdentityGraph`）基于并查集实现：同时携带 `userId` 与 `deviceId` 的事件建立两者关联，
  多个用户 id 共享设备时合并为同一身份（规范用户取字典序最小者，确定不漂移）。
- 事件状态（`EventStatus`）：
  - `LINKED`：已关联到已知用户且已获授权，进入归因；
  - `UNLINKED`：无法关联到任何已知用户（匿名/标识缺失），**不进入归因但保留标记并计入总量对账**，绝不静默丢弃；
  - `UNAUTHORIZED`：已关联但未获隐私授权，不进入归因，仍计入总量对账。
- 关联不上的转化不产生归因结果，但可在总量口径中对账；后续登录/授权事件到达后会被重新关联并修正结论。

## 2. 乱序、迟到与重复

- 事件按 `timestamp`（事件发生时间）参与归因，与到达顺序无关；乱序到达不影响结论。
- `eventId` 全局去重：重复上报返回 `duplicates`，不计入归因、不重复分摊贡献。
- 迟到事件（事件时间早于此前已观察到的最大时间戳）到达后触发全量重算：
  - 归因结果被修正，并生成一条归因修订（`AttributionRevision`），原因标记为 `LATE_EVENT`；
  - 修订给出逐项差异（`AttributionDiff`）：新增触点 `added`、移除触点 `removed`、权重变化 `changed`，修正前后可解释、可追溯。
- 授权变更（`CONSENT_CHANGED`）、配置变更（`CONFIG_CHANGED`）同样触发重算与修订。

## 3. 归因模型

- `FIRST_TOUCH`：窗口内最早的触点获得 100% 贡献。
- `LAST_TOUCH`：窗口内最晚的触点获得 100% 贡献。
- `TIME_WEIGHTED`：按指数衰减分配权重，距转化越近权重越高，半衰期为 `attribution.half-life`，权重和归一化为 1。
- 归因窗口 `attribution.window`：仅转化前窗口内（含边界）的触点参与分配。
- 归因是纯函数计算：同一批输入与同一份配置下，任意时刻、任意次重算结果完全一致、可复现。

## 4. 配置化与校验

配置项（`application.properties`）：

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `attribution.model` | 归因模型，`FIRST_TOUCH`/`LAST_TOUCH`/`TIME_WEIGHTED` | `LAST_TOUCH` |
| `attribution.window` | 归因窗口，支持 `7d`、`24h`、`P7D` | `7d` |
| `attribution.half-life` | 时间加权半衰期，仅 `TIME_WEIGHTED` 生效 | `24h` |
| `attribution.touch-types` | 参与归因的触点类型，逗号分隔 | `IMPRESSION,CLICK` |

- 启动时加载并校验，非法/冲突配置直接拒绝启动（`AttributionConfigException`，携带逐项原因 `reasons`）。
- 运行期可通过 `POST /api/config/reload` 热加载；配置变更后在途事件按新配置**确定性重算**，
  已产出的结论被修正并留下 `CONFIG_CHANGED` 修订记录。
- 非法或互相冲突的配置（如窗口非正、时间加权缺少半衰期、触点类型为空、触点类型包含 `CONVERSION`、
  非法触点类型）在加载时被拒绝并返回原因；热加载失败时当前配置与既有结论保持不变。

## 5. 并发模型（采集与查询）

- 摄入在 `ingestLock` 内串行完成：去重 → 追加日志 → 重建身份图 → 状态判定 → 全量重算 → 生成修订 → 整体发布。
- 事件日志使用线程安全的只追加结构；重算开始时取得当时事件列表的不可变副本，采集期间继续到达的事件不影响本次发布。
- 查询永远读取 `SnapshotHolder` 中的 `volatile` 不可变快照（`Snapshot`）：
  快照内结果、事件状态、对账口径、修订历史自洽一致，**不会读到中间状态**；
  查询期间到达的事件在下一个快照中可见，不丢失。
- 每次发布的快照都会保留版本号。需要同时读取快照、对账口径和修订历史时，先调用一次 `/api/snapshot`
  取得 `version`，再携带同一个 `?version=` 调用其他查询接口；这些接口会返回同一个不可变版本中的数据。
  不传 `version` 时保持默认行为，读取发布时的最新版本。
- 修订历史只随新快照追加，修订 ID 单调递增；固定版本重复读取得到完全相同的列表，旧条目不会回退或丢失。
- 快照版本单调递增；总量对账恒等式：
  `totalReceived = linked + unlinked + unauthorized + duplicates`。

## 6. 标签

- 标签按 `(userId, channel)` 维护，含 `key/value/priority/source/updatedAt`。
- 覆盖规则：**优先级高者覆盖低优先级**；同优先级以较新写入时间为准。
- 写入时间相同且优先级相同时，按 `source` 字典序决胜；缺少 `source` 是合法输入，按空字符串参与比较。
  若来源仍相同，再按标签名和值字典序决胜，从而为同一批输入提供唯一、可复现的结果。
- 合并始终在一次原子比较更新中选择一个完整的 `Tag` 对象，不会把不同请求的字段拼接成矛盾结果；
  不同 `(userId, channel)` 之间互不影响。
- 并发写入的返回值是该键当时合并后的完整标签；未成为最终胜者的写入也已参与合并，不会被静默丢弃。

## 7. 隐私授权

- 未授权用户的事件不进入归因（状态 `UNAUTHORIZED`），但始终计入总量对账。
- `POST /api/consent/{userId}` 授权后，历史事件重算进入归因。
- 用户级明细读取需鉴权：`actor=admin` 可读取全部，用户仅可读取本人；越权读取返回 `403 FORBIDDEN`。
- 总量对账为聚合口径，无需用户级鉴权。

## 8. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/events` | 批量摄入事件（数组），返回 `accepted/duplicates/snapshotVersion` |
| GET | `/api/snapshot?version=` | 当前或指定版本的不可变快照（结果、状态、对账、修订） |
| GET | `/api/reconciliation?version=` | 当前或指定版本的总量对账口径（聚合） |
| GET | `/api/revisions?version=` | 当前或指定版本的归因修订历史与逐项差异 |
| GET | `/api/users/{userId}?actor=` | 用户级归因明细（需鉴权） |
| POST | `/api/tags?actor=` | 写入标签（按优先级合并） |
| GET | `/api/tags?actor=&userId=&channel=&key=` | 读取标签（需鉴权） |
| POST | `/api/consent/{userId}` | 隐私授权 |
| GET | `/api/config` | 当前配置 |
| POST | `/api/config/reload` | 热加载配置（非法配置返回 400 与逐项原因） |

## 9. 测试覆盖

- `AttributionModelTest`：首次/末次/时间加权语义、窗口边界、跨实例可复现。
- `DedupAndLateEventTest`：重复去重、乱序到达、迟到事件修正与差异追溯、窗外事件不修正。
- `IdentityResolutionTest`：匿名事件标记、跨设备/渠道身份合并、不可关联事件不丢弃。
- `SnapshotConcurrencyTest`：多线程并发摄入与查询，事件不丢、快照自洽、版本单调；并验证采集与修订历史读取交错时，固定版本的快照、对账、修订完全一致，历史稳定只追加。
- `TagStoreTest`：优先级覆盖、同优先级新写入胜、幂等、缺少来源、写入时间相同、多用户多渠道并发写入、最终胜者与串行确定性合并一致且为完整对象。
- `PrivacyAndReconciliationTest`：未授权不进入归因但可对账、授权后重算、越权读取拒绝、对账恒等式。
- `ConfigValidationTest`：非法/冲突配置逐项拒绝、热加载确定性重算、失败保持原配置。
- `AttributionApiTest`：REST 全流程（摄入、对账、快照、403 鉴权、400 非法参数、标签、配置热加载）。
