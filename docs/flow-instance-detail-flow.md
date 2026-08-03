# `detailFlowInstance` 接口流程分析文档

> 入口：`FlowInstanceController#detailFlowInstance`
> 文件：`server/odc-server/src/main/java/com/oceanbase/odc/server/web/controller/v2/FlowInstanceController.java`
> 接口：`GET /api/v2/flow/flowInstances/{id:[\\d]+}`

本文档梳理该接口从 Controller 到数据库表的完整调用链路，并汇总涉及的数据库表。

---

## 一、接口入口

```java
@ApiOperation(value = "detailFlowInstance", notes = "获取指定流程实例")
@RequestMapping(value = "/{id:[\\d]+}", method = RequestMethod.GET)
public SuccessResponse<FlowInstanceDetailResp> detailFlowInstance(@PathVariable Long id) {
    return Responses.single(flowInstanceService.detail(id));
}
```

- 输入：路径变量 `id`（流程实例 ID）
- 输出：`SuccessResponse<FlowInstanceDetailResp>`
- 仅做参数透传，业务逻辑全部委托给 `FlowInstanceService#detail`。

---

## 二、整体调用链路总览

```
FlowInstanceController#detailFlowInstance(id)
        │
        ▼
FlowInstanceService#detail(id)                                  [FlowInstanceService.java:727]
        │  内部调用 mapFlowInstanceWithReadPermission(...)
        ▼
mapFlowInstanceWithReadPermission(id, mapper)                   [FlowInstanceService.java:964]
        │  内部调用 mapFlowInstance(id, mapper, flowPermissionHelper.withProjectMemberCheck())
        ▼
mapFlowInstance(flowInstanceId, mapper, checkAuth)              [FlowInstanceService.java:949]
        │
        ├── 1) flowFactory.getFlowInstance(id)        ── 加载流程实例与拓扑
        │        │
        │        └── FlowFactory#getFlowInstance      [FlowFactory.java:158]
        │
        ├── 2) checkAuth.accept(flowInstance)         ── 权限校验
        │        │
        │        └── FlowPermissionHelper#withProjectMemberCheck  [FlowPermissionHelper.java:59]
        │
        └── 3) mapper.apply(flowInstance)             ── 组装返回 DTO
                 │
                 ├── instanceMapper.map(...)          (generateMapperByInstance)
                 │      │
                 │      └── FlowResponseMapperFactory#generateMapper   [FlowResponseMapperFactory.java:279]
                 │
                 └── nodeInstanceMapper.map(...)      (generateNodeMapperByInstance)
                        │
                        └── FlowResponseMapperFactory#generateNodeMapper  [FlowResponseMapperFactory.java:190]
```

---

## 三、关键代码位置

| 方法 | 文件 | 行号 |
| --- | --- | --- |
| `FlowInstanceController#detailFlowInstance` | `FlowInstanceController.java` | 146 |
| `FlowInstanceService#detail` | `FlowInstanceService.java` | 727 |
| `mapFlowInstanceWithReadPermission` | `FlowInstanceService.java` | 964 |
| `mapFlowInstance` | `FlowInstanceService.java` | 949 |
| `FlowFactory#getFlowInstance` | `FlowFactory.java` | 158 |
| `FlowPermissionHelper#withProjectMemberCheck` | `FlowPermissionHelper.java` | 59 |
| `FlowResponseMapperFactory#generateMapper` | `FlowResponseMapperFactory.java` | 279 |
| `FlowResponseMapperFactory#generateNodeMapper` | `FlowResponseMapperFactory.java` | 190 |

### `FlowInstanceService#detail` 核心实现

```java
public FlowInstanceDetailResp detail(@NotNull Long id) {
    return mapFlowInstanceWithReadPermission(id, flowInstance -> {
        FlowInstanceMapper instanceMapper = mapperFactory.generateMapperByInstance(flowInstance, false);
        FlowNodeInstanceMapper nodeInstanceMapper = mapperFactory.generateNodeMapperByInstance(flowInstance, false);
        return instanceMapper.map(flowInstance, nodeInstanceMapper);
    });
}
```

> 注意：`skipAuth=false`，因此 `generateMapper` / `generateNodeMapper` 内部会执行审批人 / 当前用户可审批状态等与权限相关的查询。

---

## 四、详细阶段分析

### 阶段 1：加载流程实例与拓扑（`FlowFactory#getFlowInstance`）

位置：`FlowFactory.java:158`

```java
public Optional<FlowInstance> getFlowInstance(@NonNull Long flowInstanceId) {
    Optional<FlowInstanceEntity> flowInstanceOptional = flowInstanceRepository.findById(flowInstanceId);
    ...
    List<NodeInstanceEntity> nodes = this.nodeRepository.findByFlowInstanceId(target.getId());
    List<BaseFlowNodeInstance> instances = new LinkedList<>(getGatewayInstances(target.getId(), nodes));
    instances.addAll(getApprovalInstances(target.getId(), nodes));
    instances.addAll(getTaskInstances(target.getId(), nodes));
    Set<FlowSequenceInstance> sequences = getSequences(target.getId());
    FlowInstanceUtil.loadTopology(target, instances, sequences);
    return Optional.of(target);
}
```

子查询：

| 子方法 | 访问 Repository | 访问表 |
| --- | --- | --- |
| `flowInstanceRepository.findById` | `FlowInstanceRepository` | `flow_instance` |
| `nodeRepository.findByFlowInstanceId` | `NodeInstanceEntityRepository` | `flow_instance_node` |
| `getGatewayInstances` → `gatewayInstanceRepository.findAll` | `GateWayInstanceRepository` | `flow_instance_node_gateway` |
| `getApprovalInstances` → `userTaskInstanceRepository.findAll` | `UserTaskInstanceRepository` | `flow_instance_node_approval` |
| `getTaskInstances` → `serviceTaskRepository.findAll` | `ServiceTaskInstanceRepository` | `flow_instance_node_task` |
| `getSequences` → `sequenceRepository.findByFlowInstanceId` | `SequenceInstanceRepository` | `flow_instance_sequence` |
| `getSequences` → `nodeRepository.findByIds` | `NodeInstanceEntityRepository` | `flow_instance_node`（再次） |

该阶段将一个流程实例还原成：流程实例 + 节点实例 + 网关 + 审批节点 + 任务节点 + 连线（Sequence），并在内存里重建拓扑结构。

---

### 阶段 2：权限校验（`FlowPermissionHelper#withProjectMemberCheck`）

位置：`FlowPermissionHelper.java:59` → `withProjectPermissionCheck` (107)

```java
public Consumer<FlowInstance> withProjectMemberCheck() {
    return withProjectPermissionCheck(
            flowInstance -> flowInstance.getProjectId() != null
                && projectPermissionValidator.hasProjectRole(
                        flowInstance.getProjectId(), ResourceRoleName.all()));
}

private Consumer<FlowInstance> withProjectPermissionCheck(Predicate<FlowInstance> predicate) {
    return flowInstance -> {
        if (!Objects.equals(authenticationFacade.currentUserId(), flowInstance.getCreatorId())
                && !predicate.test(flowInstance)) {
            throw new AccessDeniedException();
        }
        horizontalDataPermissionValidator.checkCurrentOrganization(flowInstance);
    };
}
```

校验逻辑：

1. **创建人优先**：当前用户就是该流程实例的创建人 → 直接通过。
2. **项目成员校验**：否则要求 `projectId != null` 且当前用户在该项目下持有任意 `ResourceRoleName`（项目角色）。
   - `projectPermissionValidator.hasProjectRole(...)` 会查询当前用户在该项目下的资源角色。
3. **横向数据权限**：`horizontalDataPermissionValidator.checkCurrentOrganization(flowInstance)` 校验流程实例所属组织与当前用户组织一致。

涉及数据：

| 校验项 | 访问表 |
| --- | --- |
| 当前用户项目资源角色（`hasProjectRole`） | `iam_user_resource_role`，可能附带全局角色 `iam_user_role` |
| 当前用户角色 / 组织 | `iam_user_role`（经由 `GlobalResourceRoleService` 解析全局项目角色） |
| 横向组织校验 | 仅基于已加载的 `flowInstance` 实体（无额外 SQL） |

---

### 阶段 3：组装返回 DTO（Mapper 阶段）

`detail` 方法里会构造两个 Mapper 并最终调用 `instanceMapper.map(flowInstance, nodeInstanceMapper)`：

```java
FlowInstanceMapper instanceMapper = mapperFactory.generateMapperByInstance(flowInstance, false);
FlowNodeInstanceMapper nodeInstanceMapper = mapperFactory.generateNodeMapperByInstance(flowInstance, false);
return instanceMapper.map(flowInstance, nodeInstanceMapper);
```

#### 3.1 `generateMapperByInstance` → `generateMapper`

位置：`FlowResponseMapperFactory.java:279`

```java
private FlowInstanceMapper generateMapper(Collection<Long> flowInstanceIds, Set<Long> creatorIds, boolean skipAuth) {
    List<ServiceTaskInstanceEntity> serviceEntities =
            serviceTaskRepository.findByFlowInstanceIdIn(new HashSet<>(flowInstanceIds));   // ①
    ...
    Map<Long, Boolean> flowInstanceId2Rollbackable = mapRollbackableStatus(flowInstanceIds); // ②
    Map<Long, TaskEntity> taskId2TaskEntity = getTaskEntityMap(serviceEntities);             // ③
    ...
    Set<Long> databaseIds = ... collectMultiDatabaseChangeDatabaseIds(...)                   // ④
                             .collectDBStructureComparisonDatabaseIds(...)
                             .collectApplyDatabasePermissionDatabaseIds(...)
                             .collectApplyTablePermissionDatabaseIds(...);
    Map<Long, Database> id2Database = getIdDatabaseMapAndFillProjectIds(databaseIds, ...);   // ⑤
    Map<Long, Project> id2Project = getIdProjectMap(projectIds, skipAuth);                   // ⑥
    Map<Long, Set<UserEntity>> candidatesByFlowInstanceIds =
            approvalPermissionService.getCandidatesByFlowInstanceIds(flowInstanceIds);       // ⑦
    Map<Long, UserEntity> userId2User = getUserId2EntityMap(creatorIds, ...);                // ⑧
    Map<Long, List<RoleEntity>> userId2Roles = getUserId2Roles(userId2User.keySet(), skipAuth); // ⑨
    Set<Long> approvableFlowInstanceIds = getApprovableFlowInstanceIds(skipAuth);            // ⑩
    ...
}
```

涉及数据库：

| 步骤 | Repository / Service | 访问表 |
| --- | --- | --- |
| ① 加载流程下的所有任务节点 | `ServiceTaskInstanceRepository.findByFlowInstanceIdIn` | `flow_instance_node_task` |
| ② 回滚状态（是否有子流程） | `FlowInstanceRepository.findByParentInstanceIdIn` | `flow_instance` |
| ③ 加载真实业务任务 | `TaskRepository`（`TaskSpecs.idIn`） | `task_task` |
| ④ 多库变更/结构对比/权限申请的 databaseId | （仅从 `task_task.parameters_json` 解析，无额外 SQL） | — |
| ⑤ 加载数据库 + 数据源 | `DatabaseService.listDatabasesByIds` → `DatabaseRepository.findByIdIn`；`populateDatasourceToDatabase` → `ConnectionConfigRepository.findAll` | `connect_database`、`connect_connection` |
| ⑥ 加载项目及当前用户在该项目的角色 | `ProjectService.listByIds` → `collaboration_project`；`getProjectId2ResourceRoleNames` → `iam_user_resource_role`（+ 全局角色 `iam_user_role`） | `collaboration_project`、`iam_user_resource_role`、`iam_user_role` |
| ⑦ 候选审批人 | `ApprovalPermissionService.getCandidatesByFlowInstanceIds`（见下方展开） | `flow_instance_node_approval`、`flow_instance_node_approval_candidate`、`iam_user_resource_role`、`iam_user_role`、`iam_user` |
| ⑧ 用户实体 | `UserRepository.findByUserIds` | `iam_user` |
| ⑨ 用户角色 | `UserRoleRepository.findByOrganizationIdAndUserIdIn`；`RoleRepository.findByRoleIdsAndEnabled` | `iam_user_role`、`iam_role` |
| ⑩ 当前用户可审批的流程实例 | `ApprovalPermissionService.getApprovableApprovalInstances`（见下方展开） | `flow_instance_node_approval`、`flow_instance_node_approval_candidate`、`iam_user_role`、`iam_user_resource_role`、`iam_user` |
| 风险等级（懒加载） | `riskLevelRepository.findById`（在 Mapper 闭包内按 `riskLevelId` 触发） | `regulation_risklevel` |
| 外部审批集成名称 / 跳转链接（懒加载） | `IntegrationService.nullSafeGet` / `detailWithoutPermissionCheck` | `integration_integration` |

#### 3.2 `generateNodeMapperByInstance` → `generateNodeMapper`

位置：`FlowResponseMapperFactory.java:190`

```java
private FlowNodeInstanceMapper generateNodeMapper(Collection<Long> flowInstanceIds, Set<Long> creatorIds, boolean skipAuth) {
    List<UserTaskInstanceEntity> userTaskEntities = userTaskInstanceRepository.findAll(specification);  // ①
    Map<Long, UserEntity> userId2User = listUsersByUserIds(userIds).stream()...;                        // ②
    List<UserTaskInstanceCandidateEntity> candidateEntities =
            userTaskCandidateRepository.findByApprovalInstanceIds(approvalInstanceIds);                 // ③
    // 按 候选用户 / 候选角色 / 资源角色标识 三种方式查找候选审批人
    Map<Long, List<RoleEntity>> userId2Roles = getUserId2Roles(userId2User.keySet(), skipAuth);         // ④
    List<ServiceTaskInstanceEntity> serviceEntities = serviceTaskRepository.findAll(serviceSpec);        // ⑤
    Map<Long, TaskEntity> taskId2TaskEntity = getTaskEntityMap(serviceEntities);                         // ⑥
    ...
}
```

涉及数据库：

| 步骤 | Repository | 访问表 |
| --- | --- | --- |
| ① 加载所有审批节点 | `UserTaskInstanceRepository.findAll` | `flow_instance_node_approval` |
| ② 操作人/创建人 | `UserRepository.findByUserIds` | `iam_user` |
| ③ 审批候选 | `UserTaskInstanceCandidateRepository.findByApprovalInstanceIds` | `flow_instance_node_approval_candidate` |
| 候选资源角色标识 → 用户 | `ResourceRoleService.listByResourceIdentifierIn` | `iam_user_resource_role`、`iam_user_role`、`iam_user` |
| 候选角色 → 用户 | `UserRepository.findByRoleIdsAndEnabled` | `iam_user` |
| 候选用户直接查 | `UserRepository.findByUserIdsAndEnabled` | `iam_user` |
| ④ 用户角色 | `UserRoleRepository.findByOrganizationIdAndUserIdIn`；`RoleRepository.findByRoleIdsAndEnabled` | `iam_user_role`、`iam_role` |
| ⑤ 加载任务节点 | `ServiceTaskInstanceRepository.findAll` | `flow_instance_node_task` |
| ⑥ 加载真实业务任务 | `TaskRepository`（`TaskSpecs.idIn`） | `task_task` |
| 外部审批名称 / 链接（闭包内懒加载） | `IntegrationService.nullSafeGet` / `detailWithoutPermissionCheck` | `integration_integration` |

#### 3.3 `ApprovalPermissionService` 两个关键方法展开

**`getApprovableApprovalInstances`**（`ApprovalPermissionService.java:99`）
当前用户“可审批”的审批节点集合：

1. `userService.nullSafeGet(userId)` 校验用户启用 → `iam_user`
2. `userService.getCurrentUserRoleIds()` → `iam_user_role`
3. `userService.getCurrentUserResourceRoleIdentifiers()` → `iam_user_resource_role`
4. 按 候选用户 / 候选角色 / 资源角色标识 三种条件查 `flow_instance_node_approval`（结合 `flow_instance_node_approval_candidate`）

**`getCandidatesByFlowInstanceIds`**（`ApprovalPermissionService.java:136`）
按流程实例 ID 反查“当前正在执行的审批节点的候选审批人集合”：

1. `userTaskInstanceRepository.findApprovalInstanceIdByFlowInstanceIdAndStatus` → `flow_instance_node_approval`
2. `getInstanceId2CandidateResourceRoleIdentifierIds` → `flow_instance_node_approval_candidate`
3. `resourceRoleService.listByResourceIdentifierIn` → `iam_user_resource_role`、`iam_user_role`
4. `userRepository.findByUserIdsAndEnabled` → `iam_user`

---

## 四'、审批流程涉及的 SQL 语句（汇总）

> 本节汇总 `detailFlowInstance` 接口在「审批节点 / 审批候选 / 可审批流程」相关环节实际下发的 SQL 语句。
> 所有语句均来自 Repository 层的 `@Query` 注解或 JPA 衍生查询，按方法归类，参数占位用 `:param` / `?n` / `{value}` 表示。
> 所有表均位于 ODC 元数据库（metadb），且为只读查询（`detail` 接口不触发任何写操作）。

### 4'.1 加载该流程实例下的所有审批节点

来源：`FlowFactory#getApprovalInstances` → `UserTaskInstanceRepository.findAll(Specification)`
位置：`UserTaskInstanceSpecs.java:42`

```sql
-- 按 flow_instance_id 查询该流程实例下的所有审批节点
SELECT *
FROM   flow_instance_node_approval
WHERE  flow_instance_id = :flowInstanceId;
```

> 对应实体字段：`status`（FlowNodeStatus 枚举存字符串）、`operator_id`（操作人）、`is_approved`、`external_approval_id`、`external_flow_instance_id`、`wait_for_confirm` 等。
> 表结构关键字段：`id`、`organization_id`、`flow_instance_id`、`status`、`operator_id`、`is_approved`、`is_auto_approve`、`external_approval_id`。

### 4'.2 加载审批节点的候选审批人（候选用户/角色/资源角色标识）

来源：`FlowResponseMapperFactory#generateNodeMapper` → `UserTaskInstanceCandidateRepository.findByApprovalInstanceIds`
位置：`UserTaskInstanceCandidateRepository.java:44`

```sql
-- 按审批节点 ID 集合，反查所有候选审批人配置
SELECT *
FROM   flow_instance_node_approval_candidate
WHERE  approval_instance_id IN (:approvalInstanceIds);
```

> 该表通过 `user_id` / `role_id` / `resource_role_identifier` 三种方式记录候选审批人，三者至少存在其一。

### 4'.3 查询「当前用户可审批」的审批节点（getApprovableApprovalInstances）

来源：`ApprovalPermissionService#getApprovableApprovalInstances`
位置：`ApprovalPermissionService.java:99-123`

该方法根据当前用户持有的 **候选用户 / 候选角色 / 资源角色标识** 命中情况，从以下 4 条 SQL 中**按条件择一或择多执行**：

#### (a) 当用户既有候选角色、又有资源角色标识时

```sql
-- ① 按资源角色标识命中（status 排除 CREATED）
SELECT DISTINCT (fai.*)
FROM   flow_instance_node_approval            fai
       INNER JOIN flow_instance_node_approval_candidate faci
               ON fai.id = faci.approval_instance_id
WHERE  fai.status NOT IN (:statuses)
       AND faci.resource_role_identifier IN (:resourceRoleIdentifiers);

-- ② 按候选用户 + 资源角色标识命中（status 排除 CREATED）
SELECT DISTINCT (fai.*)
FROM   flow_instance_node_approval            fai
       INNER JOIN flow_instance_node_approval_candidate faci
               ON fai.id = faci.approval_instance_id
WHERE  fai.status NOT IN (:statuses)
       AND ( faci.user_id = :userId
              OR faci.resource_role_identifier IN (:resourceRoleIdentifiers) );
```

#### (b) 当用户仅有候选角色、无资源角色标识时

```sql
-- 按候选用户 + 候选角色命中（不限定 status）
SELECT DISTINCT (fai.*)
FROM   flow_instance_node_approval            fai
       INNER JOIN flow_instance_node_approval_candidate faci
               ON fai.id = faci.approval_instance_id
WHERE  faci.user_id = :userId
       OR faci.role_id IN (:roleIds);
```

#### (c) 当用户仅有候选用户 ID、无角色且无资源角色标识时

```sql
-- 仅按候选用户命中
SELECT DISTINCT (fai.*)
FROM   flow_instance_node_approval            fai
       INNER JOIN flow_instance_node_approval_candidate faci
               ON fai.id = faci.approval_instance_id
WHERE  faci.user_id = :userId;
```

> 注：`:statuses = {'CREATED'}`（`unViewableStatuses`，排除刚创建未生效的节点）。

### 4'.4 查询「当前用户/角色」为支持审批（getApprovableExternalInstances，可选分支）

来源：`ApprovalPermissionService#listApprovableExternalInstances`
位置：`ApprovalPermissionService.java:126-129`

```sql
-- 查询所有处于执行中(EXECUTING)状态的审批节点（外部审批用，按 status 过滤）
SELECT *
FROM   flow_instance_node_approval
WHERE  status = 'EXECUTING';
```

> 该方法返回结果在内存中再过滤 `external_approval_id IS NOT NULL AND external_flow_instance_id IS NOT NULL`，`detail` 流程主路径不直接调用，列出供完整性参考。

### 4'.5 按流程实例反查「正在执行的审批节点 + 候选审批人」（getCandidatesByFlowInstanceIds）

来源：`ApprovalPermissionService#getCandidatesByFlowInstanceIds`
位置：`ApprovalPermissionService.java:136 → getUsersByFlowInstanceIdsAndStatus`

#### 第 1 步：找出这些流程实例下处于执行中状态的审批节点

```sql
-- :flowInstanceIds 为目标流程实例 ID 集合
-- :status = FlowNodeStatus.getExecutingStatuses() = {'EXECUTING','WAIT_FOR_CONFIRM'}
SELECT *
FROM   flow_instance_node_approval
WHERE  flow_instance_id IN (:flowInstanceIds)
       AND status IN (:status);
```

#### 第 2 步：根据审批节点 ID 反查候选资源角色标识

```sql
-- approval_instance_id 来自第 1 步结果
SELECT *
FROM   flow_instance_node_approval_candidate
WHERE  approval_instance_id IN (:approvalInstanceIds);
```

#### 第 3 步：根据资源角色标识解析出对应用户

```sql
-- 由 ResourceRoleService.listByResourceIdentifierIn 触发
-- resourceRoleIdentifiers 形如 "resourceId:resourceRoleId"，与候选配置一一对应
SELECT t.*
FROM   iam_user_resource_role t
WHERE  Concat(t.resource_id, ':', t.resource_role_id) IN (:resourceRoleIdentifiers);

-- 若命中全局项目角色(OWNER/DBA/Security Admin)，则补充：
SELECT *
FROM   iam_user_role
WHERE  organization_id = :organizationId
       AND name IN ('GLOBAL_PROJECT_OWNER',
                    'GLOBAL_PROJECT_DBA',
                    'GLOBAL_PROJECT_SECURITY_ADMINISTRATOR');
```

#### 第 4 步：根据解析出的用户 ID 查询用户实体

```sql
-- approvalUserIds 来自第 3 步
-- findByUserIdsAndEnabled 对应 enabled 字段，映射到 is_enabled 列
SELECT *
FROM   iam_user
WHERE  id IN (:approvalUserIds)
       AND is_enabled = TRUE;
```

### 4'.6 判断流程实例是否可回滚（间接与审批相关）

来源：`FlowResponseMapperFactory#mapRollbackableStatus`
位置：`FlowResponseMapperFactory.java:452`

```sql
-- 通过统计是否存在 parent_instance_id 指向当前实例的子流程
SELECT   parent_instance_id AS parentInstanceId,
         Count(1)            AS count
FROM     flow_instance
WHERE    parent_instance_id IN (:flowInstanceIds)
GROUP BY parent_instance_id;
```

> 若某个 `flow_instance_id` 在结果中 count == 0（或不存在），则 `rollbackable = true`。

### 4'.7 SQL 访问汇总表（审批相关）

| # | SQL 用途 | 主表 | 关联表 | 触发位置 |
| --- | --- | --- | --- | --- |
| 4'.1 | 加载流程实例的所有审批节点 | `flow_instance_node_approval` | — | `FlowFactory.getApprovalInstances` |
| 4'.2 | 加载审批节点候选审批人配置 | `flow_instance_node_approval_candidate` | — | `generateNodeMapper` |
| 4'.3a | 按资源角色标识查可审批节点 | `flow_instance_node_approval` | `flow_instance_node_approval_candidate` | `getApprovableApprovalInstances` |
| 4'.3b | 按候选用户+候选角色查可审批节点 | `flow_instance_node_approval` | `flow_instance_node_approval_candidate` | `getApprovableApprovalInstances` |
| 4'.3c | 仅按候选用户查可审批节点 | `flow_instance_node_approval` | `flow_instance_node_approval_candidate` | `getApprovableApprovalInstances` |
| 4'.4 | 查询执行中的外部审批节点 | `flow_instance_node_approval` | — | `listApprovableExternalInstances`（可选） |
| 4'.5-1 | 按流程实例查执行中审批节点 | `flow_instance_node_approval` | — | `getCandidatesByFlowInstanceIds` |
| 4'.5-2 | 反查候选资源角色标识 | `flow_instance_node_approval_candidate` | — | `getCandidatesByFlowInstanceIds` |
| 4'.5-3 | 资源角色标识 → 用户 | `iam_user_resource_role` | `iam_user_role`（全局角色） | `ResourceRoleService.listByResourceIdentifierIn` |
| 4'.5-4 | 用户 ID → 用户实体 | `iam_user` | — | `getCandidatesByFlowInstanceIds` |
| 4'.6 | 判断是否可回滚（子流程计数） | `flow_instance` | — | `mapRollbackableStatus` |

### 4'.8 相关表结构速查

#### `flow_instance_node_approval`（审批节点实例）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint PK | 审批节点实例 ID |
| `organization_id` | bigint | 组织 ID |
| `flow_instance_id` | bigint | 所属流程实例 ID（外键 `flow_instance.id`） |
| `user_task_id` | varchar | Flowable 的 userTask ID |
| `status` | varchar | 节点状态（`CREATED`/`EXECUTING`/`WAIT_FOR_CONFIRM`/`COMPLETED`/`FAILED` 等） |
| `operator_id` | bigint | 实际操作（审批/拒绝）人 ID |
| `is_approved` | boolean | 是否通过 |
| `is_auto_approve` | boolean | 是否自动审批 |
| `wait_for_confirm` | boolean | 是否需要二次确认 |
| `external_approval_id` | bigint | 外部审批集成 ID（关联 `integration_integration.id`） |
| `external_flow_instance_id` | varchar | 外部审批流程实例 ID |

#### `flow_instance_node_approval_candidate`（审批候选）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint PK | 主键 |
| `approval_instance_id` | bigint | 审批节点实例 ID（外键 `flow_instance_node_approval.id`） |
| `user_id` | bigint | 候选用户 ID（三选一/多选） |
| `role_id` | bigint | 候选角色 ID |
| `resource_role_identifier` | varchar | 资源角色标识（形如 `resourceId:resourceRoleId`） |

---

## 五、涉及的数据库表汇总

> 以下所有表均位于 **ODC 元数据库（metadb）**，`detailFlowInstance` 为只读查询接口，不涉及任何写操作。

### 5.1 流程相关（核心）

| 表名 | 实体 | 用途 |
| --- | --- | --- |
| `flow_instance` | `FlowInstanceEntity` | 流程实例主表，按主键 `id` 查询；并按 `parent_instance_id` 查询以判断是否可回滚 |
| `flow_instance_node` | `NodeInstanceEntity` | 流程节点实例，描述节点类型 / ActivityId / 名称 |
| `flow_instance_node_gateway` | `GateWayInstanceEntity` | 网关节点（条件路由/开始结束端点） |
| `flow_instance_node_approval` | `UserTaskInstanceEntity` | 审批节点（审批状态、操作人、外部审批关联） |
| `flow_instance_node_task` | `ServiceTaskInstanceEntity` | 任务节点（执行策略、执行时间、关联 `task_task`） |
| `flow_instance_sequence` | `SequenceInstanceEntity` | 节点间的连线（source/target 拓扑） |
| `flow_instance_node_approval_candidate` | `UserTaskInstanceCandidateEntity` | 审批节点的候选审批人（用户/角色/资源角色标识） |

### 5.2 任务与业务对象

| 表名 | 实体 | 用途 |
| --- | --- | --- |
| `task_task` | `TaskEntity` | 真实业务任务（参数、状态、创建人、关联 databaseId） |
| `connect_database` | `DatabaseEntity` | 任务关联的数据库（单库/多库变更、权限申请、结构对比） |
| `connect_connection` | `ConnectionEntity` | 数据库对应的数据源（Connection） |
| `collaboration_project` | `ProjectEntity` | 流程实例 / 数据库所属项目 |

### 5.3 权限与组织（IAM）

| 表名 | 实体 | 用途 |
| --- | --- | --- |
| `iam_user` | `UserEntity` | 用户信息（创建人、操作人、候选审批人） |
| `iam_role` | `RoleEntity` | 角色信息（用户角色展示） |
| `iam_user_role` | `UserRoleEntity` | 用户-角色绑定；全局项目角色（OWNER/DBA/Security Admin）也通过该表解析 |
| `iam_user_resource_role` | `UserResourceRoleEntity` | 用户在资源（项目/数据库）上的细粒度资源角色 |
| `iam_resource_role` | `ResourceRoleEntity` | 资源角色定义（如项目 OWNER/DBA/PARTICIPANT） |

### 5.4 规则与集成

| 表名 | 实体 | 用途 |
| --- | --- | --- |
| `regulation_risklevel` | `RiskLevelEntity` | 风险等级（按 `riskLevelId` 懒加载展示） |
| `integration_integration` | `IntegrationEntity` | 外部审批集成配置（名称、跳转链接懒加载） |

### 5.5 表访问汇总（按出现阶段）

| 阶段 | 访问的表 |
| --- | --- |
| 1. 加载流程拓扑（`FlowFactory`） | `flow_instance`、`flow_instance_node`、`flow_instance_node_gateway`、`flow_instance_node_approval`、`flow_instance_node_task`、`flow_instance_sequence` |
| 2. 权限校验（`withProjectMemberCheck`） | `iam_user_resource_role`、`iam_user_role`（解析全局角色） |
| 3a. 实例级 Mapper（`generateMapper`） | `flow_instance_node_task`、`flow_instance`、`task_task`、`connect_database`、`connect_connection`、`collaboration_project`、`iam_user_resource_role`、`iam_user_role`、`iam_user`、`iam_user_role`、`iam_role`、`flow_instance_node_approval`、`flow_instance_node_approval_candidate`、`regulation_risklevel`（懒加载）、`integration_integration`（懒加载） |
| 3b. 节点级 Mapper（`generateNodeMapper`） | `flow_instance_node_approval`、`iam_user`、`flow_instance_node_approval_candidate`、`iam_user_resource_role`、`iam_user_role`、`flow_instance_node_task`、`task_task`、`iam_user_role`、`iam_role`、`integration_integration`（懒加载） |

---

## 六、调用时序图（文字版）

```
Client  Controller           FlowInstanceService         FlowFactory            PermissionHelper        MapperFactory         ApprovalPermissionService    DB(metadb)
  │         │                       │                         │                       │                       │                       │                      │
  │──GET /{id}──▶│                    │                         │                       │                       │                       │                      │
  │         │──detail(id)──▶│                │                       │                       │                       │                      │
  │         │                       │──getFlowInstance(id)──▶│                       │                       │                       │                      │
  │         │                       │                         │──findById(id)───────────────────────────────────────────────────────────▶ flow_instance
  │         │                       │                         │──findByFlowInstanceId──▶ node ───────────────────────────────────────▶ flow_instance_node
  │         │                       │                         │──gateway findAll──────────────────────────────────────────────────────▶ flow_instance_node_gateway
  │         │                       │                         │──approval findAll─────────────────────────────────────────────────────▶ flow_instance_node_approval
  │         │                       │                         │──task findAll────────────────────────────────────────────────────────▶ flow_instance_node_task
  │         │                       │                         │──sequence findByFlowInstanceId + findByIds──────────────────▶ flow_instance_sequence / flow_instance_node
  │         │                       │   ◀── FlowInstance（含拓扑）──│                       │                       │                      │
  │         │                       │── checkAuth.accept(instance) ──▶ withProjectMemberCheck│                       │                      │
  │         │                       │                         │                       │── hasProjectRole / orgCheck ────────────────▶ iam_user_resource_role / iam_user_role
  │         │                       │── mapper.apply(instance)│                       │                       │                      │
  │         │                       │                         │                       │  generateMapperByInstance + generateNodeMapper│                      │
  │         │                       │                         │                       │   ┌── serviceTask findByFlowInstanceIdIn ──▶ flow_instance_node_task
  │         │                       │                         │                       │   ├── findByParentInstanceIdIn ─────────▶ flow_instance
  │         │                       │                         │                       │   ├── taskRepository idIn ─────────────▶ task_task
  │         │                       │                         │                       │   ├── databaseService.listDatabasesByIds▶ connect_database
  │         │                       │                         │                       │   ├── connectionRepository findAll ─────▶ connect_connection
  │         │                       │                         │                       │   ├── projectService.listByIds ─────────▶ collaboration_project
  │         │                       │                         │                       │   ├── approvalPermissionService.getCandidates / getApprovable ─▶ flow_instance_node_approval(+candidate) / iam_user_resource_role / iam_user_role / iam_user
  │         │                       │                         │                       │   ├── userRepository.findByUserIds ─────▶ iam_user
  │         │                       │                         │                       │   ├── userRole / role ─────────────────▶ iam_user_role / iam_role
  │         │                       │                         │                       │   └── riskLevel findById / integration ─▶ regulation_risklevel / integration_integration
  │         │                       │   ◀── FlowInstanceDetailResp ───────────────────────────────────────────────│                      │
  │ ◀── 200 ──│                       │                         │                       │                       │                       │                      │
```

---

## 七、备注与注意事项

1. **只读接口**：`detailFlowInstance` 全程只读 metadb，无写操作；`FlowFactory` 加载后会调用 `flowInstance.dealloc()` 释放运行时资源（`mapFlowInstance` 的 `finally` 块）。
2. **懒加载风险**：`generateMapper` 返回的 `FlowInstanceMapper` 中，风险等级、外部审批集成名称与跳转链接均通过闭包懒加载，会在 `map(...)` 序列化阶段按需触发额外 SQL（`regulation_risklevel`、`integration_integration`）。
3. **权限校验较轻**：`withProjectMemberCheck` 的权限校验比 `withExecutableCheck`/`withApprovableCheck` 轻，主要校验项目成员身份，不会像审批权限那样加载“所有可审批节点”。
4. **N+1 隐患**：候选审批人查询、外部审批链接查询为闭包内按需执行；如返回的节点较多，可能触发多次 `integration_integration` / `iam_user_resource_role` 查询，可考虑后续批量化优化。
5. **多库/结构对比/权限申请任务**：相关 `databaseId` 是从 `task_task.parameters_json` 反序列化得到的，并非额外表，但得到的 `databaseId` 仍会用于查询 `connect_database`。
6. **跳过权限的分支**：`detail` 走的是 `skipAuth=false`，因此审批人/可审批流程实例相关查询都会执行；若上层调用方使用 `mapFlowInstanceWithoutPermissionCheck`（`detail` 不走该分支），则不会执行这些 IAM 相关查询。
