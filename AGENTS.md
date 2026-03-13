# ODC Agent Guide

This document provides essential information for working with the OceanBase Developer Center (ODC) codebase.

## Project Overview

ODC is an enterprise-grade database development and management platform with both web and desktop versions. It provides:

- SQL development environment with PL debugging support
- Visual object management and schema comparison
- Risk control and approval workflows
- Data lifecycle management (archiving, cleanup)
- Sensitive data protection and masking
- Multi-database support (OceanBase MySQL/Oracle, MySQL, Oracle, PostgreSQL, Doris)

**Architecture**: Multi-module Maven project (backend) + React/TypeScript client (frontend)
**Language**: Java 8 (backend), TypeScript (frontend)
**Build System**: Maven (backend), npm/pnpm (frontend)
**License**: Apache 2.0

## Repository Structure

```
odc/
├── client/                  # Electron + React frontend (desktop & web)
├── server/                  # Java backend modules
│   ├── odc-server/          # Main server application entry point
│   ├── odc-service/         # Business logic layer
│   ├── odc-core/            # Core abstractions and utilities
│   ├── odc-common/           # Shared utilities
│   ├── odc-migrate/          # Database migrations
│   ├── odc-test/            # Testing infrastructure
│   ├── starters/             # Runtime environment starters (web-starter, desktop-starter)
│   ├── modules/              # Pluggable business features
│   ├── plugins/              # Data source & task plugins
│   └── integration-test/      # Integration tests
├── libs/                   # Self-developed components
│   ├── db-browser/           # Database schema access
│   └── ob-sql-parser/         # SQL parsing (ANTLR-based)
├── script/                  # Build and deployment scripts
└── docs/                    # Documentation
```

## Build and Test Commands

### Backend (Java/Maven)

**Prerequisites:**
- JDK 1.8
- Maven (use `mvnw` wrapper script - Linux/Unix, `mvnw.cmd` - Windows)

**First-time setup:**
```bash
# Install self-developed dependencies
script/build_libs.sh

# Initialize Node.js environment (for frontend build)
script/init_node_env.sh

# Update git submodules (if needed)
script/update_submodule.sh
```

**Build backend:**
```bash
# Build jar package
script/build_jar.sh

# Build RPM package
script/build_rpm.sh

# Build both libs + backend
script/build_jar.sh

# Or using Maven directly (from project root)
./mvnw clean install -DskipTests
```

**Build frontend resources (for integrated deployment):**
```bash
script/build_sqlconsole.sh
```

**Run backend server:**
```bash
# Set required environment variables
export ODC_DATABASE_HOST="your_metadb_host"
export ODC_DATABASE_PORT="your_metadb_port"
export ODC_DATABASE_NAME="odc_metadb"
export ODC_DATABASE_USERNAME="odc@test"
export ODC_DATABASE_PASSWORD="your_password"
export ODC_SERVER_PORT=8989
export ODC_PROFILE_MODE="alipay"

# Start server
script/nohup-start-odc.sh

# Or use static resource server for frontend (dev mode)
export ODC_INDEX_PAGE_URI=http://static-resource-server/dev-4.3.4/index.html
script/nohup-start-odc.sh

# Stop server
script/kill-odc.sh
```

**IDE Startup (IntelliJ IDEA):**
- Main class: `com.oceanbase.odc.server.OdcServer`
- VM Options: Increase build process heap to 2000MB
- Program arguments (using `--` syntax, not `-D`):
  ```
  --ODC_DATABASE_HOST=your_metadb_host
  --ODC_DATABASE_PORT=2881
  --ODC_DATABASE_NAME=odc_metadb
  --ODC_DATABASE_USERNAME=odc@test
  --ODC_DATABASE_PASSWORD=your_password
  --server.port=8989
  ```

### Frontend (React/TypeScript)

**Prerequisites:**
- Node.js 16
- pnpm 8

**Setup:**
```bash
cd client
npm install pnpm@8 -g
pnpm install
```

**Development:**
```bash
cd client
# Start development server
npm run dev

# Or with main process (Electron)
npm run dev:client
```

**Build:**
```bash
cd client
# Build for production
npm run build:odc

# Build for client mode
npm run build:client

# Build without documentation
npm run buildNoDoc

# Type check
npm run type-check
```

**Test:**
```bash
cd client
# Run tests
npm run test

# Run with coverage
npm run cov
```

**Format:**
```bash
cd client
# Format code
npm run prettier
```

## Testing

### Unit Tests

**Backend tests:**
```bash
# Run all unit tests (from project root)
./mvnw test

# Skip tests during build
./mvnw install -DskipTests

# Run specific test class
./mvnw test -Dtest=ClassName

# Run with specific profile
./mvnw test -Dspring.profiles.active=clientMode,test,jdbc
```

**Test Configuration:**
- Database credentials for integration tests are encrypted in `local-unit-test.properties` (git-ignored)
- Keys for encrypted values can be provided via environment variables or `.env` file
- Encrypted values use format: `ENC@{encryptedValue}`
- Pattern matching: any key matching `password|username|host|port|commandline` is auto-encrypted

**Integration tests:**
```bash
# Run integration tests
cd server/integration-test
../../mvnw verify
```

### Client Tests

```bash
cd client
# Jest configuration in jest.config.js
npm test
```

## Code Style and Formatting

### Java

**Code Formatter:** Eclipse Code Formatter
- Configuration: `builds/code-style/eclipse-java-oceanbase-style.xml`
- Import order: `builds/code-style/eclipse-java-oceanbase.importorder`

**Import Order:**
```
static imports
<blank line>
java.*              (with subpackages)
<blank line>
javax.*             (with subpackages)
<blank line>
org.*               (with subpackages)
<blank line>
com.*               (with subpackages)
<blank line>
com.alipay.*        (with subpackages)
<blank line>
all other imports
<blank line>
```

**IDE Setup (IntelliJ IDEA):**
1. Install "Adapter for Eclipse Code Formatter" plugin
2. Configure: Settings → Other Settings → Eclipse Code Formatter
   - Enable "Use the Eclipse code formatter"
   - Import `eclipse-java-oceanbase-style.xml`
   - Set profile to "oceanbase-java-format"
   - Configure import order from `eclipse-java-oceanbase.importorder`
3. Import IDEA code style: Settings → Code Style → Java → Import `IDEA_code_style_oceanbase.xml`

**Line Endings:** Unix-style LF (`\n`)
**EditorConfig:** See `.editorconfig` in project root

**Maven Plugins:**
- `formatter-maven-plugin`: Auto-format code during build (goal: `format` or `validate`)
- `impsort-maven-plugin`: Sort imports (goal: `sort` or `check`)
- `license-maven-plugin`: Add license headers (goal: `format` or `check`)
- `maven-pmd-plugin`: Code quality checks with `builds/odc-pmd-rules.xml`

**Profiles:**
- `local` (default): Formats code during build
- `ci`: Validates code formatting without modifying

### TypeScript/JavaScript

**Formatting:** Prettier
```bash
npm run prettier
```

**Pre-commit:** Runs type-check and lint-staged
```bash
npm run precommit
```

## Code Conventions and Patterns

### Java

**Package Structure:**
- `com.oceanbase.odc.<module>.<subpackage>`
- Modules: `server`, `service`, `core`, `common`, `migrate`, `test`

**Naming Conventions:**
- Classes: PascalCase (`WorkspaceService`)
- Methods: camelCase (`createWorkspace`)
- Constants: UPPER_SNAKE_CASE (`MAX_RETRY_COUNT`)
- Private fields: camelCase (`connectionSession`)
- DTOs/Request/Response: Descriptive suffix (`CreateWorkspaceReq`, `UpdateWorkspaceReq`)

**Annotations:**
- Lombok: `@Data`, `@Builder`, `@AllArgsConstructor`, `@NoArgsConstructor`, `@EqualsAndHashCode`, `@Slf4j`
- Validation: `@NotNull`, `@NotBlank`, `@Size` (javax.validation)
- Jackson: `@JsonProperty`, `@JsonIgnore`
- Spring: `@Service`, `@Component`, `@Repository`, `@RestController`, `@RequestMapping`
- Custom: `@SensitiveInput`, `@SingleOrganizationResource`, `@MultiOrganizationResource`

**Common Patterns:**
- Event-driven architecture with `EventPublisher`
- Task pattern: Implement `Task<RESULT>` interface, extend `BaseDelegateTask`
- Plugin system: PF4J for dynamic loading
- State management: Stateful routes with trace IDs
- Migration: Flyway-style with versioned SQL files
- Security: Organization-level resource isolation, permission checks

**Exception Handling:**
- Custom exceptions extend domain-specific base classes
- Use `@Slf4j` for logging
- Wrap business logic exceptions with context

### TypeScript

**Component Structure:**
```typescript
// Page components in src/page/
// Shared components in src/component/
// Hooks in src/hooks/
// Network requests in src/network/
// Stores in src/store/
// Utils in src/util/
```

**Naming:**
- Components: PascalCase (`WorkspacePage`)
- Files: kebab-case for folders (`data-source`)
- Hooks: `use` prefix (`useProjects`, `useUrlParams`)
- Constants: UPPER_SNAKE_CASE

**Common Libraries:**
- UI: Ant Design (`antd`)
- State: MobX
- Forms: React Hook Form
- Editor: Monaco Editor
- Data Grid: `@oceanbase-odc/ob-react-data-grid`
- Routing: UmiMax (React Router)
- SQL Parser: `@oceanbase-odc/ob-parser-js`
- Intl: react-intl

## Important Gotchas

### Module System

**Plugins vs Starters vs Modules:**
- **Plugins**: Dialect-specific implementations (e.g., `schema-plugin-ob-mysql`, `connect-plugin-ob-oracle`). Not loaded in Spring context.
- **Starters**: Runtime environment assembly (e.g., `web-starter`, `desktop-starter`). Loaded by Spring profile.
- **Modules**: Complete, pluggable business features. Loaded in Spring context.

**Plugin Loading:**
- Plugins are built to `distribution/plugins/`, `distribution/starters/`, `distribution/modules/` directories
- Use PF4J for dynamic loading
- Plugin API defined in `server/plugins/`

### Database Connections

**Multiple Dialect Support:**
- OceanBase MySQL mode (OBMySQL)
- OceanBase Oracle mode (OBOracle)
- MySQL, Oracle, PostgreSQL, Doris
- Each dialect has specific schema accessors in `libs/db-browser`

**Data Source Configuration:**
- Connection pooling with Druid
- Support for SSL/TLS connections
- Logical database support
- Cloud provider integration (Alibaba Cloud, public clouds)

### Session Management

**Connection Sessions:**
- `ConnectionSession` represents a user's database connection session
- Session validation and timeout handling
- Session isolation per organization

### Security

**Multi-Tenancy:**
- Organization-level isolation using `@SingleOrganizationResource` / `@MultiOrganizationResource`
- IAM integration for authorization
- Fine-grained permissions on database objects

**Sensitive Data:**
- Passwords encrypted with Jasypt (AES)
- Use `@SensitiveInput` annotation for sensitive fields
- Support for masking algorithms on result sets

### Frontend-Backend Integration

**Static Resource Server Mode (Development):**
- Backend can load frontend from external URL using `ODC_INDEX_PAGE_URI`
- Enables decoupled development
- Format: `http://static-resource-server/{branchName}/index.html`

**Embedded Mode (Production):**
- Frontend built and embedded in server JAR
- Build process copies `client/dist/renderer/*` to `server/odc-server/src/main/resources/static/`

### SQL Parsing

**ANTLR-based Parser:**
- Separate parsers for different dialects
- Located in `libs/ob-sql-parser`
- Used for syntax highlighting, validation, and splitting

### Task Execution

**Task Pattern:**
- Asynchronous task execution framework
- Progress tracking (0.0 to 1.0)
- Job context management
- Event publishing for task lifecycle

**Flowable Integration:**
- Workflow engine for approval processes
- BPMN 2.0 definitions
- Custom service tasks and gateways

### Remote Debugging

**Backend:**
```bash
# Set debug port
export ODC_REMOTE_DEBUG_PORT=8000
script/start-odc.sh

# In IDEA, create Remote JVM Debug configuration:
# Host: remote server
# Port: 8000
```

**Hot Deployment (ArthasHotSwap):**
- Use plugin to hot-swap classes without restart
- Right-click class → "Swap this class" → paste command on server
- Reduces deployment time from 10-20min to <1min

### Internationalization

**Backend:**
- Message files in `src/main/resources/i18n/`
- Formats: `BusinessMessages.properties`, `ErrorMessages.properties`
- Locales: `en_US`, `zh_CN`, `zh_TW`

**Frontend:**
- Use `react-intl`
- Locale files in `src/locales/`
- Supports en-US, zh-CN, zh-TW

### Migration

**Database Migrations:**
- Flyway-style versioning: `V{version}__description.sql`
- Separate migrations for different database types
- Located in `server/odc-migrate/src/main/resources/`
- Resource migrations using YAML for vector DB configs

### Code Quality

**PMD Rules:**
- Configuration: `builds/odc-pmd-rules.xml`
- Runs during `mvn pmd:check`
- Priority 1 violations fail build

**Lombok:**
- Use annotation processors for getters/setters
- Configured in maven-compiler-plugin

## Environment Variables

**Required for Server Startup:**
- `ODC_DATABASE_HOST`: MetaDB host
- `ODC_DATABASE_PORT`: MetaDB port
- `ODC_DATABASE_NAME`: Database name
- `ODC_DATABASE_USERNAME`: Database user
- `ODC_DATABASE_PASSWORD`: Database password
- `ODC_SERVER_PORT`: Server port (default: 8989)
- `ODC_PROFILE_MODE`: Profile mode (`alipay`, etc.)
- `ODC_INDEX_PAGE_URI`: (Optional) External frontend URL for dev
- `ODC_REMOTE_DEBUG_PORT`: (Optional) Port for remote debugging

**Optional (Library versions):**
- `OCEANBASE_CLIENT_VERSION`: OceanBase JDBC client version
- `OB_LOADER_DUMPER_VERSION`: OB Loader/Dumper version

## CI/CD

**GitHub Actions:**
- `.github/workflows/` contains workflow definitions
- `build_artifact.yaml`: Build JAR, RPM, Docker images
- Multi-arch builds: x86_64 and ARM64
- Cache: Maven dependencies, pnpm store

**Build Matrix:**
- Web artifacts: Ubuntu (x86_64, ARM64)
- Client artifacts: macOS (multi-target), Windows, Linux (x86, ARM64)

## Common Tasks

### Adding a New Feature

1. Create DTO/Request/Response classes with validation annotations
2. Implement service layer in `odc-service`
3. Add controller in `odc-server` with proper API versioning
4. Write unit tests with proper mocks
5. Update i18n message files
6. Run formatter and import sorter

### Modifying Database Schema

1. Create new migration file in `odc-migrate/resources/`
2. Follow naming: `V{major}_{minor}_{patch}__description.sql`
3. Test migration with test database
4. Rollback script in same directory if needed

### Adding a New Plugin

1. Create plugin module in `server/plugins/`
2. Implement required interface from `*-plugin-api`
3. Add assembly configuration for packaging
4. Register in plugin descriptor
5. Build and test in isolation

### Debugging Issues

1. Check logs in `log/odc.log`
2. Use TraceId from API response for request tracking
3. Enable remote debugging if needed
4. Use ArthasHotSwap for quick fixes in production-like env

## Performance Considerations

- Build process requires increased heap (2000MB in IDEA)
- Large database result sets need streaming/pagination
- Connection pooling configured for high concurrency
- Caching implemented for frequently accessed data
- Static resource compression for frontend assets

## Security Notes

- Never commit database credentials
- Use encrypted configuration for secrets
- Follow principle of least privilege for database accounts
- Validate all user inputs (SQL injection prevention with libinjection)
- Sensitive data should be masked when not in use
