# 社区药箱过期药回收称重兑换与环保交接服务

基于 **Java 17 + Spring Boot 3 + PostgreSQL** 的社区过期药品回收服务，覆盖完整链路：

```
居民预约 -> 回收员扫码登记 -> 分类称重封袋 -> 高风险人工复核 -> 积分发放（失败可补发）
        -> 暂存柜暂存(容量/超期预警) -> 环保交接(上门量/双人签收/拒收冻结追缴) -> 批次追溯
```

预约、称重、复核、积分、暂存、交接与异常全部挂在**同一批次（RecycleBatch）**上，交接前每袋药品都有来源（预约/批次）与分类。

## 功能清单

| 模块 | 说明 |
| --- | --- |
| 预约 | 记录药品类型、数量、是否含处方药/液体/针剂/个人信息包装、社区点、活动日；预约时固化**积分规则快照** |
| 登记称重 | 回收员扫码登记生成批次；按 普通片剂/抗生素/精神类/注射器/外用药/液体药 六类称重，记录包装照片与封袋编号 |
| 人工复核 | 高风险品类（精神类、注射器）及异常触发的袋先复核，通过才发积分、才准入柜 |
| 积分 | 按品类每公斤积分 × 活动日倍率发放；流水可查；兑换走悲观锁应对峰值；失败落 FAILED 兑换单并自动登记异常 |
| **积分发放失败补发** | 居民完成称重后若积分未到账（账户冻结/不存在等），**不回滚称重**，自动落发放失败单：失败原因、积分规则快照与倍率、批次/封袋（药品批次）、发放时居民账户状态。客服复核后可**补发积分**、**改为纸质兑换券**或**驳回异常积分**；失败单状态机+袋标志双重幂等防重复补发，并显示补发积分是否**已被居民兑换** |
| 暂存柜 | 入柜容量强校验（悲观锁防超卖）、使用率预警（WARN/FULL）、超期暂存检查 |
| 环保交接 | 上门量阈值校验、含高风险或超重自动要求**双人签收**、环保公司逐袋签收/拒收 |
| **拒收冻结/追缴** | 批次被环保公司拒收时，已发积分自动**冻结**（可兑换额度扣除，总余额不变）；积分已被居民兑换的部分登记**欠费**，后续积分自动冲抵、欠费期间禁止兑换。社区管理员复核后可**追缴**或**解冻（申述成立）**，必须登记**处理依据**并生成给居民的告知（拒收品类+依据） |
| 异常 | 夹带未过期药(扣回积分)、处方信息泄露、针剂未封装、液体泄漏、称重争议(复秤调账)、兑换失败(自动落单)、公司拒收(冻结追缴) |
| 看板 | 管理员查看超期药品、是否需要双人交接、是否达上门量、容量预警、待复核/未结异常数、待补发积分单数 |
| 居民侧 | 积分**来源视图**（每袋药品品类/重量/适用积分规则/处理状态：正常·冻结·已追缴·纸质券·待补发）、本人异常告知（拒收品类与处理依据）、失败单处理状态 |
| 追溯 | 批次时间线：预约→称重→封袋→复核→暂存→交接→积分流水→异常，交接单含去向/重量/照片/签收人/积分发放结果 |

## 一键启动（Docker）

```bash
# 在项目根目录执行：自动构建应用镜像并启动 PostgreSQL + 服务
docker compose up -d --build

# 健康检查
curl http://localhost:8080/api/health
```

首次启动自动建表（`ddl-auto: update`）并写入演示数据（站点/暂存柜/积分规则/礼品/测试账号/活动日）。
停止清理：`docker compose down`（加 `-v` 同时删除数据库卷）。

## 本地开发启动

### 1. 准备 PostgreSQL

```bash
# 方式 A：使用 Docker（仅数据库）
docker compose up -d db

# 方式 B：使用已有 PostgreSQL，手工建库
psql -U postgres -c "CREATE DATABASE medrecycle"
psql -U postgres -c "CREATE USER medrecycle WITH PASSWORD 'medrecycle'"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE medrecycle TO medrecycle"
psql -U postgres -d medrecycle -c "GRANT ALL ON SCHEMA public TO medrecycle"
```

### 2. 启动服务

```bash
# 默认连接 jdbc:postgresql://localhost:5432/medrecycle，可用环境变量覆盖
DB_URL=jdbc:postgresql://localhost:5432/medrecycle \
DB_USER=medrecycle \
DB_PASSWORD=medrecycle \
mvn spring-boot:run

# 或打包后运行
mvn package -DskipTests
java -jar target/med-recycle-1.0.0.jar
```

启动后表结构自动创建，首次启动写入演示数据（可用 `SEED_DEMO_DATA=false` 关闭）。

健康检查：`curl http://localhost:8080/api/health`

### API 文档（Swagger UI）

服务启动后访问：

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

在 Swagger UI 右上角 Authorize 填入登录返回的 token（以请求头 `X-Auth-Token` 发送）。

## 测试账号

登录：`POST /api/auth/login` `{"username":"...","password":"..."}`，返回 `token`，后续请求带请求头 `X-Auth-Token: <token>`。

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| resident1 / resident2 | resident123 | 居民（预约、查积分来源、兑换、查拒收告知） |
| recycler1 / recycler2 | recycler123 | 回收员（登记、称重、入柜、交接签收） |
| reviewer1 | reviewer123 | 复核员（高风险复核） |
| service1 | service123 | **客服（积分发放失败复核：补发/纸质券/驳回、账户冻结解冻）** |
| admin1 / admin2 | admin123 | 社区管理员（看板、发起交接、拒收积分追缴/解冻与告知） |

演示数据：2 个社区点、3 个暂存柜、6 条品类积分规则（精神类/注射器为高风险）、3 种礼品、1 个积分 1.5 倍的活动日；resident1 预置 100 积分。

## 验证方式（均已实际执行通过）

### 1. 集成测试（PostgreSQL 15 实测，4 个测试类 15 个用例）

```bash
# 测试库：默认 jdbc:postgresql://localhost:5432/medrecycle_test，可用 TEST_DB_URL 覆盖
psql -U postgres -c "CREATE DATABASE medrecycle_test OWNER medrecycle"
mvn test
```

- `FullFlowIntegrationTest`：预约→登记→称重(含高风险)→复核→积分→暂存→双人交接→完成→追溯全链路断言
- `ExceptionFlowIntegrationTest`：夹带未过期药扣回积分并复核拒绝、称重争议复秤调账、环保公司拒收回柜并记录异常
- `ConcurrencyAndCapacityTest`：20 线程并发兑换 5 件库存不超卖（成功恰为 5、余额精确）、暂存柜容量强制与 FULL 预警
- `PointsGrantFailureIntegrationTest`（本次新增，9 用例）：
  账户冻结致发放失败并留存失败原因/规则快照/账户状态 → 解冻后客服补发 → 重复补发 409 幂等 → 补发积分已兑换标志；
  改纸质兑换券（券号、不入电子账户）；驳回异常积分须填说明；
  拒收已发积分自动冻结 → 管理员追缴（余额扣回、处理依据、居民告知）、缺依据 400、已办结 409；
  积分已兑换时拒收转欠费、欠费禁止兑换、新积分自动冲抵；申述成立解冻（含豁免欠费）；
  发放失败后被复核拒绝时失败单自动驳回、防止误补发；居民积分来源视图展示品类/规则/冻结状态/拒收信息。

本次验证执行结果：`Tests run: 15, Failures: 0, Errors: 0`（PostgreSQL 15 实测）。

### 2. 端到端脚本（HTTP 全流程，86 项断言）

```bash
# 服务运行中执行（断言基于演示数据初始状态，重复执行前请重建数据库 medrecycle）
./verify.sh
```

脚本用 curl 走通：登录（五类角色）→预约(活动日)→登记→称重(3 类)→高风险复核→积分→入柜→看板→上门量拦截→双人签收→交接完成→追溯→称重争议复秤→夹带未过期药扣回→公司拒收→积分兑换成功/失败，以及本次新增：

- **§15** 冻结致发放失败单（规则快照/账户状态）→ 解冻后客服补发 → 重复补发 409 → 补发积分已兑换标志；
- **§16** 拒收冻结（冻结额/可用额/来源视图）→ 管理员追缴（无依据 400、告知含品类与依据、办结 409 幂等）；
- **§17** 申述成立解冻恢复、兑换失败自动登记异常、看板待补发计数；
- **§18** 发放失败改纸质兑换券（券号可查）、驳回异常积分须填说明。

本次验证执行结果：`通过 86 项, 失败 0 项`。

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login | 登录取 token |
| POST | /api/appointments | 居民预约（含四类标识、活动日） |
| POST | /api/batches/check-in | 扫码登记生成批次 |
| POST | /api/batches/{no}/weigh | 分类称重封袋（照片、封袋编号、争议标记） |
| POST | /api/batches/{no}/finish-weighing | 称重完成 |
| GET | /api/batches/{no}/trace | 批次全链路追溯 |
| GET/POST | /api/reviews, /api/reviews/{no}/decide | 复核列表与审批 |
| GET | /api/points/balance, /api/points/transactions | 积分余额（含冻结/可用/欠费）与流水 |
| GET | /api/points/sources | **居民积分来源与处理状态、拒收告知** |
| POST | /api/points/exchange, GET /api/points/exchanges | 积分兑换（失败自动落异常）与兑换记录 |
| POST | /api/points/accounts/{id}/status | 管理员/客服冻结、解冻居民账户 |
| GET | /api/grant-failures, /{no} | **客服/居民查看积分发放失败单与复核详情（含是否已兑换）** |
| POST | /api/grant-failures/{no}/reissue | **客服补发积分** |
| POST | /api/grant-failures/{no}/paper-voucher | **改为纸质兑换券（可指定券号）** |
| POST | /api/grant-failures/{no}/reject | **驳回异常积分** |
| POST | /api/storage/put | 入暂存柜（容量强校验） |
| GET | /api/storage/cabinets, /api/storage/overdue, /api/storage/alerts | 柜状态/超期/容量预警 |
| POST | /api/handovers, /{no}/sign, /{no}/complete | 交接建单/签收(双人)/完成(拒收自动冻结) |
| GET | /api/handovers/{no} | 交接单详情（去向/重量/照片/签收人/积分结果） |
| GET | /api/exceptions, /api/exceptions/mine | 异常列表（工作人员）/ 居民本人异常告知 |
| POST | /api/exceptions, /{no}/reweigh, /{no}/resolve | 异常上报/复秤/办结 |
| POST | /api/exceptions/{no}/reject-handling | **管理员拒收积分追缴(CLAWBACK)/解冻(UNFREEZE)，必带处理依据** |
| GET | /api/admin/dashboard?siteId= | 管理员看板（含待补发积分单数） |

## 关键设计

- **批次状态机**：`CHECKED_IN → WEIGHING → IN_REVIEW → POINTS_GRANTED → STORED → (PARTIALLY_)HANDED_OVER → CLOSED`，由袋状态自动推导（`BatchRefresher`），预约随批次终态完结。
- **积分规则快照**：预约时固化 `TABLET:10,ANTIBIOTIC:15,...`，后续称重/复秤均按快照计价；发放失败单同样留存快照与活动倍率，规则调整不影响在途批次与补发金额。
- **发放失败不丢单**：称重成功与积分入账解耦——发放失败只落 `points_grant_failure`（原因、规则快照、批次封袋、账户状态快照），袋保持 `NOT_GRANTED`；客服三选一处置（补发 REISSUE 流水 / 纸质券 / 驳回），失败单状态与袋 `pointsState` 双重幂等。
- **拒收冻结追缴**：拒收只对已发积分生效，可用部分冻结（兑换校验改为 `余额-冻结额`），已被兑换部分转欠费；后续任何入账（含补发）优先冲抵欠费；管理员追缴/解冻都必须留处理依据并形成居民告知文本。
- **并发安全**：积分兑换（账户+礼品悲观锁，固定加锁顺序）、暂存柜入柜（柜悲观锁）、活动日名额（乐观锁重试），保证兑换峰值不超卖、容量不超卖。
- **异常联动**：夹带未过期药自动扣回积分并退回复核；液体泄漏/针剂未封装/处方信息泄露强制人工复核；称重争议复秤后按快照多退少补；公司拒收自动回柜、冻结已发积分并留痕；兑换失败自动落异常。
