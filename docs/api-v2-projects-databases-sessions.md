# ODC REST 接口文档：项目 / 数据库 / 数据源 / 会话相关接口

> 本文档覆盖以下 5 个接口的详细说明（请求参数、鉴权、响应结构、业务逻辑）：
>
> 1. `GET /api/v2/collaboration/projects`
> 2. `GET /api/v2/collaboration/projects/databases/stats`
> 3. `GET /api/v2/connect/sessions`（**该精确路径在当前代码库不存在**，见下文说明与替代接口）
> 4. `GET /api/v2/database/databases`
> 5. `GET /api/v2/datasource/datasources`

---

## 目录

- [公共响应信封](#公共响应信封)
- [接口 1：GET /api/v2/collaboration/projects](#接口-1get-apiv2collaborationprojects)
- [接口 2：GET /api/v2/collaboration/projects/databases/stats](#接口-2get-apiv2collaborationprojectsdatabasesstats)
- [接口 3：GET /api/v2/connect/sessions（不存在）](#接口-3get-apiv2connectsessions不存在)
- [接口 4：GET /api/v2/database/databases](#接口-4get-apiv2databasedatabases)
- [接口 5：GET /api/v2/datasource/datasources](#接口-5get-apiv2datasourcedatasources)
- [汇总](#汇总)
- [相关源码位置](#相关源码位置)

---

## 公共响应信封

所有 v2 接口返回统一的 `BaseResponse` 外层结构：

```jsonc
{
  "code": null,
  "successful": true,
  "httpStatus": "OK",
  "timestamp": "2026-08-13T...",
  "durationMillis": 123,
  "traceId": "...",
  "requestId": "...",
  "server": "...",
  "data": { ... }     // 具体载荷，见各接口
}
```

`data` 的形态：

- **分页接口**：`PaginatedData<T>` = `{ contents: [T], page: {totalElements, totalPages, number(1-based), size}, stats }`
- **列表接口**：`ListData<T>` = `{ contents: [T], stats }`

> 注意：分页响应中的 `number` 为 **1-based**（后端将 Spring 的 0-based `page` +1 后返回）。

---

## 接口 1：GET /api/v2/collaboration/projects

分页列出**当前用户已加入**的项目。

- **Controller**：`ProjectController.java:76-86`
- **类级前缀**：`@RequestMapping("/api/v2/collaboration")`

### 请求参数（全部 query，均可选）

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `name` | String | 否 | — | 项目名称模糊匹配（SQL `LIKE`） |
| `archived` | Boolean | 否 | — | `true`=仅归档，`false`=仅活跃，不传=全部 |
| `builtin` | Boolean | 否 | `false` | 是否包含内置项目（默认排除） |
| `page` | int | 否 | `0` | 页码，**0-based** |
| `size` | int | 否 | `Integer.MAX_VALUE` | 每页条数（默认即返回全部） |
| `sort` | String[] | 否 | `id,DESC` | 排序字段与方向 |

### 鉴权

Controller 方法无注解；Service 层 `@Authenticated` + `@SkipAuthorize("Internal usage")`。

- **需要登录**；
- 数据范围在 Service 内强制限定为「当前组织 + 当前用户已加入的项目」（通过项目成员关系过滤），不做资源级 `isPermitted` 校验。

### 响应 `data`：`PaginatedData<Project>`

**`Project` 关键字段**（`service/collaboration/project/model/Project.java`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 项目 ID |
| `name` | String | 项目名称 |
| `description` | String | 描述 |
| `archived` | Boolean | 是否已归档 |
| `builtin` | Boolean | 是否内置项目 |
| `organizationId` | Long | 所属组织 ID |
| `members` | List&lt;ProjectMember&gt; | 成员列表（`id`/`accountName`/`name`/`role`/`derivedFromGlobalProjectRole`/`userEnabled`） |
| `currentUserResourceRoles` | Set&lt;ResourceRoleName&gt; | **当前调用者**在该项目的角色（`OWNER`/`DBA`/`DEVELOPER`/`SECURITY_ADMINISTRATOR`/`PARTICIPANT`） |
| `creator` / `lastModifier` | InnerUser | 创建者 / 最后修改者（`id`/`name`/`accountName`/`roleNames`） |
| `dbObjectLastSyncTime` | Date | 库对象最后同步时间 |
| `uniqueIdentifier` | String | 全局唯一标识（如 `ODC_<uuid>`） |
| `createTime` / `updateTime` | Date | 创建/更新时间 |

### 业务逻辑

调用 `projectService.list(QueryProjectParams, Pageable)`：

1. 设置 `params.userId = currentUserId()`；
2. 解析当前用户在当前组织的项目成员关系，仅保留 `isProjectMember` 的项目 ID；
3. JPA `Specification` 组合：`name LIKE` + `archived =` + `builtin =` + `organizationId =` + `id IN (已加入项目)`；
4. 分页查询，批量组装成员、角色名称与当前用户角色。

---

## 接口 2：GET /api/v2/collaboration/projects/databases/stats

> ⚠️ **名称易误解**：路径含 `stats`，但返回的不是统计数字，而是**当前用户已加入项目下、按名称去重后的数据源（ConnectionConfig）列表**。

- **Controller**：`ProjectController.java:103-107`
- **类级前缀**：`@RequestMapping("/api/v2/collaboration")`

### 请求参数

**无**（不接受任何参数；用户/组织上下文均由服务端推导）。

### 鉴权

Service 层 `@Authenticated` + `@SkipAuthorize("internal authenticated")`。

- **需要登录**；
- 结果按组织 + 已加入项目自动过滤，不做资源级 `isPermitted` 校验。

### 响应 `data`：`ListData<ConnectionConfig>`

**`ConnectionConfig` 关键字段**（`service/connection/model/ConnectionConfig.java`；密码等敏感字段为 `WRITE_ONLY` / `@JsonIgnore`，不会返回）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 数据源 ID |
| `name` | String | 数据源名称 |
| `type` | ConnectType | 连接类型（必填） |
| `host` / `port` | String / Integer | 主机 / 端口 |
| `clusterName` / `tenantName` | String | OceanBase 集群 / 租户名 |
| `username` | String | 数据库登录用户名 |
| `defaultSchema` | String | 默认 schema |
| `visibleScope` | ConnectionVisibleScope | `PRIVATE` / `ORGANIZATION`（`@Deprecated`） |
| `environmentId` / `environmentName` / `environmentStyle` | — | 关联环境 |
| `projectId` / `projectName` | — | 关联项目 |
| `status` | CheckState | `ACTIVE` / `INACTIVE` / `TESTING` |
| `enabled` | Boolean | 是否启用 |
| `passwordSaved` | Boolean | 是否已保存密码 |
| `sslConfig` | SSLConfig | SSL 配置（`enabled`/`clientCertObjectId`/`clientKeyObjectId`/`CACertObjectId`） |
| `permittedActions` / `supportedOperations` | Set&lt;String&gt; | 允许 / 支持的操作 |
| `createTime` / `updateTime` / `lastAccessTime` | Date | 时间字段 |
| `dbObjectLastSyncTime` | Date | 库对象最后同步时间 |
| `dialectType` | DialectType | 方言类型（由 `connectType` 派生的计算属性） |

### 业务逻辑

调用 `databaseService.statsConnectionConfig()`（`DatabaseService.java:423-438`）：

- **个人组织**：返回当前组织全部 `ConnectionConfig`；
- **团队组织**：取 `list(已加入项目)` 得到的所有 `Database`，提取各自 `dataSource`，**按 `name` 去重（基于 `TreeSet`）后升序返回**。

---

## 接口 3：GET /api/v2/connect/sessions

### ⚠️ 该精确接口在当前代码库不存在

**调查依据：**

1. 全仓库**没有**类级 `@RequestMapping("/api/v2/connect")` 的 Controller；
2. 会话相关 Controller `ConnectSessionController.java:82` 的类级前缀是 **`/api/v2/datasource`**（不是 `/api/v2/connect`）；
3. `/api/v2/connect/sessions` 这个前缀实际被 6 个「DB 对象操作」Controller 用作类级路径（`DBTableController`、`DBViewController`、`DBMetadataController`、`PLController`、`DBMaterializedViewController`、`DBMaterializedViewLogController`），但它们的方法**全部是 `/{sessionId}/...` 子路径**（表/视图/PL 等增删改查），**没有任何一个映射到根 `GET /` 来列出会话**；
4. `ConnectSessionController` 中 `/sessions` 仅有的集合级映射是 **`DELETE /sessions`**（关闭会话），其余均为 `/sessions/{sessionId}/...` 的操作类方法（`getMoreResults`、`content`、`download`、`upload`、`killQuery`、`queryData`、`status` 等）。

### 最接近的替代接口

如果你的目标是 **「会话管理 / 查看会话列表」**，可用的实际接口如下：

| 场景 | 实际接口 | 说明 |
|---|---|---|
| 列出某数据源下**数据库侧**所有会话（processlist） | `GET /api/v1/dbsession/list/{sid}` | v1。`@PathVariable String sid`（如 `sid:1000-1`），返回 `OdcDBSession[]`（`sessionId`/`dbUser`/`srcIp`/`database`/`status`/`command`/`executeTime`/`sql`/`obproxyIp`/`svrIp`）。鉴权下沉到 `@SkipAuthorize("inside connect session")`，需先具备该连接会话 |
| 查询**当前单个**连接会话状态 | `GET /api/v2/datasource/sessions/{sessionId}/status` | v2。返回 `DBSessionResp`（含 session 信息 + `SessionSettings`），单个而非列表 |
| **创建**连接会话 | `POST /api/v2/datasource/datasources/{dataSourceId}/sessions`<br>`POST /api/v2/datasource/databases/{databaseId}/sessions` | v2。按数据源 / 数据库建立会话 |
| **关闭**连接会话 | `DELETE /api/v2/datasource/sessions` | v2。批量关闭 |

> 若你在前端代码中确实见到调用 `GET /api/v2/connect/sessions`，可能是前端封装的别名 / 代理路径，或路径记忆有误，建议核对前端调用处的真实后端路由。

---

## 接口 4：GET /api/v2/database/databases

分页列出数据库。

- **Controller**：`DataBaseController.java`（注意文件名为 `DataBaseController`，B 大写）
- **类级前缀**：`@RequestMapping("/api/v2/database")`
- **方法**：`listDatabases`（第 73-107 行）

### 请求参数（全部 query，均可选）

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `name` | String | — | 数据库名模糊匹配（内部映射 `schemaName`，不区分大小写 `LIKE`） |
| `type` | List&lt;DatabaseType&gt; | — | 数据库类型多选：`LOGICAL` / `PHYSICAL` |
| `connectType` | List&lt;ConnectType&gt; | — | 连接类型多选：`OB_MYSQL`/`OB_ORACLE`/`CLOUD_OB_MYSQL`/`CLOUD_OB_ORACLE`/`ODP_SHARDING_OB_MYSQL`/`MYSQL`/`DORIS`/`POSTGRESQL`/`ORACLE` 等 |
| `existed` | Boolean | — | `true`=仅现存，`false`=仅已删除，不传=全部 |
| `environmentId` | Long | — | 环境 ID |
| `dataSourceId` | Long | — | 数据源 ID 精确过滤（`connectionId`） |
| `dataSourceName` | String | — | 数据源名称（与 `tenantName`/`clusterName`/`name` 为 **OR** 组合，解析成 `connectionId`） |
| `tenantName` | String | — | 租户名（OR 组合） |
| `clusterName` | String | — | 集群名（OR 组合） |
| `projectId` | Long | — | 项目 ID 过滤（**调用方须为该项目成员**，否则抛 `AccessDeniedException`） |
| `containsUnassigned` | Boolean | `false` | 是否包含未分配到任何项目的库（`projectId IS NULL`） |
| `includesPermittedAction` | Boolean | `false` | 是否填充返回结果中的 `authorizedPermissionTypes` |
| `projectName` | String | — | ⚠️ **声明了但未传入查询参数，实际不生效** |
| `page` | int | `0` | 页码，0-based |
| `size` | int | `Integer.MAX_VALUE` | 每页条数（默认返回全部） |
| `sort` | String[] | `type,ASC;name,ASC` | 排序 |

### 鉴权

Controller 无注解；Service 层 `@Authenticated` + `@SkipAuthorize("internal authenticated")`。

- **需要登录**；
- 可见性在 `list()` 方法体内用代码强制：固定按当前组织过滤 + 仅返回当前用户已加入项目下的库 + 指定 `projectId` 时校验成员身份。

### 响应 `data`：`PaginatedData<Database>`

**`Database` 关键字段**（`service/connection/database/model/Database.java`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long | 数据库主键 |
| `databaseId` | String | 业务 UUID |
| `name` | String | 数据库名（schema 名） |
| `alias` | String | 别名 |
| `type` | DatabaseType | `LOGICAL` / `PHYSICAL` |
| `existed` | Boolean | 在数据源中是否真实存在 |
| `project` | Project | 所属项目（未分配库时为 `null`） |
| `dataSource` | ConnectionConfig | 所属数据源（逻辑库可能为 `null`） |
| `environment` | Environment | 所属环境 |
| `connectType` | ConnectType | 连接类型 |
| `dialectType` | DialectType | 方言（由 `connectType` 派生） |
| `syncStatus` | DatabaseSyncStatus | 库结构同步状态 |
| `lastSyncTime` | Date | 库结构最近同步时间 |
| `objectSyncStatus` | DBObjectSyncStatus | 库对象同步状态 |
| `objectLastSyncTime` | Date | 库对象最近同步时间 |
| `tableCount` | Long | 表数量 |
| `charsetName` / `collationName` | String | 字符集 / 排序规则 |
| `owners` | List&lt;InnerUser&gt; | 数据库 Owner 列表（`id`/`name`/`accountName`） |
| `authorizedPermissionTypes` | Set&lt;DatabasePermissionType&gt; | 当前用户对该库的授权类型（仅 `includesPermittedAction=true` 时填充） |
| `organizationId` | Long | 组织 ID |
| `lockDatabaseUserRequired` | boolean | 是否需锁定库用户 |
| `remark` | String | 备注 |

### 支持的过滤维度汇总

| 维度 | 入参 | 匹配方式 |
|---|---|---|
| 数据库名 | `name` | 不区分大小写 `LIKE` |
| 数据库类型 | `type`（多选） | `IN`（`LOGICAL`/`PHYSICAL`） |
| 连接类型 | `connectType`（多选） | `IN` |
| 是否现存 | `existed` | 等于 |
| 环境 | `environmentId` | 等于 |
| 数据源 ID | `dataSourceId` | 等于（`connectionId`） |
| 数据源名称 / 租户 / 集群 | `dataSourceName`/`tenantName`/`clusterName` | 解析为 `connectionId`，`OR` 组合 |
| 项目 ID | `projectId` | 等于（需为该项目成员） |
| 是否含未分配库 | `containsUnassigned` | `projectId IS NULL`（`OR`） |
| 组织 | （隐式） | 固定等于当前组织 |
| 已加入项目 | （隐式） | `projectId IN` 已加入项目 |

### 业务逻辑

调用 `databaseService.list(QueryDatabaseParams, Pageable)`（`DatabaseService.java:336-397`，`@Transactional`）：

1. 个人空间且指定 `dataSourceId` 时，先同步该数据源库列表（异常仅告警不中断），并强制 `containsUnassigned=true`；
2. 基础过滤：环境 / 类型 / 连接类型 / `existed` / **组织（强制隔离）**；
3. 项目可见性：取当前用户已加入项目；未指定 `projectId` 则 `projectId IN (已加入)`（`containsUnassigned=true` 再 `OR projectId IS NULL`）；指定 `projectId` 则校验成员身份后精确过滤；
4. 名称 / 数据源复合过滤：`dataSourceName`/`tenantName`/`clusterName` 解析为 `connectionId` 集合，与 `name LIKE` 为 `OR`；
5. 查询并批量组装 `project`/`environment`/`dataSource`/`owners`，按需填充 `authorizedPermissionTypes`。

---

## 接口 5：GET /api/v2/datasource/datasources

分页列出数据源（连接），支持按项目、用户、类型、权限、集群/租户等多维度过滤。

- **Controller**：`DataSourceController.listDataSources`（第 124-160 行）
- **类级前缀**：`@RequestMapping("/api/v2/datasource")`
- **完整示例 URL**：`GET http://localhost:8000/api/v2/datasource/datasources`（端口由部署决定，此处示例为 `8000`；ODC 默认服务端口为 `8989`）

### 请求参数（全部 query，均可选）

| 参数 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `basic` | Boolean | `true` | 是否仅返回基本信息（轻量） |
| `projectId` | Long | — | 项目 ID。**若传入则走「项目分支」**：返回该项目下的数据源，**忽略其余过滤参数与分页**，且要求调用方为该项目成员 |
| `userId` | Long | — | 关联用户 ID（筛选该用户可访问的数据源，内部映射为 `relatedUserId`） |
| `minPrivilege` | String | `read` | 最小权限要求（如 `read`/`connect` 等），用于过滤当前用户可见的数据源 |
| `type` | List&lt;ConnectType&gt; | — | 连接类型多选（`OB_MYSQL`/`OB_ORACLE`/`CLOUD_OB_MYSQL`/`MYSQL`/`POSTGRESQL`/`DORIS`/`ORACLE` 等） |
| `dialectType` | List&lt;DialectType&gt; | — | 方言类型多选 |
| `enabled` | List&lt;Boolean&gt; | — | 启用状态。**仅当传入恰好 1 个值时生效**（取该值）；传入多个或为空时不按此过滤 |
| `fuzzySearchKeyword` | String | — | 模糊搜索关键字 |
| `id` | Set&lt;Long&gt; | — | 数据源 ID 集合 |
| `clusterName` | List&lt;String&gt; | — | 集群名列表 |
| `tenantName` | List&lt;String&gt; | — | 租户名列表 |
| `permittedAction` | List&lt;String&gt; | — | 按已授权动作过滤 |
| `hostPort` | String | — | 主机:端口 |
| `name` | String | — | 数据源名称 |
| `page` | int | `0` | 页码，0-based（仅在 `projectId` 为空时生效） |
| `size` | int | `Integer.MAX_VALUE` | 每页条数（默认返回全部） |
| `sort` | String[] | `id,DESC` | 排序 |

> 注意：`projectId` 非空时走 `connectionService.listByProjectId(projectId, basic)`，**忽略分页及其余过滤条件**；为空时才走 `connectionService.list(params, pageable)` 应用全部过滤与分页。

### 鉴权

- **传入 `projectId`**：`listByProjectId` 上为 `@PreAuthenticate(hasAnyResourceRole = {"OWNER, DBA, DEVELOPER, SECURITY_ADMINISTRATOR"}, ...)`，要求调用方在该项目具备以上角色之一。
- **不传 `projectId`**：`list` 上为 `@SkipAuthorize("permission check inside")`，鉴权在方法体内按 `minPrivilege` 过滤，仅返回当前用户有权访问的数据源。
- 类级 `@Authenticated`：要求已登录。

### 响应 `data`：`PaginatedData<ConnectionConfig>`

`ConnectionConfig` 字段与 [接口 2](#接口-2get-apiv2collaborationprojectsdatabasesstats) 相同，此处不再重复。关键字段：`id`、`name`、`type`、`host`/`port`、`clusterName`/`tenantName`、`username`、`visibleScope`、`environmentId`/`environmentName`、`projectId`/`projectName`、`status`、`enabled`、`passwordSaved`、`sslConfig`、`permittedActions`、`supportedOperations` 等（密码等敏感字段为 `WRITE_ONLY`/`@JsonIgnore`，不返回）。

### 示例

请求：
```
GET http://localhost:8000/api/v2/datasource/datasources?type=OB_MYSQL&enabled=true&fuzzySearchKeyword=order&page=0&size=20
```

响应（结构骨架）：
```jsonc
{
  "successful": true,
  "httpStatus": "OK",
  "timestamp": "...",
  "durationMillis": 12,
  "traceId": "...",
  "requestId": "...",
  "server": "...",
  "data": {
    "contents": [
      {
        "id": 88,
        "name": "order_db_ds",
        "type": "OB_MYSQL",
        "host": "10.0.0.1",
        "port": 2881,
        "clusterName": "...",
        "tenantName": "...",
        "username": "root",
        "status": "ACTIVE",
        "enabled": true,
        "environmentId": 3,
        "environmentName": "默认环境",
        "projectId": 12,
        "projectName": "...",
        "permittedActions": ["read"],
        "...": "..."
      }
    ],
    "page": {"totalElements": 1, "totalPages": 1, "number": 1, "size": 20},
    "stats": null
  }
}
```

### 业务逻辑

- **`projectId` 非空**：`connectionService.listByProjectId(projectId, basic)`，返回该项目下数据源；`basic=true` 返回轻量信息。
- **`projectId` 为空**：构建 `QueryConnectionParams`（`types`/`dialectTypes`/`enabled`/`fuzzySearchKeyword`/`minPrivilege`/`clusterNames`/`tenantNames`/`permittedActions`/`relatedUserId`/`hostPort`/`name`/`ids`），调用 `connectionService.list(params, pageable)`，按当前用户的 `minPrivilege` 过滤可见数据源并分页返回。

---

## 汇总

| # | 接口 | 状态 | Controller 方法 |
|---|---|---|---|
| 1 | `GET /api/v2/collaboration/projects` | ✅ 存在 | `ProjectController.listProjects` |
| 2 | `GET /api/v2/collaboration/projects/databases/stats` | ✅ 存在（返回去重数据源列表） | `ProjectController.statsRelatedDataSource` |
| 3 | `GET /api/v2/connect/sessions` | ❌ **不存在**，见替代方案 | — |
| 4 | `GET /api/v2/database/databases` | ✅ 存在 | `DataBaseController.listDatabases` |
| 5 | `GET /api/v2/datasource/datasources` | ✅ 存在 | `DataSourceController.listDataSources` |

---

## 相关源码位置

| 内容 | 文件（相对项目根） | 行号 |
|---|---|---|
| Project Controller | `server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v2/ProjectController.java` | 55-107 |
| DataBase Controller | `server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v2/DataBaseController.java` | 58-107 |
| DataSource Controller | `server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v2/DataSourceController.java` | 69-160 |
| ConnectionService.list / listByProjectId | `server/odc-service/src/main/java/com/oceanbase/odc/service/connection/ConnectionService.java` | 582-584 / 439-442 |
| ConnectSession Controller | `server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v2/ConnectSessionController.java` | 82-260 |
| v1 DBSession Controller | `server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v1/DBSessionController.java` | 39-56 |
| ProjectService.list | `server/odc-service/src/main/java/com/oceanbase/odc/service/collaboration/project/ProjectService.java` | 330-361 |
| DatabaseService.list / statsConnectionConfig | `server/odc-service/src/main/java/com/oceanbase/odc/service/connection/database/DatabaseService.java` | 336-397 / 423-438 |
| Project 模型 | `server/odc-service/src/main/java/com/oceanbase/odc/service/collaboration/project/model/Project.java` | 47-135 |
| ConnectionConfig 模型 | `server/odc-service/src/main/java/com/oceanbase/odc/service/connection/model/ConnectionConfig.java` | — |
| Database 模型 | `server/odc-service/src/main/java/com/oceanbase/odc/service/connection/database/model/Database.java` | 49-138 |
| QueryDatabaseParams | `server/odc-service/src/main/java/com/oceanbase/odc/service/connection/database/model/QueryDatabaseParams.java` | 36-60 |
| QueryConnectionParams | `server/odc-service/src/main/java/com/oceanbase/odc/service/connection/model/QueryConnectionParams.java` | 34-61 |
| 响应封装 | `server/odc-service/src/main/java/com/oceanbase/odc/service/common/response/`（`BaseResponse`、`SuccessResponse`、`PaginatedResponse`、`ListResponse`、`PaginatedData`、`ListData`、`CustomPage`、`Responses`） | — |
