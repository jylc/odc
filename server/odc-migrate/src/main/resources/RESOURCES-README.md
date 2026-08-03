# ODC 迁移资源目录说明文档

> 分析对象：`server/odc-migrate/src/main/resources`
> 生成时间：2026-06-17
> 适用版本范围：ODC 2.0.0 ~ 4.3.4（目录中已出现的最新迁移版本）

本目录是 OceanBase Developer Center（ODC）**元数据库（MetaDB）** 的版本化建表与初始化脚本仓库，采用类 Flyway 的版本号机制对库结构进行演进管理。本文档对目录结构、命名规范、版本演进、数据库表分类、关键表字段、YAML 资源文件格式以及初始化数据配置进行全面解析。

---

## 一、目录总体结构

```
resources/
├── init-config/                  # 初始化配置数据（首次部署 / 升级时写入的元数据）
│   ├── default-audit-event-meta.yml          # 审计事件元信息默认值
│   ├── init/                                 # 静态初始化数据（规则元数据、通知策略）
│   │   ├── notification-policy-metadata.yaml
│   │   ├── regulation-rule-applying.yaml
│   │   └── regulation-rule-metadata.yaml
│   └── runtime/                              # 运行态初始化数据（管理员账号、私连迁移）
│       ├── iam_user_system_admin.yaml
│       └── private_connection_migrate.sql
├── migrate/                      # 版本化数据库迁移脚本（核心）
│   ├── common/                               # 通用迁移（所有 MetaDB 类型共用，138 个文件）
│   ├── h2/                                   # H2 兼容性补丁（12 个文件）
│   ├── oceanbase/                            # OceanBase 专用迁移（含 Flowable 初始化，16 个文件）
│   ├── rbac/                                 # RBAC 角色权限初始化（4 个 YAML）
│   ├── vectordb/                             # 向量库占位（预留，1 个占位文件）
│   └── web/                                  # Web 版独有初始化（管理员用户/组织，8 个文件）
└── runtime/                      # 运行态资源初始化（YAML，按组织生成内置数据，13 个文件）
    └── V_4_2_0_*__*.yaml
```

### 1.1 三类资源的职责划分

| 目录 | 用途 | 执行时机 | 介质 |
|------|------|----------|------|
| `migrate/` | 版本化 **DDL/DML 迁移**（建表、改表、加索引、补字段） | 数据库版本升级时按版本号顺序执行 | SQL 为主，YAML 为辅 |
| `init-config/` | 一次性的 **静态元数据初始化**（审计、规则、通知策略） | 首次部署或版本首次启动 | YAML / SQL |
| `runtime/` | **运行态资源初始化**（每个组织创建时写入的内置角色、权限、风险等级等） | 组织级数据装配 | YAML |

---

## 二、命名规范与版本管理

### 2.1 SQL/YAML 文件命名约定

文件名遵循类 Flyway 风格：

```
{前缀}_{主版本}_{次版本}_{修订号}_{补丁}__{描述}.{sql|yaml}
```

- **前缀含义**：
  - `V_`（Versioned）：版本化迁移，**仅执行一次**，幂等要求由脚本自身保证（多用 `CREATE TABLE IF NOT EXISTS`、`ON DUPLICATE KEY UPDATE`）。
  - `R_`（Repeatable）：可重复执行迁移，每次校验变化后重新执行（如 `R_3_3_1__initialize_flowable.sql`、`R_2_0_0__initialize_version_diff_config.sql`）。

- **版本号**：以下划线分隔的多段数字，按字典序升序执行。例如：
  - `V_3_2_0__enterprise_console_schema.sql`
  - `V_4_3_4_1__add_supervisor_agent.sql`（当前最新）

- **双下划线 `__`**：分隔版本号与人类可读描述，描述用下划线连接英文短语。

- **方言分组**：同一逻辑变更在不同数据库类型下分别提供补丁文件，序号与 common 主迁移对齐，例如：
  - common 主表：`V_3_2_0__enterprise_console_schema.sql`
  - H2 适配：`h2/V_3_2_2_1__add_and_rename_task_info_column.sql`
  - OB 适配：`oceanbase/V_3_2_2_1__add_and_rename_task_info_column.sql`

### 2.2 版本演进脉络

| 大版本 | 阶段特征 | 代表性脚本 |
|--------|----------|------------|
| 2.x | 单机/桌面版基础结构（私有脚本、用户、连接、配置） | `V_2_0_0__init.sql` |
| 3.2.0 | **企业版控制台**架构引入（IAM、组织、连接、系统配置） | `V_3_2_0__enterprise_console_schema.sql` |
| 3.3.0 | **流程引擎**（Flowable 集成）、任务、审计、对象存储 | `V_3_3_0_1__add_flow.sql`、`V_3_3_0__add_audit_event_and_meta.sql` |
| 3.5.0 | 影子表、分区计划 | `V_3_5_0_1__shadowtable.sql`、`V_3_5_0_8__partition_plan.sql` |
| 4.1.0 | Quartz 调度、自动化规则、计划任务、通知 | `V_4_1_0_1__quartz.sql`、`V_4_1_0_6__automation.sql` |
| 4.2.0 | **协作空间**（项目、环境）、资源角色、敏感数据脱敏、应用集成 | `V_4_2_0_6__add_project.sql`、`V_4_2_0_14__add_sensitive_data.sql` |
| 4.2.3~4.2.4 | 任务框架重构（job_job）、结构对比、视图与权限收敛 | `V_4_2_3_12__add_job_job.sql` |
| 4.3.0~4.3.4 | 逻辑库、变更工单模板、资源调度（K8S/Supervisor）、AI 调度 | `V_4_3_1_4__add_logical_database.sql`、`V_4_3_4_1__add_supervisor_agent.sql` |

---

## 三、YAML 资源文件格式解析

migrate 与 runtime 目录下大量使用 YAML（而非 SQL）写入内置数据。其统一格式如下，由 `ResourceMigration` 引擎解析：

```yaml
kind: resource
version: v2
templates:                          # 每个元素 = 一条记录
  - metadata:
      allow_duplicate: false        # 是否允许重复写入
      table_name: iam_permission    # 目标表
      unique_keys: ["action", "organization_id", "resource_identifier", "type"]  # 幂等判定键
    specs:                          # 列与值
      - column_name: id
        default_value: 73           # 默认 ID（当数据库未自增时使用）
        data_type: java.lang.Long
      - column_name: action
        value: "OWNER"
      - column_name: organization_id
        value: ${ORGANIZATION_ID}   # 变量占位，运行时由创建组织注入
        data_type: java.lang.Long
      - column_name: permission_id
        value_from:                 # 跨文件引用（field_ref），保证 ID 一致
          field_ref:
            ref_file: migrate/common/V_4_3_3_4__iam_role.yaml
            field_path: templates.0.specs.0.value
```

**核心机制**：
- `${ORGANIZATION_ID}` / `${CREATOR_ID}`：运行时变量，每个组织装配时注入，保证内置数据按组织隔离。
- `field_ref`：跨文件字段引用，避免硬编码 ID，保持角色↔权限↔关联表 ID 的强一致。
- `unique_keys`：用于 `INSERT ... ON DUPLICATE KEY UPDATE` 幂等写入。

---

## 四、数据库表分类总览

ODC 的 MetaDB（推荐 OceanBase MySQL 模式，开发/测试可降级 H2）按业务域划分为以下几大模块。下表给出全局索引，后续章节逐域解析。

| 模块（表前缀） | 职责 | 主要表数量 | 引入版本 |
|----------------|------|-----------|----------|
| `odc_*` | 早期单机版遗留表（用户、脚本、会话、配置、版本差异配置、片段、标签） | ~8 | 2.0.0 |
| `iam_*` | 身份与访问管理（用户、组织、角色、权限、登录历史、资源角色） | ~14 | 3.2.0 / 4.2.0 |
| `connect_*` | 数据源连接与库元数据（连接、库、会话历史、库映射、连接属性、同步历史） | ~7 | 3.2.0 / 4.2.0 |
| `config_*` | 系统级与组织级配置 | 2 | 3.2.0 |
| `flow_*` / `ACT_*` | 流程引擎（Flowable 自管 + ODC 业务流程实例/配置） | ~16 | 3.3.0 |
| `task_*` / `job_*` / `QRTZ_*` | 任务调度与执行框架（旧 task_task、新 job_job、Quartz、Supervisor） | ~10 | 3.3.0 / 4.1.0 / 4.2.3 |
| `schedule_*` | 计划任务（定时触发） | 2 | 4.1.0 |
| `audit_*` | 审计事件与元信息 | 2 | 3.3.0 |
| `objectstorage_*` | 内建对象存储（分块文件，承载脚本/导出结果） | 3 | 3.3.0 |
| `notification_*` | 通知（事件、通道、策略、消息） | 6 | 4.1.3 |
| `automation_*` | 自动化触发规则（事件→条件→动作） | 4 | 4.1.0 |
| `collaboration_*` | 协作空间（项目、环境） | 2 | 4.2.0 |
| `regulation_*` | 合规管控（规则集、规则、风险等级、风险检测规则） | ~5 | 3.2.0+（由 YAML+Service 建） |
| `data_security_*` | 敏感数据与脱敏（敏感列/规则、脱敏算法及分段） | 4 | 4.2.0 |
| `shadowtable_*` | 影子表结构对比 | 2 | 3.5.0 |
| `connection_*` / `table_partition_*` | 自动分区计划 | 2 | 3.5.0 |
| `integration_*` | 应用集成（外部系统） | 1 | 4.2.0 |
| `database_*` | 数据库对象元数据（表/视图/函数等对象、列、表映射） | 4 | 4.3.0 |
| `databasechange_*` / `logicaldatabase_*` | 数据库变更工单与逻辑库变更 | 2 | 4.3.0 / 4.3.2 |
| `structure_comparison_*` | 结构对比任务 | 2 | 4.2.4 |
| `dlm_*` | 数据生命周期管理（归档/清理任务生成器与单元） | 2 | 4.2.2 |
| `resource_*` / `task_supervisor_*` | 资源管理与任务执行器端点（K8S/进程） | 2 | 4.3.2 / 4.3.4 |

---

## 五、各模块表结构详解

### 5.1 早期单机版遗留表（`odc_*`）

源自 v2.0.0 单机/桌面版，部分已被新表取代但保留以做数据迁移。

| 表名 | 说明 | 关键字段 | 来源文件 |
|------|------|----------|----------|
| `odc_user_info` | 旧版用户表 | id, name, email, password, role, status | `V_2_0_0__init.sql` |
| `odc_sql_script` | 用户保存的 SQL 脚本 | user_id, script_name, script_text | `V_2_0_0__init.sql` |
| `odc_configuration` | 旧 KV 配置（已被 `config_system_configuration` 取代） | key, value | `V_2_0_0__init.sql` |
| `odc_session_manager` | 旧私有会话管理 | user_id, host, port, cluster, tenant, db_user, password | `V_2_0_0__init.sql` |
| `odc_version_diff_config` | 版本差异配置（不同 OB 版本的能力矩阵） | config_key, db_mode, config_value, min_version | `V_2_0_0__init.sql`，由 `R_2_0_0__initialize_version_diff_config.sql` 初始化数据 |
| `odc_snippet` | SQL 代码片段 | user_id, prefix, body, type | `V_2_4_0__add_snippet_and_label.sql` |
| `odc_session_label` | 会话标签 | user_id, label_name, label_color | `V_2_4_0__add_snippet_and_label.sql` |
| `odc_user_token` | 用户令牌 | （见 `V_2_1_0__add_user_token.sql`） | `V_2_1_0` |

> 迁移链路：`init-config/runtime/private_connection_migrate.sql` 描述了如何把 `odc_session_manager` 旧数据迁入新版 `connect_connection`。

### 5.2 身份与访问管理（`iam_*`）

IAM 模块支撑 ODC 的多租户、RBAC、资源角色三大体系。

#### 5.2.1 用户与组织

| 表名 | 说明 | 关键约束 |
|------|------|----------|
| `iam_organization` | 组织（租户顶层实体） | UK: `unique_identifier`、`name`；含 `secret`（公网连接加密密钥） |
| `iam_user` | 用户 | UK: `(organization_id, account_name)`；`password`+`cipher`（RAW/BCRYPT/AES256SALT） |
| `iam_login_history` | 登录历史（成功/失败、失败原因） | 索引 `(user_id, login_time)`，需定期清理/轮转 |

#### 5.2.2 角色与权限（经典 RBAC）

| 表名 | 说明 |
|------|------|
| `iam_role` | 角色（type: ADMIN/INTERNAL/CUSTOM；内置角色 id≥10000） |
| `iam_user_role` | 用户-角色关联（UK: `user_id, role_id`） |
| `iam_permission` | 权限（action: query/create/update/delete/read/readandwrite；resource_identifier 形如 `resource_group:10`） |
| `iam_role_permission` | 角色-权限关联 |

#### 5.2.3 资源角色（4.2.0 引入的细粒度授权）

针对具体资源（如项目）的角色，独立于全局 RBAC。

| 表名 | 说明 |
|------|------|
| `iam_resource_role` | 资源角色元数据（resource_type=ODC_PROJECT，role_name=OWNER/DBA/DEVELOPER/PARTICIPANT 等），**不按组织隔离**（全局元数据） |
| `iam_user_resource_role` | 用户在具体资源上的角色（UK: `user_id, resource_id, resource_role_id`） |
| `iam_resource_role_permission` | 资源角色→动作映射（action 为逗号分隔的动作串） |
| `iam_resource_group` / `iam_resource_group_resource` | 资源组及组-资源关联（旧版分组能力） |

内置资源角色由 `migrate/common/V_4_2_0_24__resource_role.yaml` 装配。

### 5.3 连接与库元数据（`connect_*`）

| 表名 | 说明 | 关键字段 / 备注 |
|------|------|-----------------|
| `connect_connection` | **核心表**：数据源连接定义 | dialect_type(OB_MYSQL/OB_ORACLE/MYSQL/ORACLE/ODP_SHARDING)、host/port/cluster/tenant、username/password+cipher+salt、sys_tenant_*、readonly_*、environment_id（4.2.0+） |
| `connect_session_history` | 连接会话历史（审计用） | organization_id, connection_id, connect_time, user_id, client_address, server |
| `connect_database` | 库元数据（4.2.0 引入，绑定项目与环境） | database_id, project_id, connection_id, environment_id, sync_status, table_count；4.3.1 增加 type(PHYSICAL/LOGICAL)、alias、dialect_type |
| `connect_database_mapping` | 逻辑库→物理库映射（4.3.1） | logical_database_id ↔ physical_database_id（1:N） |
| `connect_connection_attribute` | 连接扩展属性 | 见 `V_4_2_2_3` |
| `connect_connection_sync_history` | 连接同步历史 | 见 `V_4_3_2_20` |

> `visible_scope`、`owner_id` 在 4.2.0 弃用（迁移到项目模型），通过 ALTER 去除 NOT NULL。

### 5.4 配置（`config_*`）

| 表名 | 说明 |
|------|------|
| `config_system_configuration` | 系统级配置（管理员维护，含 application/profile/label 维度），如 `odc.task.default-execution-expiration-interval-hours` |
| `config_organization_configuration` | 组织级配置（按组织隔离） |

`V_3_2_0_1__common_data_migration.sql` 描述了从 `odc_configuration` → `config_system_configuration` 的迁移，并初始化阿里云/专有云（AAS/OAM/RAM/OCP）相关密钥占位（`CHANGE_ME`）。

### 5.5 流程引擎（`flow_*` + `ACT_*`）

ODC 用 Flowable 做 BPMN 流程驱动，分两层：

**Flowable 自管表（`ACT_*`）**：由 `oceanbase/R_3_3_1__initialize_flowable.sql` 初始化，包含 `ACT_RE_*`(仓库/部署)、`ACT_RU_*`(运行时)、`ACT_HI_*`(历史)、`ACT_ID_*`(身份)、`ACT_GE_*`(通用)、`ACT_EVT_*`(事件)。

**ODC 业务流程表（`flow_*`）**：

| 表名 | 说明 |
|------|------|
| `flow_config` | 流程配置（变更流程定义：general_type=ASYNC/IMPORT/EXPORT/MOCKDATA，sub_type=insert/update/...，含审批/等待/执行有效期） |
| `flow_instance` | 流程实例（status: CREATED/EXECUTING/KILLED/COMPLETED/EXPIRED/FAILED；含 flowable 的 process_definition_id/process_instance_id、快照 XML、parent_instance_id） |
| `flow_config_node` / `flow_instance_node` | 节点配置/实例（节点类型 APPROVAL_TASK/GATEWAY/SERVICE_TASK，映射 flowable 的 activity_id） |
| `flow_config_node_approval` / `flow_instance_node_approval` | 审批节点（审批人/候选人/超时/是否批准/起止端点） |
| `flow_config_node_approval_candidate` / `flow_instance_node_approval_candidate` | 审批候选人（user_id 或 role_id） |
| `flow_instance_node_gateway` | 网关实例 |
| `flow_config_node_task` / `flow_instance_node_task` | 任务节点（task_type、task_execution_strategy=AUTO/MANUAL/TIMER） |
| `flow_config_node_gateway` | 网关节点配置 |
| `flow_config_sequence` / `flow_instance_sequence` | 连线（源/目标节点，条件表达式、risk_level、risk_level_config_id） |
| `flow_config_risk_level` | 风险等级配置（按 sub_type、是否含风险数据、影响行数区间） |
| `flow_config_sub_task_type` | 流程配置↔任务子类型映射 |

审批流（`approval_flow_config`、`approval_flow_node_config`）表由 `runtime/` 下 YAML 引用并初始化。

### 5.6 任务调度与执行框架

ODC 经历了三代任务框架：

**第一代（3.3.0）— `task_task`**：直接挂在流程节点下。
- 字段：task_type(ASYNC/IMPORT/EXPORT/MOCKDATA)、connection_id、parameters_json、status、progress_percentage、result_json、executor。

**第二代（4.1.0）— Quartz `QRTZ_*`**：标准 Quartz 11 张表（`V_4_1_0_1__quartz.sql`），支撑定时调度。
- 配套：`schedule_schedule`（计划任务定义，触发器配置、误触发策略）、`schedule_task`（单次触发的任务实例）、`schedule_change_log`。

**第三代（4.2.3）— `job_job`**：自研分布式任务框架。
- `job_job`：任务实例（job_class、job_type、status(PREPARING/RUNNING/RETRYING/FAILED/CANCELING/CANCELED/DONE)、run_mode(PROCESS/K8S)、executor_endpoint、心跳/上报时间）。
- `job_job_attribute`：任务扩展属性（KV，UK: `job_id, attribute_key`）。

**资源调度（4.3.x）**：
- `resource_resource`：资源（region/group/namespace/name、resource_type=memory/k8s、endpoint、status=CREATING/RUNNING/DESTROYING/...）。
- `task_supervisor_endpoint`：Supervisor 端点（host/port、status、loads、resource_region/group），用于按区域过滤可用的执行器。

### 5.7 审计（`audit_*`）

| 表名 | 说明 |
|------|------|
| `audit_event` | 审计事件（type/action、连接快照字段、client/server IP、detail(JSON)、result=SUCCESS/FAIL/UNKNOWN、start/end_time、task_id），索引覆盖 `org+user+time`、`org+time`、`task_id` |
| `audit_event_meta` | 审计元信息（type/action 与 API method_signature 映射，sid_extract_expression 为 SpEL，in_connection 标记是否在连接内） |

默认元数据由 `init-config/default-audit-event-meta.yml` 装配（密码管理、连接管理、会话、任务等几十类操作）。

### 5.8 对象存储（`objectstorage_*`）

ODC 内建的轻量对象存储，承载 SQL 脚本、导出文件、结构对比脚本等大对象。

| 表名 | 说明 |
|------|------|
| `objectstorage_bucket` | 桶 |
| `objectstorage_object_metadata` | 对象元数据（object_id、bucket、total_length、split_length、sha1、status=INIT/FINISHED） |
| `objectstorage_object_block` | 对象分块（object_id+block_index，content=MEDIUMBLOB，单块≤16M） |

### 5.9 通知（`notification_*`）

完整的事件→策略→通道→消息链路。

| 表名 | 说明 |
|------|------|
| `notification_event` | 触发的通知事件（trigger_time、status=CREATED/THROWN/CONVERTED） |
| `notification_event_label` | 事件标签（KV，UK: `event_id, key`） |
| `notification_channel` | 通道（type=DingTalkGroupBot/SMS/...） |
| `notification_channel_property` | 通道属性（KV） |
| `notification_policy` | 通知策略（title/content 模板、match_expression_json 匹配表达式、to/cc 收件人） |
| `notification_policy_channel_relation` | 策略↔通道关联 |
| `notification_message` | 最终消息（status、retry_times、max_retry_times、recipients） |

策略匹配表达式模板由 `init-config/init/notification-policy-metadata.yaml` 提供（如 `taskType.equals('ASYNC') && taskStatus.equals('EXECUTION_SUCCEEDED')`）。

### 5.10 自动化规则（`automation_*`）

事件驱动的自动化（Event→Condition→Action）。

| 表名 | 说明 |
|------|------|
| `automation_event_metadata` | 触发事件元数据（name、variable_names、is_builtin/is_hidden） |
| `automation_rule` | 规则（绑定 event_id，按组织） |
| `automation_condition` | 触发条件（object/expression/operation/value 四元组） |
| `automation_action` | 动作（action 名 + args_json_array） |

事件元数据初始化见 `V_4_1_0_7__automation_event_metadata.yaml` 与 `V_4_2_0_8__add_automation_event_metadata.yaml`。

### 5.11 协作空间（`collaboration_*`）

| 表名 | 说明 |
|------|------|
| `collaboration_project` | 项目（is_archived 归档标记，UK: `org, name`） |
| `collaboration_environment` | 环境（style=GREEN/YELLOW/RED，绑定 ruleset_id） |

环境与规则集（regulation_ruleset）关联，是合规管控的承载点。

### 5.12 合规管控（`regulation_*`）

> 注：`regulation_*` 表的建表 DDL 由 `odc-service` 的实体/Schema 模块在运行时自动创建，本目录主要通过 YAML 装配内置数据，少量 ALTER 脚本（如 `V_4_3_2_11__alter_regulation_riskdetect_rule.sql`、`V_4_2_4_14__truncate_default_risk_level_detect_rules.sql`）调整结构。

| 表/概念 | 说明 | 数据来源 |
|---------|------|----------|
| `regulation_ruleset` | 规则集（默认 dev/sit/default 三套） | `runtime/V_4_2_0_20__regulation_ruleset.yaml` |
| `regulation_rule_metadata` | 规则元数据（SQL_CONSOLE/SQL_CHECK/EXTERNAL 审批类） | `init-config/init/regulation-rule-metadata.yaml` |
| `regulation_rule_applying` | 规则在规则集下的应用（applied dialect types、propertiesJson） | `init-config/init/regulation-rule-applying.yaml` |
| `regulation_risklevel` | 风险等级（0=default/1=low/2=middle/3=high，颜色 GRAY/BLUE/YELLOW/RED，绑定 approval_flow_config_id） | `runtime/V_4_2_0_31__risklevel.yaml` |
| `regulation_risk_detect_rule` | 风险检测规则 | `runtime/V_4_2_4_13__default_risk_detect_rule.yaml` |

规则元数据示例（节选）：`sql-console.not-allowed-edit-resultset`、`sql-console.max-query-limit`、`sql-check.*`，通过 `SUB_TYPE`、`SUPPORTED_DIALECT_TYPE` 标签做维度过滤。

### 5.13 敏感数据与脱敏（`data_security_*`）

| 表名 | 说明 |
|------|------|
| `data_security_sensitive_column` | 敏感列（database_id+table+column、sensitive_level=HIGH/...、masking_algorithm_id） |
| `data_security_sensitive_rule` | 敏感规则（type=REGEX/GROOVY/PATH，含各类正则与脚本，绑定 project_id 与 masking_algorithm_id） |
| `data_security_masking_algorithm` | 脱敏算法（type=MASK/SUBSTITUTION/PSEUDO/HASH/ROUNDING/...，sample_content/masked_content 样例） |
| `data_security_masking_algorithm_segment` | 算法分段（按 DELIMITER/DIGIT/DIGIT_PERCENTAGE 切分，决定每段是否掩码） |

内置算法数据由 `runtime/V_4_2_0_27__add_masking_algorithm.yaml`、`V_4_2_0_28__add_masking_algorithm_segment.yaml` 装配（SM3/国密等）。

### 5.14 影子表与结构对比

| 表名 | 说明 |
|------|------|
| `shadowtable_table_comparing_task` | 影子表对比任务（connection_id、schema_name、flow_instance_id） |
| `shadowtable_table_comparing` | 单表对比结果（comparing_result=CREATE/UPDATE/NO_ACTION/SKIP，源/目标/变更 DDL） |
| `structure_comparison_task` | 结构对比任务（源/目标 connect_database_id，存储对象 ID） |
| `structure_comparison_task_result` | 对比结果（comparing_result=ONLY_IN_SOURCE/CONSISTENT/INCONSISTENT/...，对象 DDL 与 change_sql_script） |

### 5.15 自动分区（`connection_partition_plan` / `table_partition_plan`）

| 表名 | 说明 |
|------|------|
| `connection_partition_plan` | 连接级分区巡检配置（inspect_trigger_strategy=EVERY_DAY/FIRST_DAY_OF_MONTH/...，is_inspect_enabled） |
| `table_partition_plan` | 表级分区计划（partition_interval/unit、pre_create_partition_count、expire_period、命名规则前后缀） |

### 5.16 数据生命周期管理（`dlm_*`）

| 表名 | 说明 |
|------|------|
| `dlm_task_generator` | 任务生成器（generator_id、job_id、processed_data_size/row_count、主键/分区 savepoint、task_count） |
| `dlm_task_unit` | 任务单元（task_index、lower/upper_bound_primary_key、primary_key_cursor、partition_name），支持断点续传 |
| `dlm_limiter_config` | DLM 限流配置 |

### 5.17 应用集成与数据库变更

| 表名 | 说明 |
|------|------|
| `integration_integration` | 外部系统集成（type、configuration(JSON)、encrypted/algorithm/secret/salt 加密） |
| `databasechange_changingorder_template` | 变更工单模板（database_sequences 执行顺序，绑定 project_id） |
| `logicaldatabase_database_change_execution_unit` | 逻辑库变更执行单元（execution_id/order、schedule_task_id、sql_content、execution_result_json、status） |

### 5.18 数据库对象元数据（`database_*`）

| 表名 | 说明 |
|------|------|
| `database_schema_object` | 库对象（type=TABLE/VIEW/FUNCTION/PROCEDURE/PACKAGE/TRIGGER/TYPE/SEQUENCE/SYNONYM/PUBLIC_SYNONYM，绑定 database_id），索引 `(database_id, type, name)` |
| `database_schema_column` | 列（UK: `database_id, object_id, name`） |
| `database_table_mapping` | 逻辑表→物理表映射（expression 如 `db_[0-3].tb_[0-3]`，is_consistent 一致性标记，physical_table_id 4.3.2 加入） |
| `database_access_history` | 库访问历史（4.3.4） |

`connect_database` 在 4.3.0 增加 `object_sync_status`（INITIALIZED/PENDING/SYNCING/FAILED/SYNCED）与 `object_last_sync_time`，配合对象元数据同步。

### 5.19 视图

| 视图 | 说明 | 来源 |
|------|------|------|
| `list_flow_instance_view` | 流程实例列表视图（多次 ALTER/REPLACE） | `V_4_2_0_41`、`V_4_2_3_1/3/18` |
| `list_user_database_permission_view` | 用户库权限视图 | `V_4_2_4_6` |
| `list_user_table_permission_view` | 用户表权限视图 | `V_4_3_1_2`、`V_4_3_2_12` |

### 5.20 其它辅助表

- `iam_user_default_roles`（用户默认角色，`V_4_2_0_36`）。
- `history_resource_last_access`（资源最近访问历史，`V_4_3_2_6`）。
- `file_import_history`（文件导入历史，`V_4_3_3_10`）。
- `resource_allocate_info`（资源分配信息，`V_4_3_4_2`）。
- `iam_user_extra_info`（用户扩展信息，`V_4_2_0_12`）。
- `flow_task_heartbeat`（流程任务心跳，`V_4_3_0_6`）。
- `cloud_load_data_task`（云上数据加载任务，`V_4_1_3_2`）。

---

## 六、初始化配置数据详解（init-config）

### 6.1 `default-audit-event-meta.yml`

预置约百条审计事件元数据，每条记录：`type` / `action` / `method_signature`（精确到 Controller 方法）/ `sid_extract_expression`（SpEL，从入参提取连接 ID）/ `in_connection` / `enabled`。覆盖：
- **密码管理**：SET/RESET/CHANGE_PASSWORD
- **连接管理**：CREATE/DELETE/UPDATE_CONNECTION、CREATE_SESSION
- **个人配置**：UPDATE_PERSONAL_CONFIGURATION
- 其余由业务 Controller 方法签名关联（详见文件内 `method_signature` 列）。

### 6.2 `init/regulation-rule-metadata.yaml`（58 KB）

合规规则元数据全集，每条规则含 `id` / `name`（i18n 占位）/ `description` / `type`(SQL_CONSOLE/SQL_CHECK/EXTERNAL) / `builtIn` / `labels`。标签维度：
- `SUB_TYPE`：SECURITY/PERMISSION/...（分类）
- `SUPPORTED_DIALECT_TYPE`：OB_MYSQL/OB_ORACLE/MYSQL/DORIS/ODP_SHARDING_OB_MYSQL/ORACLE（方言支持矩阵）

### 6.3 `init/regulation-rule-applying.yaml`（109 KB）

规则在"默认规则集"下的具体应用：`enabled` / `level`（优先级）/ `rulesetName` / `ruleName` / `appliedDialectTypes` / `propertiesJson`（规则参数，如 `max-query-limit: 1000`）。

### 6.4 `init/notification-policy-metadata.yaml`（25 KB）

通知策略匹配表达式模板，按 `event_category`(NONE/TASK/...) 与 `event_name` 组织，`match_expression` 用 SpEL 表达事件条件。

### 6.5 `runtime/iam_user_system_admin.yaml`

创建组织时，为 `${CREATOR_ID}`（系统管理员）绑定系统内置角色（引用 `migrate/rbac/V_3_2_0_5__iam_role.yaml` 的角色 id）。

### 6.6 `runtime/private_connection_migrate.sql`

将旧 `odc_session_manager` 的私有会话迁入 `connect_connection` 的查询模板（按 user_id 过滤，LEFT JOIN 去重）。

---

## 七、runtime 资源初始化详解

`runtime/` 目录下 13 个 YAML 用于在**新建组织**时注入内置业务数据（与 `init-config/init` 的全局静态数据不同，runtime 数据按 `${ORGANIZATION_ID}` 隔离）。

| 文件 | 作用 |
|------|------|
| `V_4_2_0_18__iam_permission.yaml`（18 KB） | 内置系统权限（如 `ODC_PROJECT:*` 的 OWNER/DBA/PARTICIPANT 等） |
| `V_4_2_0_19__add_iam_role_permission.yaml` | 角色-权限关联 |
| `V_4_2_0_20__regulation_ruleset.yaml` | 三套默认规则集（dev/sit/default） |
| `V_4_2_0_21__environment.yaml` | 默认环境（与规则集绑定） |
| `V_4_2_0_27__add_masking_algorithm.yaml` | 内置脱敏算法 |
| `V_4_2_0_28__add_masking_algorithm_segment.yaml` | 脱敏算法分段 |
| `V_4_2_0_29__approval_flow_config.yaml` | 默认审批流配置（每个风险等级一条） |
| `V_4_2_0_30__approval_flow_node_config.yaml` | 审批流节点配置 |
| `V_4_2_0_31__risklevel.yaml` | 四级风险等级（default/low/middle/high） |
| `V_4_2_0_4__iam_permission.yaml` | 早期权限补丁 |
| `V_4_2_0_5__iam_role_permission.yaml` | 早期角色-权限 |
| `V_4_2_0_8__add_automation_event_metadata.yaml` | 自动化事件元数据 |
| `V_4_2_4_13__default_risk_detect_rule.yaml` | 默认风险检测规则 |

这些文件之间通过 `field_ref` 互相引用 ID，形成完整的"权限→角色→规则→环境→风险等级→审批流"闭环。

---

## 八、方言适配（h2 / oceanbase / vectordb / web）

### 8.1 `migrate/h2/`（12 个文件）

针对 H2 内存库（单元测试 / 桌面版降级）的兼容补丁，主要内容是字段类型与默认值调整，例如：
- `V_2_4_1_1__gmt_modify_default_password_size.sql`：调整 password 列长度。
- `V_4_2_0_42__transfer_fetch_size.sql`：H2 fetch size 设置。
- `V_4_3_3_1__alter_integration.sql`：集成表字段在 H2 下的差异。

### 8.2 `migrate/oceanbase/`（16 个文件）

OceanBase 专用，**含两块重要内容**：
1. **Flowable 表初始化**：`R_3_3_1__initialize_flowable.sql`（30 KB），创建全部 `ACT_*` 表。
2. **Spring Session 管理**：`V_3_4_0_11__add_spring_session.sql` 建表，`V_3_5_0_13`、`V_4_1_0_19`、`V_4_1_2_3`、`V_4_2_0_37` 多次 `TRUNCATE` 以应对会话结构变更。
3. **状态化路由**：`V_4_2_4_18__add_stateful_route.sql`（OB 下的有状态路由表）。

### 8.3 `migrate/vectordb/`

仅一个占位文件 `V_2_0_0__placeholder.sql`，为未来向量数据库（AI Copilot 场景）预留。

### 8.4 `migrate/web/`（8 个文件）

Web 版（多用户）独有的初始化：
- `V_3_2_0_4__connection_migration.sql`：连接数据迁移。
- `V_3_2_0_8__iam_organization.yaml`：默认组织。
- `V_3_2_0_9__iam_user.yaml`：内置 `admin` 用户（BCrypt 密码，`is_builtin=true`，id=1）。
- `V_3_2_0_10__iam_user_role.yaml`：admin 的角色绑定。
- `V_4_2_1_2/3__iam_role*.yaml`：4.2.1 角色补丁。
- `V_4_3_1_6__init_max_login_record_time.sql`：登录记录时间初始化。

### 8.5 `migrate/rbac/`（4 个 YAML）

RBAC 内置角色与权限元数据：
- `V_3_2_0_5__iam_role.yaml`：系统角色（如系统管理员，被 `iam_user_system_admin.yaml` 引用）。
- `V_3_2_0_7__iam_role_permission.yaml` / `V_3_3_0_10` / `V_4_1_0_15`：随版本演进的角色-权限映射。

---

## 九、关键设计要点

### 9.1 多租户隔离

几乎所有业务表都带 `organization_id`，作为行级租户隔离字段；唯一约束通常包含 `organization_id`（如 `uk_iam_user_organization_id_account_name`）。内置元数据表（如 `iam_resource_role`、`regulation_rule_metadata`）例外，全局共享。

### 9.2 密码与密钥加密

连接、用户、集成等敏感表采用统一的加密字段三元组：
- `cipher`：算法标识（RAW / AES256SALT / BCRYPT）。
- `salt`：随机盐。
- 实际密文存在 `password` / `secret` 字段。

### 9.3 幂等性

所有迁移脚本严格幂等：
- DDL：`CREATE TABLE IF NOT EXISTS`、`ADD COLUMN` 前用条件判断或重复执行安全写法。
- DML：`ON DUPLICATE KEY UPDATE id=id`（无副作用 upsert）。
- YAML：`allow_duplicate: false` + `unique_keys`。

### 9.4 三层模型：配置→流程→任务

ODC 的数据库变更遵循 **流程配置（flow_config）→ 流程实例（flow_instance）→ 任务（task_task / job_job / schedule_task）** 的分层，审批节点（flow_instance_node_approval）与风险等级（regulation_risklevel + flow_config_risk_level）联动，决定变更走哪条审批链路。

### 9.5 资源调度的演进

- 4.1.0：Quartz 集群调度（`QRTZ_*` + `schedule_*`）。
- 4.2.3：自研 `job_job` 框架，支持 PROCESS/K8S 两种 run_mode。
- 4.3.x：引入 `resource_resource`（资源池）+ `task_supervisor_endpoint`（执行器端点），按 region/group 调度，支撑 AI Copilot 等需要独立执行环境的能力。

---

## 十、迁移执行与维护建议

### 10.1 执行流程

1. 应用启动时，`ResourceMigration` / Flywind 引擎扫描 `migrate/common`（按文件名版本号升序）+ 对应方言目录。
2. 对比 `metadb` 已记录的版本号，执行未应用的 V_/R_ 脚本。
3. 首次部署额外加载 `init-config/init` 的静态 YAML。
4. 每个新建组织触发 `runtime/` 与 `init-config/runtime` 的按组织装配。

### 10.2 排查指引

- **版本卡住**：检查 `metadb` 中迁移记录表（如 `flyway_schema_history` 或 ODC 自管表）的 `success` 标记与失败日志。
- **方言不匹配**：H2 环境务必同时执行 `common` 与 `h2` 目录；生产 OB 环境执行 `common` 与 `oceanbase`。
- **内置数据缺失**：确认对应 YAML 的 `${ORGANIZATION_ID}` / `${CREATOR_ID}` 在执行上下文中已注入。
- **审计/规则未生效**：核对 `audit_event_meta.enabled`、`regulation_rule_applying.enabled`、环境与 ruleset 的绑定关系。

### 10.3 新增迁移注意事项

1. 严格遵循命名规范，版本号必须大于当前最新（4.3.4.x）。
2. DDL 必须幂等，DML 使用 `ON DUPLICATE KEY UPDATE`。
3. 涉及内置数据时优先用 YAML（享受 `field_ref` 跨文件引用），避免硬编码 ID。
4. 字段变更同步在 `h2` / `oceanbase` 提供对应补丁。
5. 不直接删除旧表/旧字段（如 `odc_*`、`visible_scope`），用 ALTER 弱化或保留以兼容历史数据迁移。

---

## 附录：文件清单统计

| 目录 | 文件数 | 主要类型 |
|------|--------|----------|
| `migrate/common` | 138 | SQL（建表/改表）+ YAML（IAM 数据） |
| `migrate/h2` | 12 | SQL（H2 兼容补丁） |
| `migrate/oceanbase` | 16 | SQL（Flowable + Spring Session + OB 适配） |
| `migrate/rbac` | 4 | YAML（角色权限） |
| `migrate/vectordb` | 1 | 占位 SQL |
| `migrate/web` | 8 | SQL + YAML（Web 版初始化） |
| `runtime` | 13 | YAML（运行态按组织装配） |
| `init-config` | 6 | YAML + SQL（静态元数据 + 迁移模板） |
| **合计** | **198** | — |

> 版本覆盖：2.0.0 → 4.3.4（最新 `V_4_3_4_10__alter_iam_organization_add_column.sql`）。

---

# 附录 B：FlowInstanceService#listAll 查询 SQL 解析

> 分析对象：`server/odc-service/src/main/java/com/oceanbase/odc/service/flow/FlowInstanceService.java#listAll`
> 目的：根据方法实现 + 库表/视图定义，还原该方法实际下发到 MetaDB 的 SQL 语句。

## B.1 方法逻辑概览

`listAll(Pageable, QueryFlowInstanceParams)` 是流程实例列表查询的核心方法，`list()` 对其结果做映射后返回。该方法存在 **两条分支**：

1. **父实例分支**（`params.getParentInstanceId() != null`）：查询某父流程下的子流程，直接走 `flowInstanceRepository`（查 `flow_instance` 表）。
2. **通用列表分支**（默认）：基于 `list_flow_instance_view` 视图 + 动态 `Specification` 拼接条件，走 `flowInstanceViewRepository.findAllWithoutFlowConfigSnapshot`。

本附录聚焦第二条分支（通用列表查询），它是工单列表页的主查询。

## B.2 涉及的库表与视图

### B.2.1 `list_flow_instance_view`（基础视图）

定义见 `migrate/common/V_4_2_3_18__create_list_flow_instance_view.sql`：

```sql
CREATE OR REPLACE VIEW `list_flow_instance_view` AS
SELECT /*+use_merge(flow_instance flow_instance_node_task)*/
    `flow_instance`.`id` AS `id`,
    `flow_instance`.`create_time` AS `create_time`,
    `flow_instance`.`update_time` AS `update_time`,
    `flow_instance`.`name` AS `name`,
    `flow_instance`.`flow_config_id` AS `flow_config_id`,
    `flow_instance`.`creator_id` AS `creator_id`,
    `flow_instance`.`organization_id` AS `organization_id`,
    `flow_instance`.`process_definition_id` AS `process_definition_id`,
    `flow_instance`.`process_instance_id` AS `process_instance_id`,
    `flow_instance`.`status` AS `status`,
    `flow_instance`.`flow_config_snapshot_xml` AS `flow_config_snapshot_xml`,
    `flow_instance`.`description` AS `description`,
    `flow_instance`.`parent_instance_id` AS `parent_instance_id`,
    `flow_instance`.`project_id` AS `project_id`,
    `flow_instance_node_task`.`task_type` AS `task_type`
FROM `flow_instance`
JOIN `flow_instance_node_task`
    ON `flow_instance`.`id` = `flow_instance_node_task`.`flow_instance_id`;
```

- **基表**：`flow_instance`（流程实例主表，见 5.5 节）。
- **关联表**：`flow_instance_node_task`（任务节点实例表，提供 `task_type`）。
- `project_id` 字段由 `V_4_2_0_16__alter_flow_instance.sql` 加入 `flow_instance`。
- 优化器提示 `use_merge` 强制走 merge join。

### B.2.2 `flow_instance_approval_view`（审批视图，用于"待我审批"过滤）

最终定义见 `migrate/common/V_4_2_3_3__replace_list_flow_instance_view.sql`：

```sql
CREATE OR REPLACE VIEW `flow_instance_approval_view` AS
SELECT
    fai.id,
    fai.flow_instance_id,
    faci.resource_role_identifier,
    fai.status AS approval_status
FROM `flow_instance_node_approval` fai
INNER JOIN `flow_instance_node_approval_candidate` faci
    ON fai.id = faci.approval_instance_id;
```

- **基表**：`flow_instance_node_approval`（审批节点实例）+ `flow_instance_node_approval_candidate`（审批候选人）。
- 该视图在 `V_4_2_3_3` 后**不再内置 status 过滤**，状态过滤（`EXECUTING`、`WAIT_FOR_CONFIRM`）改由查询时的 WHERE 子句动态注入。

## B.3 查询参数（QueryFlowInstanceParams）

| 字段 | 类型 | 对应 Spec | 说明 |
|------|------|-----------|------|
| `parentInstanceId` | Long | （走父实例分支，不进入视图查询） | 父流程实例 ID |
| `creator` | String | `creatorIdIn`（先模糊查用户得 id 列表） | 创建者名称（模糊匹配） |
| `id` | String | `idEquals`（数值化） | 流程实例 ID |
| `statuses` | List\<FlowStatus\> | `statusIn` | 流程状态集合 |
| `startTime` | Date | `createTimeLate` | 创建时间下界（>=） |
| `endTime` | Date | `createTimeBefore` | 创建时间上界（<） |
| `type` | TaskType | `taskTypeEquals` / 否则 `taskTypeIn` | 任务类型 |
| `projectIds` | Set\<Long\> | `projectIdIn` | 项目 ID 集合 |
| `containsAll` | Boolean | （分流） | 是否查全部 |
| `createdByCurrentUser` | Boolean | `creatorIdEquals` | 仅看本人创建 |
| `approveByCurrentUser` | Boolean | `leftJoinFlowInstanceApprovalView` | 仅看待我审批 |

## B.4 还原后的 SQL 语句

> 说明：JPA Criteria 最终下发为参数化 SQL，下方用 `${...}` 表示运行时绑定值。完整查询包含 **三条 SQL**：列表查询、count 查询，以及（仅当 `creator` 非空时）一次用户模糊查询。

### B.4.0 前置查询（仅当 `creator` 非空时）

由 `userService.getUsersByFuzzyNameWithoutPermissionCheck(creator)` 触发，在 `iam_user` 上做模糊匹配以拿到 `creatorIds`：

```sql
SELECT id, name, account_name, ...
FROM `iam_user`
WHERE `name` LIKE '%${creator}%'              -- 或 account_name 视实现而定
   OR `account_name` LIKE '%${creator}%';
-- 取出 id 集合 → creatorIds
```

### B.4.1 主查询（通用列表分支，`containsAll=false` 且 `approveByCurrentUser=false` 的最简形态）

对应 `flowInstanceViewRepository.findAllWithoutFlowConfigSnapshot`。注意 SELECT 列表**显式排除了大字段 `flow_config_snapshot_xml`**，且固定 `GROUP BY id, task_type`。

```sql
SELECT
    view0_.id                      AS id,
    view0_.parent_instance_id      AS parentInstanceId,
    view0_.project_id              AS projectId,
    view0_.name                    AS name,
    view0_.flow_config_id          AS flowConfigId,
    view0_.creator_id              AS creatorId,
    view0_.organization_id         AS organizationId,
    view0_.process_definition_id   AS processDefinitionId,
    view0_.process_instance_id     AS processInstanceId,
    view0_.status                  AS status,
    view0_.description             AS description,
    view0_.create_time             AS createTime,
    view0_.update_time             AS updateTime
FROM `list_flow_instance_view` view0_
WHERE
    view0_.creator_id IN (:creatorIds)                       -- creatorIdIn        （creator 为空时此条件不出现）
AND view0_.organization_id = :currentOrganizationId          -- organizationIdEquals
AND view0_.status IN (:statuses)                             -- statusIn           （statuses 为空时不出现）
AND view0_.create_time >= :startTime                         -- createTimeLate     （startTime 为空时不出现）
AND view0_.create_time <  :endTime                           -- createTimeBefore   （endTime 为空时不出现）
AND view0_.id = :targetId                                    -- idEquals           （id 非数字时不出现）
AND view0_.task_type IN (                                     -- taskTypeIn         （type 非空时退化为 task_type = :type）
        'MULTIPLE_ASYNC','EXPORT','IMPORT','MOCKDATA','ASYNC',
        'SHADOWTABLE_SYNC','PARTITION_PLAN','ONLINE_SCHEMA_CHANGE',
        'EXPORT_RESULT_SET','APPLY_PROJECT_PERMISSION',
        'APPLY_DATABASE_PERMISSION','STRUCTURE_COMPARISON','APPLY_TABLE_PERMISSION')
AND (                                                         -- projectIdIn（projectIds 非空时）
        view0_.project_id IN (:projectIds)
    )
GROUP BY view0_.id, view0_.task_type                          -- groupByIdAndTaskType（固定）
ORDER BY view0_.create_time DESC                              -- 按 Pageable.sort，列表页默认按创建时间倒序
LIMIT :pageSize OFFSET :offset;                               -- 分页
```

> 当 `projectIds` 为空时，`projectIdIn` 分支被替换为：
> ```sql
> AND (
>     view0_.project_id IN (:currentUserJoinedProjectIds)     -- 用户已加入的项目
>  OR (view0_.creator_id = :currentUserId                     -- 或 APPLY_PROJECT_PERMISSION 类工单
>      AND view0_.task_type = 'APPLY_PROJECT_PERMISSION')
> )
> ```

### B.4.2 含"待我审批"过滤的主查询（`approveByCurrentUser=true`）

`leftJoinFlowInstanceApprovalView` 通过 `@OneToMany` 关联对 `flow_instance_approval_view` 做 LEFT JOIN，并以审批候选人资源角色标识 + 审批节点状态做过滤。`FlowNodeStatus.getExecutingStatuses()` = `{EXECUTING, WAIT_FOR_CONFIRM}`。

```sql
SELECT
    view0_.id, view0_.parent_instance_id, view0_.project_id, view0_.name,
    view0_.flow_config_id, view0_.creator_id, view0_.organization_id,
    view0_.process_definition_id, view0_.process_instance_id, view0_.status,
    view0_.description, view0_.create_time, view0_.update_time
FROM `list_flow_instance_view` view0_
LEFT JOIN `flow_instance_approval_view` approvals1_          -- 关联审批视图
    ON view0_.id = approvals1_.flow_instance_id
WHERE
    view0_.organization_id = :currentOrganizationId
AND view0_.status IN (:statuses)
AND view0_.create_time >= :startTime
AND view0_.create_time <  :endTime
AND view0_.task_type IN ( /* 同 B.4.1 */ )
AND view0_.project_id IN (:projectIds)
AND ( approvals1_.resource_role_identifier IN (:currentUserResourceRoleIdentifiers)  -- 当前用户的资源角色标识
  AND approvals1_.approval_status IN ('EXECUTING','WAIT_FOR_CONFIRM') )              -- 待审批状态
GROUP BY view0_.id, view0_.task_type
ORDER BY view0_.create_time DESC
LIMIT :pageSize OFFSET :offset;
```

> 注意 `leftJoinFlowInstanceApprovalView` 的语义实际是：LEFT JOIN 后再以 `approval.resource_role_identifier IN (...) AND approval.status IN (...)` 做过滤，效果等价于 INNER JOIN（不满足条件的行被过滤）。如需同时保留"我创建的"，方法中会以 `creatorId` 参数构造 `OR` 分支（本调用传 `null`，故不展开）。

### B.4.3 count 查询（分页总数）

`findAllWithoutFlowConfigSnapshot` 在主查询后立即执行 count。实现中**清空 groupBy 与 orderBy**，并用 `countDistinct(id)` 避免因 `task_type` 维度导致重复计数：

```sql
SELECT COUNT(DISTINCT view0_.id)
FROM `list_flow_instance_view` view0_
[LEFT JOIN `flow_instance_approval_view` approvals1_
    ON view0_.id = approvals1_.flow_instance_id]            -- 仅 approveByCurrentUser=true 时出现
WHERE
    /* 与主查询完全一致的 WHERE 条件，但不含 GROUP BY / ORDER BY */
    ...
;
```

### B.4.4 父实例分支（`parentInstanceId != null` 时）

不走视图，直接查 `flow_instance` 表。先两次子查询收敛 `flowInstanceIds`，再 `findAll(specification, pageable)`：

```sql
-- 步骤1：取父流程下的所有子流程 ID
SELECT id FROM `flow_instance`
WHERE parent_instance_id = :parentInstanceId;

-- 步骤2（仅当 params.type 非空）：按任务类型过滤
SELECT flow_instance_id, task_type
FROM `flow_instance_node_task`
WHERE flow_instance_id IN (:flowInstanceIds);
-- 应用侧按 task_type == params.type 收敛 flowInstanceIds

-- 步骤3：分页查询
SELECT flow_instance.*                                    -- 含 flow_config_snapshot_xml（未排除）
FROM `flow_instance`
WHERE id IN (:flowInstanceIds)
AND organization_id = :currentOrganizationId
[AND project_id IN (:projectIds)]                         -- projectIds 非空时
ORDER BY create_time DESC
LIMIT :pageSize OFFSET :offset;
```

## B.5 条件注入矩阵

下表归纳各 Spec 在不同参数组合下的出现情况（"✓"=出现，"—"=不出现，"动态"=依参数有无）：

| Spec → | creatorIdIn | organizationIdEquals | statusIn | createTimeLate | createTimeBefore | idEquals | taskTypeEquals/In | projectIdIn | groupByIdAndTaskType | creatorIdEquals | leftJoinApprovalView |
|--------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| creator 非空 | 动态 | ✓ | 动态 | 动态 | 动态 | 动态 | ✓ | 动态 | ✓ | — | — |
| type 非空 | — | ✓ | 动态 | 动态 | 动态 | 动态 | task_type= | 动态 | ✓ | — | — |
| createdByCurrentUser | — | ✓ | 动态 | 动态 | 动态 | 动态 | ✓ | 动态 | ✓ | ✓ | — |
| approveByCurrentUser | — | ✓ | 动态 | 动态 | 动态 | 动态 | ✓ | 动态 | ✓ | — | ✓(LEFT JOIN) |

## B.6 关键实现细节与注意事项

1. **排除大字段**：`findAllWithoutFlowConfigSnapshot`（`FlowInstanceViewRepositoryImpl`）显式 `multiselect` 了 13 个列，**不选 `flow_config_snapshot_xml`**，避免列表查询拉取大对象（视图本身含该列，但投影时剔除）。这是 2026/3/10 新增的优化。

2. **强制 GROUP BY**：`groupByIdAndTaskType()` 固定 `GROUP BY id, task_type`。因为一个流程实例的 `task_type` 在 `flow_instance_node_task` 中可能有多行（视图是 INNER JOIN），需去重。

3. **countDistinct**：count 查询用 `COUNT(DISTINCT id)` 而非 `COUNT(*)`，与 GROUP BY 的去重语义对齐，避免分页总数被 JOIN 放大。count 时显式清空 `groupBy`/`orderBy`。

4. **任务类型枚举**：`type` 为空时，`taskTypeIn` 用一组固定枚举（13 种）过滤，确保只列出业务工单类型；这是"独立过滤"的设计，避免列出无 task_type 的脏数据。

5. **项目权限收敛**：`projectIds` 为空时，以"用户已加入项目 OR (本人创建 AND APPLY_PROJECT_PERMISSION)"做权限收敛；若用户在 TEAM 组织下未加入任何项目且任务类型不含 `APPLY_PROJECT_PERMISSION`，直接返回空页。

6. **审批视图语义**：`approveByCurrentUser` 走 LEFT JOIN + WHERE 过滤；资源角色标识来自 `flow_instance_node_approval_candidate.resource_role_identifier`（格式 `resource_id:resource_role_id`，由 `V_4_2_0_32` 加入）。

7. **索引支撑**：`flow_instance` 上的 `(organization_id, create_time, id)`、`flow_instance_node_task.flow_instance_id`（`V_4_2_3_15`/`V_4_3_0_7`）等索引为本查询提供性能保障。

## B.7 涉及源码文件索引

| 文件 | 作用 |
|------|------|
| `service/flow/FlowInstanceService.java#listAll` (L607-721) | 查询编排、Specification 拼接 |
| `service/flow/FlowInstanceService.java#list` (L579-586) | 对外入口，调用 listAll 后映射 |
| `metadb/flow/FlowInstanceViewSpecs.java` | 各 WHERE 条件构造器（Specification） |
| `metadb/flow/FlowInstanceViewEntity.java` | `list_flow_instance_view` 视图实体映射（`@OneToMany approvals`） |
| `metadb/flow/FlowInstanceApprovalViewEntity.java` | `flow_instance_approval_view` 视图实体映射 |
| `metadb/flow/FlowInstanceViewRepository.java` | `findAllWithoutFlowConfigSnapshot` 接口声明 |
| `metadb/flow/FlowInstanceViewRepositoryImpl.java` | 自定义投影 + countDistinct 实现（排除大字段） |
| `migrate/common/V_4_2_3_18__create_list_flow_instance_view.sql` | `list_flow_instance_view` 最新定义 |
| `migrate/common/V_4_2_3_3__replace_list_flow_instance_view.sql` | `flow_instance_approval_view` 最新定义 |
| `migrate/common/V_4_2_0_16__alter_flow_instance.sql` | `flow_instance.project_id` 字段 |
| `migrate/common/V_4_2_0_32__alter_flow_instance_node_approval_candidate.sql` | `resource_role_identifier` 字段 |
| `service/flow/model/FlowNodeStatus.java` | `getExecutingStatuses()` = {EXECUTING, WAIT_FOR_CONFIRM} |

