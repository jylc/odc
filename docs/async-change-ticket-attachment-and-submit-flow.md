# 数据库变更工单 —— 附件上传与提交流程分析

> 场景：用户在「新建数据库变更」工单（`TaskType.ASYNC` 单库异步变更）中点击「上传附件」（上传 SQL 文件 / 回滚 SQL 文件），填写后点击「提交」创建工单。
> 本文梳理前端 → 后端 Controller → Service → 对象存储 / 元数据库 的完整链路，并汇总涉及的数据库表与外部系统。

---

## 一、场景与术语

| 术语 | 说明 |
| --- | --- |
| 数据库变更工单 | `TaskType.ASYNC`（单库异步变更）。前端「工单 → 新建工单 → 数据库变更」对应此类型 |
| 附件 | 用户上传的 **SQL 脚本文件**（含主 SQL 与回滚 SQL）。前端以 `.sql` 等后缀上传 |
| 对象存储 | ODC 自带的通用对象存储抽象（`ObjectStorageFacade`），区分本地模式 / 云模式 |
| objectId | 上传成功后返回的对象唯一标识（UUID），后续作为附件引用 ID |

> 注意：附件本质上就是 **SQL 文件**，不是任意附件。提交工单时，附件以 `objectId` 列表的形式被携带在 `DatabaseChangeParameters` 中。

---

## 二、整体链路总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│  ① 上传附件（可多次，提交前）                                                │
│     前端 → POST /api/v2/objectstorage/{bucket}/files/batchUpload          │
│            → FileController.batchUpload                                    │
│            → LocalFileTransferService.batchUpload / putObjects             │
│            → ObjectStorageFacade.putObject (本地/云)                       │
│            → 返回 List<ObjectMetadata>（含 objectId）                       │
└─────────────────────────────────────────────────────────────────────────┘
                              │ 前端保存返回的 objectId 列表
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  ② 提交工单（一次性创建）                                                   │
│     前端 → POST /api/v2/flow/flowInstances/   (CreateFlowInstanceReq)      │
│            → FlowInstanceController.createFlowInstance (line 97)           │
│            → FlowInstanceService.create (line 488)                         │
│            → innerCreate (line 542) ── ASYNC 校验 + 加载风险等级/连接       │
│            → buildFlowInstance (line 1124) ── 构造流程图                    │
│            → buildConfigurer (line 1276) ── 审批 + 任务节点接线             │
│            → 写入 metadb 一系列 flow_* / task_task 表                       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 三、附件上传流程（详细）

### 3.1 入口：前端调用

| 项 | 值 |
| --- | --- |
| HTTP | `POST` |
| 路径 | `/api/v2/objectstorage/{bucket}/files/batchUpload` |
| bucket | `async`（数据库变更专用 bucket） |
| Body | `multipart/form-data`，字段 `file`（可多个） |
| 返回 | `ListResponse<ObjectMetadata>`，每个含 `objectId` / `objectName` / `bucketName` / `totalLength` / `extension` / `status` |

> 实际 bucket 名称会在服务端拼接为 `async/{userId}`，每个用户独立隔离。

### 3.2 Controller 层

**文件**：`server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v2/FileController.java`
**类**：`FileController`，`@RequestMapping("/api/v2/objectstorage")`（line 46）

```java
// line 65-69
@RequestMapping(value = "/{bucket}/files/batchUpload", method = RequestMethod.POST)
public ListResponse<ObjectMetadata> batchUpload(@PathVariable String bucket,
        @RequestParam("file") List<MultipartFile> files) {
    return Responses.list(localFileTransferService.batchUpload(bucket, files));
}
```

> 同类还有两个下载接口：
> - `GET /{bucket}/files/{id}` → `FileManager.download`（旧本地目录路径）
> - `GET /files/{id}` → `LocalFileTransferService.download`（对象存储路径，按 tempId）

### 3.3 Service 层：`LocalFileTransferService`

**文件**：`server/odc-service/src/main/java/com/oceanbase/odc/service/objectstorage/LocalFileTransferService.java`

#### `batchUpload(String bucketName, List<MultipartFile> files)`（line 92）

```java
public List<ObjectMetadata> batchUpload(String bucketName, List<MultipartFile> files) {
    PreConditions.notBlank(bucketName, "bucketName");
    PreConditions.notEmpty(files, "files");
    if (StringUtils.equals(bucketName, "async")) {
        // ① 文件数量上限校验（默认 100）
        PreConditions.lessThanOrEqualTo("file count", LimitMetric.FILE_COUNT, files.size(), maxAsyncUploadFileCount);
        // ② 文件总大小上限校验（默认 32MB）
        PreConditions.lessThanOrEqualTo("file size", LimitMetric.FILE_SIZE,
                files.stream().mapToLong(MultipartFile::getSize).sum(), maxAsyncUploadFileTotalSize);
        // ③ 拼接真实 bucket = async/{userId}
        return putObjects(files, bucketName.concat(File.separator).concat(authenticationFacade.currentUserIdStr()));
    } else if (StringUtils.equals(bucketName, "ssl")) {
        ...
    } else {
        throw new BadRequestException("Bad upload request");
    }
}
```

**上传限制**（配置项，line 69-73）：

| 配置 key | 默认值 | 含义 |
| --- | --- | --- |
| `odc.flow.async.max-upload-file-count` | `100` | 单次上传文件数量上限 |
| `odc.flow.async.max-upload-file-total-size` | `32 * 1024 * 1024`（32MB） | 单次上传文件总大小上限 |

#### `putObjects(List<MultipartFile> files, String bucket)`（line 114）

```java
private List<ObjectMetadata> putObjects(List<MultipartFile> files, String bucket) {
    List<ObjectMetadata> returnVal = Lists.newArrayList();
    objectStorageFacade.createBucketIfNotExists(bucket);
    for (MultipartFile file : files) {
        String filename = file.getOriginalFilename();
        fileChecker.validateSuffix(filename);          // 后缀白名单校验
        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata objectMetadata =
                    objectStorageFacade.putObject(bucket, filename, file.getSize(), inputStream);
            returnVal.add(objectMetadata);
        } catch (IOException ex) {
            throw new InternalServerError("put object failed", ex);
        }
    }
    return returnVal;
}
```

### 3.4 存储抽象：`ObjectStorageFacade`

**接口**：`server/odc-service/.../objectstorage/ObjectStorageFacade.java`
**抽象基类**：`AbstractObjectStorageFacade`（分块大小默认 **1MB**，line 72）

两个 Spring Bean 实现，由部署模式决定哪个生效：

| Bean 名 | 实现类 | 部署模式 |
| --- | --- | --- |
| `localObjectStorageFacade` | `LocalObjectStorageFacade` | 本地模式（默认） |
| `cloudEnvironmentObjectStorageFacade` | `CloudEnvironmentObjectStorageFacade` | 云模式 |

#### 本地模式（`LocalObjectStorageFacade.doPutObject`）

写入 **两处**：

1. **本地磁盘**（`LocalFileOperator`）
   - 根目录：配置 `odc.objectstorage.local.dir`，默认 `${user.home}/data/files`
   - 路径布局：`<localDir>/<bucket>/<objectId>.<extension>`，如 `~/data/files/async/1024/8f3a....sql`
   - 按 1MB 分块流式写入，计算 SHA-1

2. **元数据库**（`ObjectBlockOperator.saveObjectBlock`）
   - 文件按 1MB 切分为多个 BLOB 行写入 `objectstorage_object_block`（持久化的权威副本）
   - 本地磁盘缺失时，可从 DB 重建（`loadDbDataToLocalhost`）

同时插入一条元数据行到 `objectstorage_object_metadata`（含 `objectId`、`sha1`、`totalLength`、`splitLength`、`status`）。

#### 云模式（`CloudEnvironmentObjectStorageFacade`）

- 文件字节经 `CloudObjectStorageService.upload(...)` 推送到云端对象存储（OSS / 对象存储服务），云对象名即 `objectId`
- **仅元数据**写入 metadb（`objectstorage_object_metadata`）
- 本地磁盘作为下载时的临时缓存

### 3.5 上传涉及的数据库表与外部系统

| 表 / 系统 | 写入内容 | 说明 |
| --- | --- | --- |
| `objectstorage_bucket` | bucket 注册（`async/{userId}`） | `BucketEntity`，首次上传时 `createBucketIfNotExists` 触发 |
| `objectstorage_object_metadata` | 对象元数据（objectId / sha1 / 大小 / 状态） | `ObjectMetadataEntity`，每个文件一行 |
| `objectstorage_object_block` | 文件内容分块（1MB BLOB） | `ObjectBlockEntity`，仅本地模式写入；云模式不写 |
| **本地文件系统** | `<localDir>/<bucket>/<objectId>.<ext>` | 仅本地模式 |
| **云端对象存储（OSS）** | 以 `objectId` 为对象名的文件 | 仅云模式 |

### 3.6 上传返回与前端处理

上传接口返回 `List<ObjectMetadata>`，前端从中提取：
- `objectId`（核心，后续作为附件引用）
- `objectName`（原始文件名，用于展示）

前端将其存入 `DatabaseChangeParameters.sqlObjectIds` / `rollbackSqlObjectIds` 列表，等待用户点击「提交」。

---

## 四、附件下载（工单内查看附件）

为完整性补充下载路径，与上传对称：

```
GET /api/v2/objectstorage/files/{tempId}
  → FileController.download (line 60)
  → LocalFileTransferService.download(tempId)  (line 75)
      ├─ TempId2ObjectMetaCache.get(tempId)        // 临时 ID → ObjectMetadata（内存缓存，1 分钟有效）
      ├─ ObjectStorageFacade.loadObject(bucket, objectId)   // 按需从磁盘/DB/云端加载
      └─ TempId2ObjectMetaCache.remove(tempId)     // 一次性失效
```

> `tempId` 是一个短时缓存句柄（不同于 `objectId`），由前端通过其它接口换取。下载链接 1 分钟内有效。

---

## 五、提交流程（创建工单）

### 5.1 入口：前端调用

| 项 | 值 |
| --- | --- |
| HTTP | `POST` |
| 路径 | `/api/v2/flow/flowInstances/` |
| Body | `CreateFlowInstanceReq`（JSON） |

### 5.2 请求模型：`CreateFlowInstanceReq`

**文件**：`server/odc-service/src/main/java/com/oceanbase/odc/service/flow/model/CreateFlowInstanceReq.java`

| 字段 | 类型 | 行号 | 说明 |
| --- | --- | --- | --- |
| `taskType` | `TaskType` | 70 | `@NotNull`；数据库变更取 `ASYNC` |
| `databaseId` | `Long` | 65 | 单库变更目标数据库 |
| `connectionId` | `Long` | (READ_ONLY) | 由 aspect 根据 databaseId 回填 |
| `projectId` / `environmentId` | `Long` | (READ_ONLY) | 由 aspect 回填 |
| `executionStrategy` | `FlowTaskExecutionStrategy` | 75 | 默认 `AUTO`（还有 `TIMER` 定时执行） |
| `executionTime` | `Date` | 79 | 仅 `TIMER` 策略使用 |
| `parentFlowInstanceId` | `Long` | 83 | 回滚计划生成场景使用 |
| `description` | `String` | 87 | 工单描述 |
| **`parameters`** | **`TaskParameters`** | **109** | **多态字段，见下** |

`parameters` 字段通过 Jackson `@JsonTypeInfo` 按 `taskType` 多态反序列化（line 92-108）：

| taskType | 反序列化目标类 |
| --- | --- |
| `ASYNC` | `DatabaseChangeParameters` |
| `MULTIPLE_ASYNC` | `MultipleDatabaseChangeParameters`（继承自上者） |
| `IMPORT`/`EXPORT` | `DataTransferConfig` |
| `PRE_CHECK` | `OdcMockTaskConfig` 等 |

### 5.3 附件在工单中的承载：`DatabaseChangeParameters`

**文件**：`server/odc-service/src/main/java/com/oceanbase/odc/service/flow/task/model/DatabaseChangeParameters.java`

**关键字段（附件相关）**：

| 字段 | 类型 | 行号 | 说明 |
| --- | --- | --- | --- |
| `sqlContent` | `String` | 35 | 内联 SQL 文本（与文件二选一） |
| `sqlObjectNames` | `List<String>` | 37 | 上传 SQL 文件名（展示用） |
| **`sqlObjectIds`** | **`List<String>`** | **38** | **上传 SQL 文件的 objectId 列表（附件核心链接）** |
| `rollbackSqlObjectNames` | `List<String>` | 40 | 上传回滚 SQL 文件名 |
| `rollbackSqlContent` | `String` | 41 | 内联回滚 SQL 文本 |
| **`rollbackSqlObjectIds`** | **`List<String>`** | **42** | **上传回滚 SQL 文件的 objectId 列表** |
| `generateRollbackPlan` | `Boolean` | 50 | `@NotNull`；是否自动生成回滚方案 |
| `timeoutMillis` | `Long` | 43 | 超时，默认 2 天 |
| `errorStrategy` | `TaskErrorStrategy` | 44 | 出错策略（继续/中止） |
| `delimiter` | `String` | 46 | 默认 `;` |

> **链接方式**：附件不直接挂在 `CreateFlowInstanceReq` 顶层，而是嵌在 `parameters.sqlObjectIds` / `rollbackSqlObjectIds`（即上传阶段返回的 objectId 列表）。提交时这些 objectId 会随参数序列化进 `task_task.parameters_json`，任务执行阶段再按 objectId 从对象存储加载 SQL 内容。

### 5.4 Controller 层

**文件**：`FlowInstanceController.java`（line 96-107）

```java
@RequestMapping(value = "/", method = RequestMethod.POST)
public ListResponse<FlowInstanceDetailResp> createFlowInstance(@RequestBody CreateFlowInstanceReq flowInstanceReq) {
    if (flowInstanceReq.getTaskType() == TaskType.ALTER_SCHEDULE) {
        return Responses.list(scheduleService.dispatchCreateSchedule(flowInstanceReq));
    }
    flowInstanceReq.validate();
    if (authenticationFacade.currentUser().getOrganizationType() == OrganizationType.INDIVIDUAL) {
        return Responses.list(flowInstanceService.createIndividualFlowInstance(flowInstanceReq));
    } else {
        return Responses.list(flowInstanceService.create(flowInstanceReq));   // ← 企业组织走这里
    }
}
```

### 5.5 Service 层：`FlowInstanceService`

**文件**：`server/odc-service/src/main/java/com/oceanbase/odc/service/flow/FlowInstanceService.java`

#### ① `create(CreateFlowInstanceReq)` — line 488

```java
@EnablePreprocess
@Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
public List<FlowInstanceDetailResp> create(@NotNull @Valid CreateFlowInstanceReq createReq) {
    if (createReq.getTaskType() == TaskType.APPLY_DATABASE_PERMISSION) { ... }
    else if (createReq.getTaskType() == TaskType.APPLY_TABLE_PERMISSION) { ... }
    else {
        return innerCreate(createReq);   // ← ASYNC 走这里（line 525）
    }
}
```

- `@EnablePreprocess`：触发 `CreateFlowInstanceProcessAspect`，根据 `databaseId` 回填 `connectionId` / `projectId` / `environmentId` 等 READ_ONLY 字段
- `@Transactional`：整个创建过程在一个事务内

#### ② `innerCreate(...)` — line 542

```java
private List<FlowInstanceDetailResp> innerCreate(@NotNull @Valid CreateFlowInstanceReq createReq) {
    checkCreateFlowInstancePermission(createReq);                          // 权限校验
    if (createReq.getTaskType() == TaskType.ASYNC) {                       // ← ASYNC 专属校验
        DatabaseChangeParameters taskParameters = (DatabaseChangeParameters) createReq.getParameters();
        PreConditions.maxLength(taskParameters.getSqlContent(), "sql content",
                flowTaskProperties.getSqlContentMaxLength());              // SQL 文本长度上限
    }
    ...
    List<RiskLevel> riskLevels = riskLevelService.list();                  // 加载所有风险等级规则
    Verify.notEmpty(riskLevels, "riskLevels");
    List<ConnectionConfig> conns = new ArrayList<>();
    if (Objects.nonNull(createReq.getConnectionId())) {                    // ASYNC：单连接
        ConnectionConfig conn = connectionService.getForConnectionSkipPermissionCheck(createReq.getConnectionId());
        cloudMetadataClient.checkPermission(...);                          // 云端权限校验
        conns.add(conn);
    }
    return Collections.singletonList(buildFlowInstance(riskLevels, createReq, conns));   // ← 构造流程图
}
```

#### ③ `buildFlowInstance(...)` — line 1124（流程图主构建）

通用结构（所有任务类型共用），节点接线顺序：

```
START
  │
  ▼
[PRE_CHECK 风险检测任务节点]  ← riskDetectInstance (line 1150)
  │
  ▼
[风险等级网关]                 ← riskLevelGateway (line 1154)，按 ${RISKLEVEL == N} 路由
  │
  ├─(riskLevel=低)─▶ buildConfigurer(...)   ┐
  ├─(riskLevel=中)─▶ buildConfigurer(...)   ├─ 每个风险等级一条审批链
  └─(riskLevel=高)─▶ buildConfigurer(...)   ┘
```

#### ④ `buildConfigurer(...)` — line 1276（单个风险等级的审批+任务子图）

ASYNC（非 MULTIPLE_ASYNC）分支在 line 1339-1360：

```java
} else {
    ExecutionStrategyConfig strategyConfig = ExecutionStrategyConfig.from(flowInstanceReq, ...);
    FlowTaskInstance taskInstance =
            flowFactory.generateFlowTaskInstance(flowInstance.getId(), false, true,
                    taskType, strategyConfig);                             // ← ASYNC 任务节点
    taskInstance.setTargetTaskId(targetTaskId);
    FlowInstanceConfigurer taskConfigurer;
    // 若 ASYNC 且 generateRollbackPlan==true，前置「回滚方案生成」节点
    if (taskType == TaskType.ASYNC
            && Boolean.TRUE.equals(((DatabaseChangeParameters) parameters).getGenerateRollbackPlan())) {
        FlowTaskInstance rollbackPlanInstance =
                flowFactory.generateFlowTaskInstance(flowInstance.getId(), false, false,
                        TaskType.GENERATE_ROLLBACK, ExecutionStrategyConfig.autoStrategy());
        taskConfigurer =
                flowInstance.newFlowInstanceConfigurer(rollbackPlanInstance).next(taskInstance);
    } else {
        taskConfigurer = flowInstance.newFlowInstanceConfigurer(taskInstance);
    }
    taskConfigurer.endFlowInstance();
    configurer.route("${APPROVAL_VARIABLE}", taskConfigurer);              // 审批通过 → 任务链
}
```

### 5.6 最终生成的流程图（ASYNC 单库变更）

```
START
  │
  ▼
[PRE_CHECK 风险检测] ── 计算风险等级、生成 sqlCheckResult
  │
  ▼
[风险等级网关] ── 按 RISKLEVEL 路由
  │
  ├─[审批节点1]（autoApproval=true 时自动通过）
  │     │
  │     ▼
  │  [审批网关] ── 拒绝(${!APPROVAL}) ──▶ END
  │     │ 通过
  │     ▼
  │  [审批节点2] ...（多级审批逐级串联）
  │     │
  │     ▼
  │  (可选)[GENERATE_ROLLBACK 回滚方案生成]  ← 仅 generateRollbackPlan=true
  │     │
  │     ▼
  │  [ASYNC 数据库变更任务]（按 executionStrategy：AUTO 立即 / TIMER 定时）
  │     │
  │     ▼
  │    END
```

> `autoApproval`（来自 `ApprovalNodeConfig.getAutoApproval()`）**不改变图拓扑**，仅让 `FlowApprovalInstance` 在运行时自动放行而非等人审批（line 1296）。

### 5.7 提交涉及的数据库表

| 阶段 | 表 | 写入内容 |
| --- | --- | --- |
| 权限/规则加载 | `regulation_risklevel`、`regulation_riskdetectrule`、`regulation_approvalflowconfig`、`regulation_risklevel_rule` | 只读，加载风险等级与审批流配置 |
| 流程实例 | `flow_instance` | 新增一行流程实例 |
| 流程节点 | `flow_instance_node` | 风险检测/审批/任务/网关等节点 |
| 任务节点 | `flow_instance_node_task` | PRE_CHECK / GENERATE_ROLLBACK / ASYNC 任务节点（含 `target_task_id` 指向 `task_task`） |
| 审批节点 | `flow_instance_node_approval` | 审批节点（status=CREATED） |
| 审批候选 | `flow_instance_node_approval_candidate` | 候选审批人（用户/角色/资源角色标识） |
| 网关节点 | `flow_instance_node_gateway` | 风险等级网关、审批网关 |
| 节点连线 | `flow_instance_sequence` | 节点间 source→target 连线 |
| 业务任务 | `task_task` | 真实业务任务，`parameters_json` 含 `sqlObjectIds` / `rollbackSqlObjectIds`（附件 objectId） |
| 连接配置 | `connect_connection`、`connect_database` | 只读，校验目标库与连接 |
| Flowable 引擎 | `act_ru_*` / `act_re_*` / `act_hi_*` | Flowable 流程定义/运行时/历史表（由 `runtimeService`/`repositoryService` 写入） |
| 用户/权限 | `iam_user_resource_role`、`iam_user_role` | 只读，权限校验 |

> **附件本身不会在提交时再次写入对象存储表**：附件 objectId 已经在上传阶段（第三节）持久化到 `objectstorage_object_metadata`，提交时仅以字符串形式序列化进 `task_task.parameters_json`。任务执行时再按 objectId 从 `objectstorage_object_metadata` + `objectstorage_object_block`（本地模式）或云端对象存储（云模式）加载 SQL 内容。

---

## 六、完整时序图（文字版）

```
用户        前端            FileController     LocalFileTransferService    ObjectStorageFacade     metadb / 文件系统 / 云端
 │            │                   │                      │                       │                      │
 │ 选 SQL 文件 │                    │                      │                       │                      │
 │───────────▶│                    │                      │                       │                      │
 │            │ POST /objectstorage/async/files/batchUpload│                      │                       │                      │
 │            │──────────────────▶│                      │                       │                      │
 │            │                   │ batchUpload(async, files)                     │                      │
 │            │                   │─────────────────────▶│                       │                      │
 │            │                   │                      │ 校验 count/size（≤100/≤32MB）                │
 │            │                   │                      │ bucket = async/{userId}                      │
 │            │                   │                      │ putObjects ── putObject(bucket,name,size,in)│
 │            │                   │                      │──────────────────────▶│                      │
 │            │                   │                      │                       │ createBucketIfNotExists ─▶ objectstorage_bucket
 │            │                   │                      │                       │ 写磁盘 + 切块 ─────────▶ objectstorage_object_block (本地模式)
 │            │                   │                      │                       │                       │  + 本地文件 <dir>/async/{userId}/{objectId}.ext
 │            │                   │                      │                       │ 或 云端 upload ───────▶ 云对象存储 (云模式)
 │            │                   │                      │                       │ 写元数据 ─────────────▶ objectstorage_object_metadata
 │            │                   │                      │ ◀── List<ObjectMetadata>（含 objectId） ─────│
 │            │ ◀── List<ObjectMetadata> ────────────────│                       │                      │
 │            │ 保存 objectId 列表到 sqlObjectIds          │                       │                      │
 │            │                   │                      │                       │                      │
 │ 点击「提交」│                    │                      │                       │                      │
 │───────────▶│                    │                      │                       │                      │
 │            │ POST /api/v2/flow/flowInstances/ (CreateFlowInstanceReq, parameters.sqlObjectIds=[...])      │
 │            │───▶ FlowInstanceController.createFlowInstance (line 97)                                          │
 │            │         │                                                                                       │
 │            │     FlowInstanceService.create (line 488) → innerCreate (line 542) → buildFlowInstance (line 1124) │
 │            │         │  权限校验、加载 riskLevel/approvalConfig、加载 connection                                  │
 │            │         │  写 flow_instance / flow_instance_node* / flow_instance_sequence / task_task              │
 │            │         │  task_task.parameters_json ← {sqlObjectIds:[...], rollbackSqlObjectIds:[...], ...}        │
 │            │         │  Flowable 写 act_re_*/act_ru_*                                                          │
 │            │ ◀── FlowInstanceDetailResp ─────────────────────────────────────────────────────────────────────│
 │            │                                                                                              │
 │ 后续任务执行（ASYNC 节点触发）                                                                              │
 │            │   按 task_task.parameters_json 中的 objectId ── ObjectStorageFacade.loadObject ─▶ 读取 SQL 内容   │
```

---

## 七、配置项与限制汇总

| 配置 key | 默认值 | 作用 |
| --- | --- | --- |
| `odc.flow.async.max-upload-file-count` | `100` | 单次上传文件数量上限 |
| `odc.flow.async.max-upload-file-total-size` | `32MB` | 单次上传文件总大小上限 |
| `odc.objectstorage.local.dir` | `${user.home}/data/files` | 本地模式对象存储根目录 |
| `odc.flow.task.sql-content-max-length` | （见 `flowTaskProperties`） | `sqlContent` 文本最大长度 |

---

## 八、注意事项与设计要点

1. **附件 = SQL 文件**：本场景的「附件」即用户上传的 SQL 脚本（主 SQL 与回滚 SQL），通过 `DatabaseChangeParameters.sqlObjectIds` / `rollbackSqlObjectIds` 链接，**没有**独立的 attachment 表。
2. **上传与提交解耦**：上传是独立接口，可多次调用；提交时只携带 objectId 列表。若用户上传后未提交，object 元数据仍会留在 `objectstorage_object_metadata`（依赖清理策略）。
3. **bucket 隔离**：每个用户的 async bucket 为 `async/{userId}`，互不影响。
4. **本地模式双写**：`objectstorage_object_block`（DB BLOB）是持久权威副本，本地磁盘文件是缓存；两者都会写。云模式仅写云端 + 元数据。
5. **`generateRollbackPlan` 影响图拓扑**：为 `true` 时会在 ASYNC 任务节点前插入一个 `GENERATE_ROLLBACK` 任务节点（line 1347-1353），用于自动生成回滚脚本。
6. **`autoApproval` 不改变拓扑**：审批节点始终存在，仅运行时自动放行（line 1296）。
7. **附件内容延迟加载**：提交时只存 objectId，SQL 实际内容在任务执行（PRE_CHECK 风险检测 / ASYNC 执行）阶段才按 objectId 从对象存储读取。
8. **事务边界**：`create()` 标注 `@Transactional`，metadb 写入要么全部成功要么全部回滚；但对象存储上传（前置步骤）不在该事务内。
