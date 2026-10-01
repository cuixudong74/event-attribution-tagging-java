# 投放事件归因与标签化服务

对曝光（IMPRESSION）、点击（CLICK）、转化（CONVERSION）三类事件进行归因，
并按用户与渠道维护标签。设计目标：乱序 / 迟到 / 重复 / 并发场景下结论可信、可复现、可追溯。

## 快速开始

```bash
mvn compile        # 编译
mvn test           # 运行全部自动化测试
mvn spring-boot:run # 启动服务（端口见 application.properties，默认随机端口）
```

## 归因语义

### 事件与身份关联

- 每个事件携带 `eventId`（全局唯一，用于去重）、`eventTime`（事件发生时间）、
  `userId` / `deviceId`（身份线索）、`channel`、`consented`（隐私授权）。
- 身份归一（`IdentityGraph`）：`userId` 与 `deviceId` 视为身份线索，用并查集归并。
  同一用户跨设备（同 userId）、跨渠道（渠道不参与身份）的行为自动关联；
  后续到达的“桥接事件”（同时携带某 userId 与 deviceId）会把此前分离的行为归并到同一身份。
  身份键优先取 `u:<userId>`（登录态更稳定），否则取字典序最小的线索，与归并顺序无关。
- 完全无身份线索的事件**不静默丢弃**：归入 `NO_IDENTITY:<eventId>` 的独立身份键，
  可区分、可追踪。

### 去重、乱序与迟到修正

- 按 `eventId` 去重：重复上报（包括并发重复）只计入一次。
- 事件排序与到达顺序无关：按 `(eventTime, ingestSeq, eventId)` 确定性排序，
  乱序到达不影响最终结论（有测试验证两种相反到达顺序结论一致）。
- 迟到 / 乱序事件到达后，对受影响身份下的全部转化**重算归因**：
  - 结论变化时版本号递增，并记录 `AttributionCorrection`
    （修正前 / 修正后 / 触发事件 / 原因 / 时间），可通过
    `GET /api/attributions/{conversionEventId}/corrections` 追溯；
  - 只重算受影响身份，复杂度可控。

### 归因模型

| 模型 | 语义 |
|---|---|
| `FIRST_TOUCH` | 窗口内最早触点独占权重 1.0 |
| `LAST_TOUCH` | 窗口内最晚触点独占权重 1.0 |
| `TIME_DECAY` | 权重 ∝ 0.5^(距转化时长 / 半衰期)，归一化使权重和为 1 |

触点须满足：`eventTime <= 转化时间` 且距转化不超过归因窗口，否则不参与。
无合格触点时结论为未归因（`contributions` 为空），转化本身仍保留。

### 可复现性

归因引擎为纯函数：同一批输入事件 + 同一份配置，任意时刻、任意到达顺序下
查询到的结论一致。所有共享状态不可变或在锁内拷贝快照后返回。

## 配置项

配置通过 `POST /api/config` 整体替换，加载时校验，非法配置拒绝并保持旧配置。

| 字段 | 含义 | 约束 |
|---|---|---|
| `modelType` | 归因模型 | 必填，`FIRST_TOUCH` / `LAST_TOUCH` / `TIME_DECAY` |
| `attributionWindow` | 归因窗口（ISO-8601 时长，如 `P30D`） | 必须为正，且不超过 365 天 |
| `timeDecayHalfLife` | 时间衰减半衰期 | `TIME_DECAY` 必填且为正；不得大于归因窗口（冲突拒绝） |
| `version` | 配置版本号 | 非负 |

**配置变更对在途事件的行为**：每个事件按其**到达时**生效的配置处理；
已产出的结论不因配置变更而被动重写，仅在后续事件触发重算时采用新配置。
该行为是确定的：配置读取与事件处理在同一把写锁内完成。

## 并发与一致性

- 摄入持写锁、查询持读锁（`ReentrantReadWriteLock`）：
  查询期间到达的事件在锁释放后生效，**不丢失**；
  查询读到的是某一时刻**自洽的快照**（如权重和恒为 1），不会读到中间状态。
- 事件存储在读锁内拷贝并排序后返回不可变快照。

## 标签语义

- 标签按 `(identityKey, channel)` 维度隔离维护。
- 覆盖规则：`priority` 大者覆盖小者；同优先级**后写覆盖先写**（以单调写入序号定序）；
  低优先级写入被拒绝（返回 `applied=false`）。
- 重复写入（同 key/value/priority）幂等，状态不变。
- 并发更新在锁内比较-交换，不会留下互相矛盾的标签（有 16 线程并发测试验证）。

## 隐私与对账

- 未授权（`consented=false`）事件**不进入归因**，但计入总量：
  `GET /api/reconciliation` 返回 `totalEvents / consentedEvents / nonConsentedEvents`，
  满足 `total = consented + nonConsented`，可对账。
- 用户级明细 `GET /api/users/{userId}/events` 必须携带请求头
  `X-Access-Token: admin-read-token`，否则返回 403。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/events` | 摄入事件，返回 `accepted`（重复/未授权为 false） |
| GET | `/api/attributions/{conversionEventId}` | 单个转化的归因结论 |
| GET | `/api/attributions` | 全部归因结论快照 |
| GET | `/api/attributions/{id}/corrections` | 修正历史（可追溯） |
| POST / GET | `/api/config` | 更新 / 查看配置（非法配置 400 并给出原因） |
| POST / GET | `/api/tags` | 写入 / 查询标签 |
| GET | `/api/reconciliation` | 总量对账 |
| GET | `/api/users/{userId}/events` | 用户级明细（需授权令牌） |

## 测试覆盖

`src/test/.../tests/` 下 30 个用例：

- `AttributionEngineTest`：三种模型、窗口过滤、转化后触点排除、可复现性；
- `IngestionServiceTest`：重复上报、乱序、迟到修正、跨设备关联、身份桥接、
  无身份标记、未授权过滤与对账、越权拒绝、到达顺序无关性；
- `ConfigValidationTest`：非法与冲突配置拒绝、失败保持旧配置、原子生效；
- `TagServiceTest`：优先级覆盖、同优先级后写胜出、幂等、维度隔离、并发一致性；
- `ConcurrencyTest`：并发采集与查询（事件不丢失、快照自洽）、并发重复上报只计一次。
