# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

OceanBase Developer Center (ODC) 是一个企业级数据库开发和管理平台，提供 Web 版本和桌面版本。

**技术栈：**
- 后端：Java 8 + Spring Boot 2.7 + JPA + MyBatis
- 前端：TypeScript + React + Electron
- 构建工具：Maven (后端) + pnpm (前端)
- 数据库：支持 OceanBase (MySQL/Oracle 模式)、MySQL、Oracle、PostgreSQL、Doris

**架构特点：**
- 基于 PF4J 的插件系统，支持多数据库方言扩展
- 多租户组织隔离
- 工作流引擎 (Flowable) 用于审批流程
- 任务调度系统 (Quartz)

## 构建和测试命令

### 后端构建

```bash
# 首次构建前需要安装依赖
script/build_libs.sh

# 构建 JAR 包
script/build_jar.sh

# 构建 RPM 包
script/build_rpm.sh

# 使用 Maven 直接构建（跳过测试）
./mvnw clean install -DskipTests

# 格式化代码（local profile 默认启用）
./mvnw process-sources
```

### 运行后端服务器

```bash
# 设置必需的环境变量
export ODC_DATABASE_HOST="127.0.0.1"
export ODC_DATABASE_PORT="2881"
export ODC_DATABASE_NAME="odc_metadb"
export ODC_DATABASE_USERNAME="odc@test"
export ODC_DATABASE_PASSWORD="your_password"
export ODC_SERVER_PORT=8989

# 启动服务器
script/nohup-start-odc.sh

# 停止服务器
script/kill-odc.sh
```

### 运行测试

```bash
# 运行所有单元测试
./mvnw test

# 运行单个测试类
./mvnw test -Dtest=ClassName

# 运行集成测试
cd server/integration-test
../../mvnw verify

# 跳过测试构建
./mvnw install -DskipTests
```

### IDE 启动 (IntelliJ IDEA)

- **主类**: `com.oceanbase.odc.server.OdcServer`
- **VM 参数**: 增加构建进程堆到 2000MB
- **程序参数**（使用 `--` 语法）:
  ```
  --ODC_DATABASE_HOST=127.0.0.1
  --ODC_DATABASE_PORT=2881
  --ODC_DATABASE_NAME=odc_metadb
  --ODC_DATABASE_USERNAME=odc@test
  --ODC_DATABASE_PASSWORD=your_password
  --server.port=8989
  ```

## 高层架构

### 模块结构

```
server/
├── odc-server/          # 主服务器应用入口，REST API 控制器
├── odc-service/         # 业务逻辑层，Service 实现
├── odc-core/            # 核心抽象和工具，独立于 Spring 上下文
├── odc-common/          # 共享工具类
├── odc-migrate/         # 数据库迁移脚本
├── odc-test/            # 测试基础设施
├── starters/            # 运行时环境组装器
│   ├── web-starter/     # Web 模式配置
│   └── desktop-starter/ # 桌面模式配置
├── modules/             # 可插拔业务功能
└── plugins/             # 数据源方言插件
    ├── connect-plugin-*/    # 连接插件
    ├── schema-plugin-*/     # Schema 访问插件
    └── task-plugin-*/       # 任务插件
```

### 插件系统

ODC 使用 **PF4J** 实现动态插件加载：

- **connect-plugin**: 数据库连接和会话管理
- **schema-plugin**: 数据库 Schema 访问和元数据查询
- **task-plugin**: 数据导入导出等任务执行

插件打包到 `distribution/plugins/` 目录，运行时动态加载。插件不参与 Spring 上下文，使用独立的类加载器。

### 启动器 (Starters)

Starters 通过 Spring Profile 加载，组装运行时环境：

- `web-starter`: Web 模式，支持多组织、SSO、LDAP、OAuth2 等企业认证
- `desktop-starter`: 桌面模式，使用嵌入式 H2 数据库

### JPA 仓库模式

**重要**: JPA Repository 接口不使用 `@Repository` 注解。

项目使用自定义的 `EnhancedJpaRepository` 扩展标准 JPA 功能，支持批量操作和动态查询。

### 多租户隔离

使用 `@SingleOrganizationResource` 和 `@MultiOrganizationResource` 注解实现组织级别的资源隔离。

## 代码规范

### Java 代码格式

- **格式化配置**: `builds/code-style/eclipse-java-oceanbase-style.xml`
- **Import 顺序**: java.*, javax.*, org.*, com.*, com.alipay.*
- **行结束符**: Unix-style LF (`\n`)
- **Maven 插件**: formatter-maven-plugin, impsort-maven-plugin

### 命名约定

- 类: PascalCase (`WorkspaceService`)
- 方法: camelCase (`createWorkspace`)
- 常量: UPPER_SNAKE_CASE (`MAX_RETRY_COUNT`)
- DTO 请求/响应: 描述性后缀 (`CreateWorkspaceReq`, `UpdateWorkspaceReq`)

### 常用注解

- Lombok: `@Data`, `@Builder`, `@Slf4j`
- 验证: `@NotNull`, `@NotBlank`, `@Size`
- Spring: `@Service`, `@Component`, `@RestController`
- 自定义: `@SensitiveInput`, `@SingleOrganizationResource`

## 重要注意事项

### 数据库连接

ODC 支持多种数据库方言，每种方言有特定的插件实现。新增数据库支持需要实现三类插件接口。

### 敏感数据处理

- 密码使用 Jasypt (AES) 加密
- 使用 `@SensitiveInput` 注解标记敏感字段
- 结果集支持脱敏算法

### 会话管理

`ConnectionSession` 表示用户的数据库连接会话，支持会话验证和超时处理。

### 国际化

- 后端: `src/main/resources/i18n/BusinessMessages*.properties`
- 支持语言: en_US, zh_CN, zh_TW

### 迁移脚本

数据库迁移使用 Flyway 风格版本控制：`V{major}_{minor}_{patch}__description.sql`
位于 `server/odc-migrate/src/main/resources/`

## 环境变量

**服务器启动必需：**
- `ODC_DATABASE_HOST`: MetaDB 主机
- `ODC_DATABASE_PORT`: MetaDB 端口
- `ODC_DATABASE_NAME`: 数据库名
- `ODC_DATABASE_USERNAME`: 数据库用户
- `ODC_DATABASE_PASSWORD`: 数据库密码
- `ODC_SERVER_PORT`: 服务器端口 (默认: 8989)

**可选：**
- `ODC_REMOTE_DEBUG_PORT`: 远程调试端口
- `ODC_INDEX_PAGE_URI`: 前端静态资源地址（开发模式）
