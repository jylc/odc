# ODC REST 接口文档：数据库连接会话相关接口（ConnectSessionController）

> 本文档覆盖 `ConnectSessionController`（`@RequestMapping("/api/v2/datasource")`）下的全部 15 个接口，
> 包括请求参数、鉴权、响应结构与业务逻辑说明。
>
> 源码位置：`server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v2/ConnectSessionController.java`

---

## 目录

- [公共说明](#公共说明)
- [会话生命周期](#会话生命周期)
  - [1. 创建会话（按数据源）](#1-创建会话按数据源)
  - [2. 创建会话（按数据库）](#2-创建会话按数据库)
  - [3. 关闭会话](#3-关闭会话)
- [SQL 执行](#sql-执行)
  - [4. 流式执行 SQL](#4-流式执行-sql)
  - [5. 获取更多结果](#5-获取更多结果)
  - [6. 查询表/视图数据](#6-查询表视图数据)
  - [7. 停止当前查询](#7-停止当前查询)
- [SQL 静态检查](#sql-静态检查)
  - [8. 会话内 SQL 检查](#8-会话内-sql-检查)
  - [9. 多数据库 SQL 检查](#9-多数据库-sql-检查)
- [大字段与文件](#大字段与文件)
  - [10. 查看大字段内容](#10-查看大字段内容)
  - [11. 下载二进制数据](#11-下载二进制数据)
  - [12. 上传文件](#12-上传文件)
- [会话管理](#会话管理)
  - [13. 终止数据库会话/查询](#13-终止数据库会话查询)
  - [14. 查询当前会话状态](#14-查询当前会话状态)
  - [15. 分区计划预览](#15-分区计划预览)
- [汇总表](#汇总表)

---

## 公共说明

### 响应信封

所有接口返回统一的 `BaseResponse` 外层结构（与 v2 其他接口一致）：

```jsonc
{
  "code": null,            // 失败时为错误码对象
  "successful": true,
  "httpStatus": "OK",
  "timestamp": "2026-08-20T...",
  "durationMillis": 123,
  "traceId": "...",
  "requestId": "...",
  "server": "...",
  "data": { ... }          // 具体载荷，见各接口
}
```

### sessionId 格式与有状态路由

- 路径参数 `sessionId` 兼容两种格式：`sid:1000-1`（带前缀的资源 ID）或纯数字 ID，
  后端通过 `SidUtils.getSessionId()` 统一剥离去前缀（`common/util/SidUtils.java:26-31`）。
- 除"多库 SQL 检查"和"终止数据库会话"外，接口均标注
  `@StatefulRoute(stateName = StateName.DB_SESSION, stateIdExpression = "#sessionId")`：
  请求会被路由到**持有该会话内存状态的那个 ODC 节点**（多节点部署下生效，单节点无感知）。
- "关闭会话"使用 `multiState = true` + `connectSessionCloseStateManager`，按请求体内的多个
  sessionIds 分别路由。

### 鉴权

所有接口需要登录（IAM 认证）。资源级权限校验下沉到 Service 层
（如 `ConnectSessionService.createByDataSourceId` 会校验数据源访问权限），Controller 层无 `@PreAuthenticate`。

---

## 会话生命周期

### 1. 创建会话（按数据源）

`POST /api/v2/datasource/datasources/{dataSourceId}/sessions`

由数据源 ID 创建一个到目标库的连接会话，会话的默认 schema 取数据源配置。

- **路径参数**：`dataSourceId`（Long，必填）— 数据源 ID
- **请求体**：无
- **响应 `data`**：`CreateSessionResp`

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | String | 会话 ID，后续接口的 `sessionId` 入参 |
| `dataTypeUnits` | List\<DataTypeUnit\> | 数据类型单元（编辑器提示用） |
| `supports` | List\<OBSupport\> | 目标库能力集 |
| `charsets` | List\<String\> | 可用字符集 |
| `collations` | List\<String\> | 可用排序规则 |

> 源码：`ConnectSessionController.java:96-100`

### 2. 创建会话（按数据库）

`POST /api/v2/datasource/databases/{databaseId}/sessions?recordDbAccessHistory=false`

由数据库（`connect_database` 记录）ID 创建会话，会话直接绑定到该库对应的 schema。

- **路径参数**：`databaseId`（Long，必填）
- **Query 参数**：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `recordDbAccessHistory` | Boolean | 否 | `false` | 是否记录数据库访问历史 |

> ⚠️ **注意**：当前实现中该参数声明后并未传入 Service 层
> （`ConnectSessionController.java:104-107` 只调用了 `createByDatabaseId(databaseId)`），
> 属于预留参数，实际不生效。

- **响应 `data`**：`CreateSessionResp`（同接口 1）

### 3. 关闭会话

`DELETE /api/v2/datasource/sessions`

批量关闭连接会话（对应 v1 的 `/api/v1/session/close/{sid}`）。

- **请求体**：`MultiSessionsReq`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionIds` | Set\<String\> | 是 | 会话 ID 集合，元素兼容 `sid:1000-1` 格式 |

- **响应 `data`**：`Set<String>` — 成功关闭的会话 ID 列表
- **行为**：等待最多 60 秒（`sessionService.close(sessionIds, 60, SECONDS)`），
  超时未完成的会话仍会被强制回收。

> 源码：`ConnectSessionController.java:229-237`

---

## SQL 执行

### 4. 流式执行 SQL

`POST /api/v2/datasource/sessions/{sessionId}/sqls/streamExecute`

在会话中异步执行 SQL 脚本（支持多条语句拆分），返回执行请求 ID，结果通过"获取更多结果"接口分批拉取。

- **请求体**：`SqlAsyncExecuteReq`

| 字段 | 类型 | 说明 |
|---|---|---|
| `sql` | String | SQL 脚本内容 |
| `queryLimit` | Integer | 最大返回行数 |
| `autoCommit` | Boolean | 是否自动提交 |
| `split` | Boolean | 是否按分隔符拆分为多条语句 |
| `addROWID` | Boolean | 结果集中是否附加 ROWID 列 |
| `showTableColumnInfo` | Boolean | 是否返回表列信息 |
| `fullLinkTraceEnabled` | Boolean | 是否启用全链路诊断 |
| `continueExecutionOnError` | Boolean | 单条失败后是否继续 |

- **响应 `data`**：`SqlAsyncExecuteResp`

| 字段 | 类型 | 说明 |
|---|---|---|
| `requestId` | String | 异步执行请求 ID，用于接口 5 拉取结果 |
| `violatedRules` | List\<Rule\> | 命中的 SQL 检查规则 |
| `sqls` | List\<SqlTuplesWithViolation\> | 拆分后的 SQL 及其违规信息 |
| `unauthorizedDBResources` | List | 未授权访问的库/表（权限预检） |
| `logicalSql` | boolean | 是否逻辑库 SQL |
| `approvalRequired` | boolean | 是否需要走审批流 |

> 源码：`ConnectSessionController.java:109-114`

### 5. 获取更多结果

`GET /api/v2/datasource/sessions/{sessionId}/sqls/getMoreResults?requestId={requestId}`

拉取异步执行的增量结果（流式分批）。

- **Query 参数**：`requestId`（String，必填）— 接口 4 返回的请求 ID
- **响应 `data`**：`AsyncExecuteResultResp`

| 字段 | 类型 | 说明 |
|---|---|---|
| `results` | List\<SqlExecuteResult\> | 本批执行结果 |
| `traceId` / `sqlId` | String | 追踪 ID / SQL 标识 |
| `total` / `count` | int | 总语句数 / 已返回数 |
| `finished` | boolean | 是否已全部返回 |

`SqlExecuteResult` 关键字段（完整定义见 `session/model/SqlExecuteResult.java`，共 40+ 字段）：
`columnLabels`、`columns`、`rows`、`status`、`sqlType`、`total`、`errorCode`、`types`、
`dbObjectType/dbObjectName`、`existWarnings`、`checkViolations`、`dbmsOutput` 等。

### 6. 查询表/视图数据

`POST /api/v2/datasource/sessions/{sessionId}/queryData`

按表或视图名直接取数（SQL 窗口"查看数据"入口），后端自动生成查询 SQL。

- **请求体**：`QueryTableOrViewDataReq`

| 字段 | 类型 | 说明 |
|---|---|---|
| `schemaName` | String | schema 名 |
| `tableOrViewName` | String | 表或视图名 |
| `queryLimit` | Integer | 返回行数上限 |
| `addROWID` | boolean | 是否附加 ROWID |

- **响应 `data`**：`SqlExecuteResult`（同接口 5 说明）

> 源码：`ConnectSessionController.java:239-245`

### 7. 停止当前查询

`PUT /api/v2/datasource/sessions/{sessionId}/killQuery`

终止会话当前正在执行的 SQL（不关闭会话）。

- **响应 `data`**：`Boolean` — 是否成功发起终止

---

## SQL 静态检查

### 8. 会话内 SQL 检查

`POST /api/v2/datasource/sessions/{sessionId}/sqlCheck`

在既有会话上下文中对脚本做静态检查（语法/规范/风险规则）。

- **请求体**：`SqlCheckReq`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `scriptContent` | String | 否 | 待检查脚本 |
| `delimiter` | String | 是 | 语句分隔符（如 `;`） |
| `schemaName` | String | 否 | 目标 schema |
| `connectionId` | Long | 否 | 关联连接 ID |

- **响应 `data`**：`SqlCheckResponse<CheckResult>` — 每条语句的检查结果与违规描述

### 9. 多数据库 SQL 检查

`POST /api/v2/datasource/sessions/sqlCheck`

不依赖会话，一次对多个数据库执行同一脚本的静态检查（无 `@StatefulRoute`）。

- **请求体**：`MultipleSqlCheckReq`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `databaseIds` | List\<Long\> | 是（@NotEmpty） | 数据库 ID 列表 |
| `scriptContent` | String | 否 | 待检查脚本 |
| `delimiter` | String | 是 | 语句分隔符 |

- **响应 `data`**：`ListData<MultipleSqlCheckResult>` — 每个数据库一份检查结果

---

## 大字段与文件

### 10. 查看大字段内容

`GET /api/v2/datasource/sessions/{sessionId}/sqls/{sqlId}/content`

分页查看结果集中 BLOB/CLOB 等大字段的内容片段。

- **路径参数**：`sqlId`（String）— 由执行结果提供的 SQL 标识
- **Query 参数**：

| 参数 | 类型 | 必填 | 默认值 | 约束 | 说明 |
|---|---|---|---|---|---|
| `row` | Long | 是 | — | — | 行号（从 0 开始） |
| `col` | Integer | 是 | — | — | 列号（从 0 开始） |
| `skip` | Long | 否 | `0` | ≥0 | 跳过字节数（**单位 KB**） |
| `len` | Integer | 否 | `4` | ≤48×1024 | 读取长度（**单位 KB**） |
| `format` | ValueEncodeType | 否 | `TXT` | `TXT`/`HEX` | 编码展示形式 |

- **响应 `data`**：`BinaryContent` = `{ content: String, displayType: ValueEncodeType, size: long }`

### 11. 下载二进制数据

`GET /api/v2/datasource/sessions/{sessionId}/sqls/{sqlId}/download?row=&col=`

以文件流形式下载大字段完整内容。**注意：该接口直接返回二进制流（`ResponseEntity<InputStreamResource>`），不套响应信封。**

- **Query 参数**：`row`（Long，必填）、`col`（Integer，必填）

### 12. 上传文件

`POST /api/v2/datasource/sessions/{sessionId}/upload`

会话级通用文件上传（multipart，用于导入数据等场景）。

- **请求体**：`multipart/form-data`，字段名 `file`
- **响应 `data`**：`String` — 上传后的文件名

> Controller 方法签名用 `@RequestBody MultipartFile`，前端需以 multipart 表单提交。

---

## 会话管理

### 13. 终止数据库会话/查询

`POST /api/v2/datasource/sessions/killSession`

在**目标数据库**层面终止会话或其当前查询（DBA 会话管理场景，非 ODC 自身会话；无 `@StatefulRoute`）。

- **请求体**：`KillSessionOrQueryReq`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionIds` | List\<String\> | 是 | 目标库会话 ID 列表 |
| `datasourceId` | String | 是 | 所属数据源 ID |
| `killType` | String | 否 | `"session"`=终止会话（常量 `KILL_SESSION_TYPE`），`"query"`=仅终止当前查询（`KILL_QUERY_TYPE`），见 `KillSessionOrQueryReq.java:27-28` |

- **响应 `data`**：`List<KillResult>` = `[{ sessionId, killed, errorMessage }]`

### 14. 查询当前会话状态

`GET /api/v2/datasource/sessions/{sessionId}/status`

查询当前 ODC 连接会话对应的数据库会话信息。

- **响应 `data`**：`DBSessionResp`

| 字段 | 类型 | 说明 |
|---|---|---|
| `session` | DBSessionRespDelegate | 数据库会话详情（含 `killCurrentQuerySupported` 等能力标记） |
| `settings` | SessionSettings | 会话变量设置 |

### 15. 分区计划预览

`POST /api/v2/datasource/sessions/{sessionId}/partitionPlans/latest/preview`

基于会话最新分区计划模板，为指定表生成分区 DDL 预览。

- **请求体**：`PartitionPlanPreviewReq`

| 字段 | 类型 | 说明 |
|---|---|---|
| `tableNames` | List\<String\> | 待预览的表名 |
| `template` | PartitionPlanTableConfig | 分区计划模板配置 |
| `onlyForPartitionName` | boolean | 是否仅生成分区名预览 |

- **响应 `data`**：`ListData<PartitionPlanPreViewResp>` — 每张表的 DDL 预览

> 源码：`ConnectSessionController.java:254-260`

---

## 汇总表

| # | 方法 | 路径 | 功能 | 有状态路由 |
|---|---|---|---|---|
| 1 | POST | `/api/v2/datasource/datasources/{dataSourceId}/sessions` | 按数据源创建会话 | 否 |
| 2 | POST | `/api/v2/datasource/databases/{databaseId}/sessions` | 按数据库创建会话 | 否 |
| 3 | DELETE | `/api/v2/datasource/sessions` | 批量关闭会话 | 是（multi） |
| 4 | POST | `/api/v2/datasource/sessions/{sessionId}/sqls/streamExecute` | 流式执行 SQL | 是 |
| 5 | GET | `/api/v2/datasource/sessions/{sessionId}/sqls/getMoreResults` | 拉取增量结果 | 是 |
| 6 | POST | `/api/v2/datasource/sessions/{sessionId}/queryData` | 查询表/视图数据 | 是 |
| 7 | PUT | `/api/v2/datasource/sessions/{sessionId}/killQuery` | 停止当前查询 | 是 |
| 8 | POST | `/api/v2/datasource/sessions/{sessionId}/sqlCheck` | 会话内 SQL 检查 | 是 |
| 9 | POST | `/api/v2/datasource/sessions/sqlCheck` | 多数据库 SQL 检查 | 否 |
| 10 | GET | `/api/v2/datasource/sessions/{sessionId}/sqls/{sqlId}/content` | 查看大字段内容 | 是 |
| 11 | GET | `/api/v2/datasource/sessions/{sessionId}/sqls/{sqlId}/download` | 下载二进制数据 | 是 |
| 12 | POST | `/api/v2/datasource/sessions/{sessionId}/upload` | 上传文件 | 是 |
| 13 | POST | `/api/v2/datasource/sessions/killSession` | 终止目标库会话/查询 | 否 |
| 14 | GET | `/api/v2/datasource/sessions/{sessionId}/status` | 查询会话状态 | 是 |
| 15 | POST | `/api/v2/datasource/sessions/{sessionId}/partitionPlans/latest/preview` | 分区计划 DDL 预览 | 是 |
