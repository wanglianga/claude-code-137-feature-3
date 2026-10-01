# 社区药箱过期药回收称重兑换与环保交接服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的社区过期药品回收服务，覆盖完整链路：

```
居民预约 -> 回收员扫码登记 -> 分类称重封袋 -> 高风险人工复核 -> 积分发放
        -> 暂存柜暂存(容量/超期预警) -> 环保交接(上门量/双人签收/拒收) -> 批次追溯
```

预约、称重、复核、积分、暂存、交接、异常、**积分发放失败补发**与**拒收冻结/追缴**全部挂在**同一批次（RecycleBatch）**上，交接前每袋药品都有来源（预约/批次）与分类。

## 功能清单

| 模块 | 说明 |
| --- | --- |
| 预约 | 记录药品类型、数量、是否含处方药/液体/针剂/个人信息包装、社区点、活动日；预约时固化**积分规则快照** |
| 登记称重 | 回收员扫码登记生成批次；按 普通片剂/抗生素/精神类/注射器/外用药/液体药 六类称重，记录包装照片与封袋编号 |
| 人工复核 | 高风险品类（精神类、注射器）及异常触发的袋先复核，通过才发积分、才准入柜 |
| 积分 | 按品类每公斤积分 × 活动日倍率发放；流水可查；兑换走悲观锁应对峰值；失败落 FAILED 兑换单 |
| **发放失败补发** | 称重后积分发放失败（账户冻结/账务故障等）自动落**发放失败单**：固化失败原因、积分规则快照、药品批次与居民账户状态；客服复核后可**补发积分 / 改纸质兑换券 / 驳回异常积分**；处置幂等防重发，详情回显居民**是否已发生兑换**；补发后居民可在台账查看积分来源与处理状态 |
| **拒收冻结/追缴** | 批次被环保公司拒收后，该袋**已发积分**进入冻结：可用余额足够当场冻结，不足部分形成**追缴欠分**（居民后续入账自动抵扣，也可管理员主动追缴），偿清后冻结积分核销；社区管理员登记**拒收品类与处理依据**向居民说明；申诉成立可解除冻结退回积分 |
| 暂存柜 | 入柜容量强校验（悲观锁防超卖）、使用率预警（WARN/FULL）、超期暂存检查 |
| 环保交接 | 上门量阈值校验、含高风险或超重自动要求**双人签收**、环保公司逐袋签收/拒收 |
| 异常 | 夹带未过期药(扣回积分)、处方信息泄露、针剂未封装、液体泄漏、称重争议(复秤调账)、兑换失败、**积分发放失败**、公司拒收 |
| 看板 | 管理员查看超期药品、是否需要双人交接、是否达上门量、容量预警、待复核/未结异常数 |
| 追溯 | 批次时间线：预约→称重→封袋→复核→暂存→交接→积分流水→发放失败单→冻结/追缴→异常 |

## 本地启动

### 方式 A：Docker 一键启动（推荐）

```bash
docker compose up --build
```

启动 PostgreSQL 与服务两个容器；服务首次启动自动建表（`ddl-auto: update`）并写入演示数据。
健康检查：`curl http://localhost:8080/api/health`

### 方式 B：本地 PostgreSQL + Maven

```bash
# 1) 准备数据库
docker compose up -d db   # 或使用已有 PostgreSQL 手工建库：
psql -U postgres -c "CREATE DATABASE medrecycle"
psql -U postgres -c "CREATE USER medrecycle WITH PASSWORD 'medrecycle'"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE medrecycle TO medrecycle"
psql -U postgres -d medrecycle -c "GRANT ALL ON SCHEMA public TO medrecycle"

# 2) 启动服务（默认 jdbc:postgresql://localhost:5432/medrecycle，可用环境变量覆盖）
DB_URL=jdbc:postgresql://localhost:5432/medrecycle \
DB_USER=medrecycle DB_PASSWORD=medrecycle mvn spring-boot:run

# 或打包后运行
mvn package -DskipTests
java -jar target/med-recycle-1.0.0.jar
```

首次启动写入演示数据（站点/暂存柜/积分规则/礼品/测试账号/活动日，可用 `SEED_DEMO_DATA=false` 关闭）。

### OpenAPI 3 文档与 Swagger UI

服务为纯后端，内置可交互接口文档（业务接口仍需先登录并在 Swagger UI 点 **Authorize** 填 `X-Auth-Token`）：

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

## 测试账号

登录：`POST /api/auth/login` `{"username":"...","password":"..."}`，返回 `token`，后续请求带请求头 `X-Auth-Token: <token>`。

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| resident1 / resident2 | resident123 | 居民（预约、查积分/台账、兑换、查追溯） |
| recycler1 / recycler2 | recycler123 | 回收员（登记、称重、入柜、交接签收） |
| reviewer1 | reviewer123 | 复核员（高风险复核） |
| service1 | service123 | **客服（积分发放失败复核：补发/纸质券/驳回，追缴）** |
| admin1 / admin2 | admin123 | 社区管理员（看板、发起交接、账户冻结/解冻、登记拒收依据、追缴/解冻） |

演示数据：2 个社区点、3 个暂存柜、6 条品类积分规则（精神类/注射器为高风险）、3 种礼品、1 个积分 1.5 倍的活动日；resident1 预置 100 积分。

## 验证方式（均已实际执行通过）

### 1. 集成测试（PostgreSQL 15 实测，12 个用例）

```bash
# 测试库：默认 jdbc:postgresql://localhost:5432/medrecycle_test，可用 TEST_DB_URL 覆盖
psql -U postgres -c "CREATE DATABASE medrecycle_test OWNER medrecycle"
mvn test
```

- `FullFlowIntegrationTest`：预约→登记→称重(含高风险)→复核→积分→暂存→双人交接→完成→追溯全链路
- `ExceptionFlowIntegrationTest`：夹带未过期药扣回并复核拒绝、称重争议复秤调账、环保公司拒收回柜并记录异常
- `ConcurrencyAndCapacityTest`：20 线程并发兑换 5 件库存不超卖、暂存柜容量强制与 FULL 预警
- `PointsReissueIntegrationTest`（本次新增 6 例）：
  账户冻结致发放失败落单（含原因/规则/批次/账户快照、自动登记异常）→ 解冻后客服补发、冻结期补发与**重复补发被 409 拦截**；
  改纸质兑换券（不入账户、线下兑付防重复）；驳回异常积分；
  拒收后余额不足形成追缴欠分、主动追缴、后续入账自动抵扣直至全额核销；
  余额充足当场冻结、申诉成立解冻退回；客服详情可见居民**是否已兑换**、居民台账可见来源与状态

本次验证执行结果：`Tests run: 12, Failures: 0, Errors: 0`（PostgreSQL 15 实测）。

### 2. 端到端脚本（HTTP 全流程，90 项断言）

```bash
# 服务运行中执行（断言基于演示数据初始状态，重复执行前请重建数据库 medrecycle）
./verify.sh
```

脚本用 curl 走通：登录→预约(活动日)→登记→称重(3 类)→高风险复核→积分→入柜→看板→上门量拦截→双人签收→交接完成→追溯→称重争议复秤→夹带未过期药扣回→公司拒收→积分兑换成功/失败，
以及本次新增的 **账户冻结致发放失败→客服补发(防重发)、纸质券兑付、拒收冻结→追缴欠分自动抵扣核销、申诉解冻退回**，共 90 项断言。
本次验证执行结果：`通过 90 项, 失败 0 项`。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录取 token |
| POST | /api/appointments | 居民预约（含四类标识、活动日） |
| POST | /api/batches/check-in | 扫码登记生成批次 |
| POST | /api/batches/{no}/weigh | 分类称重封袋（照片、封袋编号、争议标记） |
| POST | /api/batches/{no}/finish-weighing | 称重完成 |
| GET | /api/batches/{no}/trace | 批次全链路追溯（含发放失败单、冻结/追缴） |
| GET/POST | /api/reviews, /api/reviews/{no}/decide | 复核列表与审批 |
| GET | /api/points/balance, /account, /ledger | 余额 / 账户(可用+冻结+状态) / 居民积分台账 |
| GET/POST | /api/points/transactions, /exchanges, /exchange | 流水、兑换记录、兑换 |
| **GET** | **/api/points-issues?status=** | **客服/管理员：发放失败待办（可按状态过滤）** |
| **GET** | **/api/points-issues/mine, /{no}** | **居民：本人失败单；详情附是否已兑换（防重发参考）** |
| **POST** | **/api/points-issues/{no}/reissue /voucher /reject** | **客服：补发积分 / 改纸质券 / 驳回（幂等）** |
| **GET** | **/api/vouchers/mine, /{no}** | **居民纸质券查询** |
| **POST** | **/api/vouchers/{no}/redeem** | **社区点登记纸质券线下兑付（防重复）** |
| **GET** | **/api/points-freezes, /mine, /{no}** | **冻结/追缴单：工作人员全部、居民本人** |
| **POST** | **/api/points-freezes/{no}/basis** | **管理员登记拒收品类与处理依据** |
| **POST** | **/api/points-freezes/{no}/recover, /release** | **主动追缴 / 申诉成立解冻退回** |
| **POST** | **/api/admin/points-accounts/freeze, /unfreeze** | **管理员冻结/解冻居民积分账户** |
| POST | /api/storage/put | 入暂存柜（容量强校验） |
| GET | /api/storage/cabinets, /overdue, /alerts | 柜状态/超期/容量预警 |
| POST | /api/handovers, /{no}/sign, /{no}/complete | 交接建单/签收(双人)/完成(拒收) |
| GET | /api/handovers/{no} | 交接单详情（去向/重量/照片/签收人/积分结果） |
| POST | /api/exceptions, /{no}/reweigh, /{no}/resolve | 异常上报/复秤/办结 |
| GET | /api/admin/dashboard?siteId= | 管理员看板 |

## 关键设计

- **批次状态机**：`CHECKED_IN → WEIGHING → IN_REVIEW → POINTS_GRANTED → STORED → (PARTIALLY_)HANDED_OVER → CLOSED`，由袋状态自动推导（`BatchRefresher`），预约随批次终态完结。
- **积分规则快照**：预约时固化 `TABLET:10,ANTIBIOTIC:15,...`，后续称重/复秤/补发均按快照计价，规则调整不影响在途批次。
- **并发安全**：积分兑换（账户+礼品悲观锁，固定加锁顺序）、暂存柜入柜（柜悲观锁）、失败单/冻结单处置（行悲观锁 + 每袋唯一单，天然**幂等防重复补发**）、活动日名额（乐观锁重试）。
- **发放失败不阻断回收**：发放单袋积分是唯一入口 `grantBagPoints`；账户冻结在发放**前置只读校验**中判定（避免参与事务异常把称重事务污染为 rollback-only），其余账务异常兜底落失败单，称重/复核照常推进。
- **失败单是处置事实源**：`points_issue` 固化失败原因、规则快照、批次/袋、活动倍率与失败时账户（状态+余额），并自动登记 `POINTS_GRANT_FAILED` 异常；补发/转券/驳回后联动办结异常。每袋至多一单（`bagNo` 唯一）。
- **拒收冻结/追缴账务模型**：账户区分 `balance`（可用）与 `frozenBalance`（冻结）。拒收按袋的 `grantedPoints`（实际已发，驳回未发为 0）发起冻结，`min(可用,应冻)` 转入冻结、不足记 `outstandingPoints` 欠分；居民**任何后续入账（发放/补发）先自动抵扣最旧欠分**，偿清后冻结积分 `RECOVER` 核销（不退居民）；申诉成立 `UNFREEZE` 退回可用。
- **对居民可解释**：冻结单记录拒收品类与处理依据，居民经 `/api/points-freezes/mine`、`/api/points/ledger` 与批次追溯可查看每笔积分来源、冻结/追缴状态及依据；纸质券支持线下等值兑付。
