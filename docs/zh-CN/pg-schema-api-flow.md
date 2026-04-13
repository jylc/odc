# PostgreSQL Schema API 流程分析

## 概述

本文档整理了 ODC 中 PostgreSQL 数据源在 Schema 相关 API 调用时的完整流程逻辑，包括表列表查询和表详情查询两个核心接口的实现状态分析。

## 一、表列表查询

### 接口

```
GET /api/v2/databaseSchema/tables?databaseId=&includePermittedAction=&type=
```

### Controller 入口

**文件**: `server/odc-server/.../v2/DBSchemaController.java:56`

- 接收 `databaseId`、`includePermittedAction`（默认 false）、`types`（默认 `[TABLE]`）
- 构造 `QueryTableParams` 传给 `TableService.list()`

### 调用链

```
DBSchemaController.list()
  └─ TableService.list()
       ├─ DatabaseService.detail(databaseId)
       │    └─ 获取 Database 对象，含 ConnectionConfig（DialectType.POSTGRESQL）
       ├─ OBConsoleDataSourceFactory
       │    └─ 创建 SingleConnectionDataSource，建立 PG JDBC 连接
       ├─ SchemaPluginUtil.getTableExtension(POSTGRESQL)
       │    └─ PF4J 插件管理器 → PostgresTableExtension
       │         └─ 继承 OBMySQLTableExtension
       │              └─ DBAccessorUtil.getSchemaAccessor()
       │                   └─ DBBrowser.schemaAccessor().setType("POSTGRESQL")
       │                        └─ PostgresSchemaAccessor
       │                             └─ showTables(schemaName)
       │                                  → SQL: SELECT table_name FROM information_schema.tables
       │                                     WHERE table_schema = '{schema}'
       │                                       AND table_type = 'BASE TABLE'
       │                                       AND table_name NOT IN (
       │                                         SELECT relname FROM pg_class c
       │                                         JOIN pg_inherits i ON c.oid = i.inhrelid
       │                                       )
       ├─ generateListAndSyncDBTablesByTableType()
       │    ├─ 个人用户 (INDIVIDUAL):
       │    │    └─ 直接将表名映射为 Table 对象，赋所有权限
       │    └─ 企业用户:
       │         ├─ 比较 MetaDB 与实际数据库的表名差异
       │         ├─ 若不一致 → DBTableSyncer.sync()（增量同步）
       │         │    ├─ 新增表 → batchCreate()
       │         │    └─ 删除表 → deleteByIds() + 清理权限/列记录
       │         └─ entitiesToModels() → 查权限，返回 List<Table>
       └─ 返回 ListResponse<Table>
```

### PG 实际执行的 SQL

`PostgresSchemaAccessor.showTables()` 执行以下查询：

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = '{schemaName}'
  AND table_type = 'BASE TABLE'
  AND table_name NOT IN (
    SELECT relname FROM pg_class c
    JOIN pg_inherits i ON c.oid = i.inhrelid
  );
```

关键点：排除了继承表（通过 `pg_inherits` 判断），只返回真正的基表。

### 实现状态

**此接口 PG 可正常工作。**

## 二、表详情查询

### 接口

```
GET /api/v2/connect/sessions/{sessionId}/databases/{databaseName}/tables/{tableName}?type=TABLE
```

其中 `tableName` 为 Base64 编码（如 `Y29tcGFueQ%3D%3D` → `company`）。

### Controller 入口

**文件**: `server/odc-server/.../v2/DBTableController.java:76-87`

```java
@GetMapping(value = {
    "/{sessionId}/databases/{databaseName}/tables/{tableName}",
    "/{sessionId}/currentDatabase/tables/{tableName}"
})
public SuccessResponse<DBTable> getTable(
    @PathVariable String sessionId,
    @PathVariable String databaseName,
    @PathVariable String tableName,
    @RequestParam(defaultValue = "TABLE") DBObjectType type)
```

- Base64 解码 `tableName`
- 获取 `ConnectionSession`
- 调用 `DBTableService.getTable(session, databaseName, tableName, type)`

### 调用链

```
DBTableController.getTable()
  ├─ sessionService.nullSafeGet(sessionId)      获取 PG 连接会话
  └─ DBTableService.getTable()
       │
       ├─【第一步：前置校验】
       │  DBSchemaAccessors.create(connectionSession)
       │    └─ DBBrowser.schemaAccessor().setType("POSTGRESQL")
       │         └─ PostgresSchemaAccessor
       │              └─ showTables("public")    → 检查表是否存在
       │                   → SQL: SELECT table_name FROM information_schema.tables ...
       │                   ✓ 此步骤 PG 正常
       │
       └─【第二步：获取表详情】
          PostgresTableExtension.getDetail()      继承自 OBMySQLTableExtension:91
          │
          ├─ schemaAccessor.getTableDDL()          ✗ UnsupportedOperationException
          ├─ schemaAccessor.listTableColumns()     ✗ UnsupportedOperationException
          ├─ schemaAccessor.listTableConstraints() ✗ UnsupportedOperationException
          ├─ schemaAccessor.listTableIndexes()     ✗ UnsupportedOperationException
          ├─ schemaAccessor.getTableOptions()      ✗ UnsupportedOperationException
          └─ schemaAccessor.listTableColumnGroups()✗ UnsupportedOperationException
```

### 关键代码分析

`DBTableService.getTable()` 执行两步操作：

**第一步** — 前置校验（第93-98行）：

```java
DBSchemaAccessor schemaAccessor = DBSchemaAccessors.create(connectionSession);
PreConditions.validExists(ResourceType.OB_TABLE, "tableName", tableName,
    () -> schemaAccessor.showTables(schemaName).stream()
        .filter(name -> name.equals(tableName))
        .collect(Collectors.toList()).size() > 0);
```

通过 `PostgresSchemaAccessor.showTables()` 查询，**此步正常**。

**第二步** — 获取表详情（第105-108行）：

```java
return connectionSession.getSyncJdbcExecutor(BACKEND_DS_KEY)
    .execute((ConnectionCallback<DBTable>) con ->
        getTableExtensionPoint(connectionSession).getDetail(con, schemaName, tableName));
```

调用 `PostgresTableExtension.getDetail()`，该方法继承自 `OBMySQLTableExtension.getDetail()`：

```java
public DBTable getDetail(Connection connection, String schemaName, String tableName) {
    DBSchemaAccessor schemaAccessor = getSchemaAccessor(connection);
    String ddl = schemaAccessor.getTableDDL(schemaName, tableName);        // ← 第一个报错点
    OBMySQLGetDBTableByParser parser = new OBMySQLGetDBTableByParser(ddl);

    DBTable table = new DBTable();
    table.setColumns(schemaAccessor.listTableColumns(schemaName, tableName));
    table.setConstraints(schemaAccessor.listTableConstraints(schemaName, tableName));
    table.setIndexes(schemaAccessor.listTableIndexes(schemaName, tableName));
    table.setPartition(parser.getPartition());
    table.setDDL(ddl);
    table.setTableOptions(schemaAccessor.getTableOptions(schemaName, tableName));
    table.setStats(getTableStats(connection, schemaName, tableName));
    table.setColumnGroups(schemaAccessor.listTableColumnGroups(schemaName, tableName));
    return table;
}
```

第一个调用 `getTableDDL()` 即抛出 `UnsupportedOperationException("Not supported yet")`，被外层 catch 块捕获，包装为 `UnexpectedException` 返回前端。

### 实现状态

**此接口 PG 不可用，返回"不支持"错误。**

## 三、PostgresSchemaAccessor 方法实现矩阵

| 方法 | 状态 | 说明 |
|------|------|------|
| `showDatabases()` | ✓ 已实现 | 查询 `information_schema.schemata` |
| `listDatabases()` | ✓ 已实现 | 查询 `pg_database` 获取 collation/charset |
| `showTables(schemaName)` | ✓ 已实现 | 查询 `information_schema.tables`，排除继承表 |
| `showCharset()` | ✓ 已实现 | 查询 `pg_collation` |
| `showCollation()` | ✓ 已实现 | 查询 `pg_collation` |
| `getTableDDL()` | ✗ 未实现 | |
| `listTableColumns()` | ✗ 未实现 | |
| `listTableConstraints()` | ✗ 未实现 | |
| `listTableIndexes()` | ✗ 未实现 | |
| `getTableOptions()` | ✗ 未实现 | |
| `getPartition()` | ✗ 未实现 | |
| `listTableColumnGroups()` | ✗ 未实现 | |
| `listViews()` | ✗ 未实现 | |
| `listMViews()` | ✗ 未实现 | |
| `showTablesLike()` | ✗ 未实现 | |
| `listTableColumns(批量)` | ✗ 未实现 | |
| `listBasicTableColumns()` | ✗ 未实现 | |
| `isExternalTable()` | ✗ 返回 false | |
| `isLowerCaseTableName()` | ✗ 未实现 | |

## 四、插件层架构

```
PostgresSchemaPlugin (PF4J Plugin)
  └─ dialectType = POSTGRESQL
  └─ 注册扩展点:
       ├─ PostgresTableExtension  (@Extension, 继承 OBMySQLTableExtension)
       │    └─ 覆写 getSchemaAccessor() → PostgresSchemaAccessor
       └─ PostgresDatabaseExtension (@Extension)
```

`PostgresTableExtension` 继承 `OBMySQLTableExtension`，只覆写了 `getSchemaAccessor()`，未覆写 `getDetail()` 等方法。因此 PG 的表详情逻辑复用了 MySQL 的流程框架，但底层的 `PostgresSchemaAccessor` 大部分方法尚未实现，导致调用链断裂。

## 五、解决方向

要使 PG 表详情接口正常工作，需要在以下位置补充实现：

### 1. PostgresSchemaAccessor 补充实现

在 `libs/db-browser/src/main/java/com/oceanbase/tools/dbbrowser/schema/postgre/PostgresSchemaAccessor.java` 中实现以下方法：

- `getTableDDL(schemaName, tableName)` — 使用 `pg_get_tabledef()` 或构造 DDL
- `listTableColumns(schemaName, tableName)` — 查询 `information_schema.columns`
- `listTableConstraints(schemaName, tableName)` — 查询 `information_schema.table_constraints` + `key_column_usage`
- `listTableIndexes(schemaName, tableName)` — 查询 `pg_indexes` 系统视图
- `getTableOptions(schemaName, tableName)` — 查询 `pg_tables` 获取表空间等信息
- `getPartition(schemaName, tableName)` — 查询 `pg_partitioned_table`

### 2. PostgresTableExtension 覆写（可选）

如果 PG 的表详情获取逻辑与 MySQL 有显著差异，需在 `PostgresTableExtension` 中覆写 `getDetail()` 方法，编写 PG 特有的组装逻辑。

### 涉及文件

| 文件 | 路径 | 修改类型 |
|------|------|---------|
| PostgresSchemaAccessor | `libs/db-browser/.../postgre/PostgresSchemaAccessor.java` | 补充方法实现 |
| PostgresTableExtension | `server/plugins/schema-plugin-postgres/.../PostgresTableExtension.java` | 可能需要覆写 getDetail() |
