/*
 * Copyright (c) 2023 OceanBase.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.oceanbase.tools.dbbrowser.schema.postgre;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

import com.oceanbase.tools.dbbrowser.model.DBColumnGroupElement;
import com.oceanbase.tools.dbbrowser.model.DBConstraintType;
import com.oceanbase.tools.dbbrowser.model.DBDatabase;
import com.oceanbase.tools.dbbrowser.model.DBFunction;
import com.oceanbase.tools.dbbrowser.model.DBIndexAlgorithm;
import com.oceanbase.tools.dbbrowser.model.DBIndexType;
import com.oceanbase.tools.dbbrowser.model.DBObjectIdentity;
import com.oceanbase.tools.dbbrowser.model.DBPLObjectIdentity;
import com.oceanbase.tools.dbbrowser.model.DBPackage;
import com.oceanbase.tools.dbbrowser.model.DBProcedure;
import com.oceanbase.tools.dbbrowser.model.DBSequence;
import com.oceanbase.tools.dbbrowser.model.DBSynonym;
import com.oceanbase.tools.dbbrowser.model.DBSynonymType;
import com.oceanbase.tools.dbbrowser.model.DBTable;
import com.oceanbase.tools.dbbrowser.model.DBTable.DBTableOptions;
import com.oceanbase.tools.dbbrowser.model.DBTableColumn;
import com.oceanbase.tools.dbbrowser.model.DBTableConstraint;
import com.oceanbase.tools.dbbrowser.model.DBTableIndex;
import com.oceanbase.tools.dbbrowser.model.DBTablePartition;
import com.oceanbase.tools.dbbrowser.model.DBTableSubpartitionDefinition;
import com.oceanbase.tools.dbbrowser.model.DBTrigger;
import com.oceanbase.tools.dbbrowser.model.DBType;
import com.oceanbase.tools.dbbrowser.model.DBVariable;
import com.oceanbase.tools.dbbrowser.model.DBView;
import com.oceanbase.tools.dbbrowser.schema.DBSchemaAccessor;
import com.oceanbase.tools.dbbrowser.util.StringUtils;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PostgresSchemaAccessor implements DBSchemaAccessor {

    private static final RowMapper<DBObjectIdentity> IDENTITY_MAPPER =
            new BeanPropertyRowMapper<>(DBObjectIdentity.class);

    protected JdbcOperations jdbcOperations;

    public PostgresSchemaAccessor(@NonNull JdbcOperations jdbcOperations) {
        this.jdbcOperations = jdbcOperations;
    }

    @Override
    public List<String> showDatabases() {
        String sql = "SELECT schema_name FROM information_schema.schemata "
                + "where schema_name not like 'pg_%' "
                + "and schema_name <> 'information_schema'";
        return jdbcOperations.queryForList(sql, String.class);
    }

    @Override
    public DBDatabase getDatabase(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBDatabase> listDatabases() {
        List<String> schemas = showDatabases();
        String sql = "SELECT "
                + "    datcollate AS collation, "
                + "    pg_encoding_to_char(encoding) AS charset "
                + "FROM pg_database "
                + "WHERE datname = current_database();";
        AtomicReference<String> charset = new AtomicReference<>();
        AtomicReference<String> collation = new AtomicReference<>();
        jdbcOperations.query(sql, rs -> {
            collation.set(rs.getString(1));
            charset.set(rs.getString(2));
        });
        return schemas.stream().map(schema -> {
            DBDatabase database = new DBDatabase();
            database.setId(schema);
            database.setName(schema);
            database.setCollation(collation.get());
            database.setCharset(charset.get());
            return database;
        }).collect(Collectors.toList());
    }

    @Override
    public void switchDatabase(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBObjectIdentity> listUsers() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<String> showTables(String schemaName) {
        StringBuilder sb = new StringBuilder();
        sb.append("select table_name from information_schema.tables where table_schema = ");
        sb.append("'").append(schemaName).append("'");
        sb.append(" and table_type = 'BASE TABLE' ");
        sb.append(" and table_name not in (SELECT relname FROM pg_class c ");
        sb.append(" JOIN pg_inherits i ON c.oid = i.inhrelid);");
        List<String> tableNames;
        try {
            tableNames = jdbcOperations.query(sb.toString(), (rs, rowNum) -> rs.getString(1));
        } catch (BadSqlGrammarException e) {
            if (StringUtils.containsIgnoreCase(e.getMessage(), "Unknown schema")) {
                return Collections.emptyList();
            }
            throw e;
        }
        return tableNames;
    }

    @Override
    public List<String> showTablesLike(String schemaName, String tableNameLike) {
        StringBuilder sb = new StringBuilder();
        sb.append("select table_name from information_schema.tables where table_schema = ?");
        sb.append(" and table_type = 'BASE TABLE' ");
        sb.append(" and table_name not in (SELECT relname FROM pg_class c ");
        sb.append(" JOIN pg_inherits i ON c.oid = i.inhrelid)");
        if (StringUtils.isNotBlank(tableNameLike)) {
            sb.append(" and table_name like ?");
        }
        sb.append(" order by table_name");
        if (StringUtils.isNotBlank(tableNameLike)) {
            return jdbcOperations.queryForList(sb.toString(), String.class, schemaName, tableNameLike);
        }
        return jdbcOperations.queryForList(sb.toString(), String.class, schemaName);
    }

    @Override
    public List<DBObjectIdentity> listTables(String schemaName, String tableNameLike) {
        // GaussDB 兼容：纯 information_schema 查询；排除分区子表，与 showTables 语义一致
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT table_schema AS schema_name, 'TABLE' AS type, table_name AS name ");
        sb.append("FROM information_schema.tables ");
        sb.append("WHERE table_type = 'BASE TABLE' ");
        sb.append("AND table_name NOT IN (SELECT relname FROM pg_class c ");
        sb.append("JOIN pg_inherits i ON c.oid = i.inhrelid) ");
        sb.append("AND table_schema NOT LIKE 'pg%' ");
        sb.append("AND table_schema <> 'information_schema' ");
        if (StringUtils.isNotBlank(schemaName)) {
            sb.append("AND table_schema = ? ");
        }
        if (StringUtils.isNotBlank(tableNameLike)) {
            sb.append("AND table_name LIKE ? ");
        }
        sb.append("ORDER BY table_schema, table_name");
        return queryIdentities(sb.toString(), schemaName, tableNameLike);
    }

    @Override
    public List<DBObjectIdentity> listViews(String schemaName) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT table_schema AS schema_name, 'VIEW' AS type, table_name AS name ");
        sb.append("FROM information_schema.tables WHERE table_type = 'VIEW' ");
        if (StringUtils.isNotBlank(schemaName)) {
            sb.append("AND table_schema = ? ");
        } else {
            sb.append("AND table_schema NOT LIKE 'pg%' AND table_schema <> 'information_schema' ");
        }
        sb.append("ORDER BY table_schema, table_name");
        if (StringUtils.isNotBlank(schemaName)) {
            return jdbcOperations.query(sb.toString(), IDENTITY_MAPPER, schemaName);
        }
        return jdbcOperations.query(sb.toString(), IDENTITY_MAPPER);
    }

    @Override
    public List<DBObjectIdentity> listAllViews(String viewNameLike) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT table_schema AS schema_name, 'VIEW' AS type, table_name AS name ");
        sb.append("FROM information_schema.tables WHERE table_type = 'VIEW' ");
        if (StringUtils.isNotBlank(viewNameLike)) {
            sb.append("AND table_name LIKE ? ");
        }
        sb.append("ORDER BY table_schema, table_name");
        if (StringUtils.isNotBlank(viewNameLike)) {
            return jdbcOperations.query(sb.toString(), IDENTITY_MAPPER, viewNameLike);
        }
        return jdbcOperations.query(sb.toString(), IDENTITY_MAPPER);
    }

    @Override
    public List<DBObjectIdentity> listAllUserViews() {
        String sql = "SELECT table_schema AS schema_name, 'VIEW' AS type, table_name AS name "
                + "FROM information_schema.tables "
                + "WHERE table_type = 'VIEW' "
                + "AND table_schema NOT LIKE 'pg%' AND table_schema <> 'information_schema' "
                + "ORDER BY table_schema, table_name";
        return jdbcOperations.query(sql, IDENTITY_MAPPER);
    }

    @Override
    public List<DBObjectIdentity> listAllSystemViews() {
        String sql = "SELECT table_schema AS schema_name, 'VIEW' AS type, table_name AS name "
                + "FROM information_schema.tables "
                + "WHERE table_type = 'VIEW' "
                + "AND (table_schema LIKE 'pg%' OR table_schema = 'information_schema') "
                + "ORDER BY table_schema, table_name";
        return jdbcOperations.query(sql, IDENTITY_MAPPER);
    }

    /**
     * 合并扫描 user 表与视图（单次 information_schema 扫描），语义与 listTables + listAllUserViews 一致
     */
    @Override
    public List<DBObjectIdentity> listTablesAndViews(String schemaName, String nameLike) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT table_schema AS schema_name, ");
        sb.append("CASE WHEN table_type = 'VIEW' THEN 'VIEW' ELSE 'TABLE' END AS type, ");
        sb.append("table_name AS name ");
        sb.append("FROM information_schema.tables ");
        sb.append("WHERE table_type IN ('BASE TABLE', 'VIEW') ");
        sb.append("AND (table_type = 'VIEW' OR table_name NOT IN (SELECT relname FROM pg_class c ");
        sb.append("JOIN pg_inherits i ON c.oid = i.inhrelid)) ");
        sb.append("AND table_schema NOT LIKE 'pg%' AND table_schema <> 'information_schema' ");
        if (StringUtils.isNotBlank(schemaName)) {
            sb.append("AND table_schema = ? ");
        }
        if (StringUtils.isNotBlank(nameLike)) {
            sb.append("AND table_name LIKE ? ");
        }
        sb.append("ORDER BY table_schema, table_name");
        return queryIdentities(sb.toString(), schemaName, nameLike);
    }

    private List<DBObjectIdentity> queryIdentities(String sql, String schemaName, String nameLike) {
        if (StringUtils.isNotBlank(schemaName) && StringUtils.isNotBlank(nameLike)) {
            return jdbcOperations.query(sql, IDENTITY_MAPPER, schemaName, nameLike);
        } else if (StringUtils.isNotBlank(schemaName)) {
            return jdbcOperations.query(sql, IDENTITY_MAPPER, schemaName);
        } else if (StringUtils.isNotBlank(nameLike)) {
            return jdbcOperations.query(sql, IDENTITY_MAPPER, nameLike);
        }
        return jdbcOperations.query(sql, IDENTITY_MAPPER);
    }

    @Override
    public List<String> showSystemViews(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBVariable> showVariables() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBVariable> showSessionVariables() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBVariable> showGlobalVariables() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<String> showCharset() {
        String sql = "select DISTINCT pg_encoding_to_char(collencoding) from pg_collation;";
        return jdbcOperations.queryForList(sql, String.class);
    }

    @Override
    public List<String> showCollation() {
        String sql = "select DISTINCT collname  from pg_collation;";
        return jdbcOperations.queryForList(sql, String.class);
    }

    @Override
    public List<DBPLObjectIdentity> listFunctions(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBPLObjectIdentity> listProcedures(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBPLObjectIdentity> listPackages(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBPLObjectIdentity> listPackageBodies(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBPLObjectIdentity> listTriggers(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBPLObjectIdentity> listTypes(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBObjectIdentity> listSequences(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBObjectIdentity> listSynonyms(String schemaName,
            DBSynonymType synonymType) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, List<DBTableColumn>> listTableColumns(
            String schemaName, List<String> tableNames) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBTableColumn> listTableColumns(String schemeName, String tableName) {
        // GaussDB 兼容：不使用 to_regclass/format（PG 9.4+），改用 pg_class 关联子查询取 oid
        String sql = "SELECT c.column_name, "
                + "COALESCE(NULLIF(c.udt_name, ''), c.data_type) AS type_name, "
                + "CASE WHEN c.character_maximum_length IS NOT NULL "
                + "THEN COALESCE(NULLIF(c.udt_name, ''), c.data_type) || '(' || c.character_maximum_length || ')' "
                + "WHEN c.data_type IN ('numeric', 'decimal') AND c.numeric_precision IS NOT NULL AND c.numeric_scale IS NOT NULL "
                + "THEN COALESCE(NULLIF(c.udt_name, ''), c.data_type) || '(' || c.numeric_precision || ',' || c.numeric_scale || ')' "
                + "ELSE COALESCE(NULLIF(c.udt_name, ''), c.data_type) END AS full_type_name, "
                + "c.numeric_scale, c.numeric_precision, c.is_nullable, c.column_default, c.ordinal_position, "
                + "COALESCE(col_description((SELECT c2.oid FROM pg_class c2 "
                + "JOIN pg_namespace n2 ON n2.oid = c2.relnamespace "
                + "WHERE n2.nspname = c.table_schema AND c2.relname = c.table_name), c.ordinal_position), '') AS column_comment, "
                + "(c.column_default LIKE 'nextval%') AS is_auto_increment "
                + "FROM information_schema.columns c "
                + "WHERE c.table_schema = ? AND c.table_name = ? "
                + "ORDER BY c.ordinal_position";
        return jdbcOperations.query(sql, (rs, rowNum) -> {
            DBTableColumn column = new DBTableColumn();
            column.setSchemaName(schemeName);
            column.setTableName(tableName);
            column.setName(rs.getString("column_name"));
            column.setTypeName(rs.getString("type_name"));
            column.setFullTypeName(rs.getString("full_type_name"));
            if (rs.getObject("numeric_scale") != null) {
                column.setScale(rs.getInt("numeric_scale"));
            }
            if (rs.getObject("numeric_precision") != null) {
                column.setPrecision(rs.getLong("numeric_precision"));
            }
            column.setNullable("YES".equals(rs.getString("is_nullable")));
            column.setDefaultValue(rs.getString("column_default"));
            column.setComment(rs.getString("column_comment"));
            column.setOrdinalPosition(rs.getInt("ordinal_position"));
            column.setAutoIncrement(rs.getBoolean("is_auto_increment"));
            return column;
        }, schemeName, tableName);
    }

    @Override
    public Map<String, List<DBTableColumn>> listBasicTableColumns(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBTableColumn> listBasicTableColumns(String schemaName, String tableName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, List<DBTableColumn>> listBasicViewColumns(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBTableColumn> listBasicViewColumns(String schemaName, String viewName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, List<DBTableColumn>> listBasicColumnsInfo(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, List<DBTableIndex>> listTableIndexes(String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, List<DBTableConstraint>> listTableConstraints(
            String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, DBTableOptions> listTableOptions(
            String schemaName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, DBTablePartition> listTablePartitions(
            @NonNull String schemaName, List<String> tableNames) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBTablePartition> listTableRangePartitionInfo(String tenantName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBTableSubpartitionDefinition> listSubpartitions(
            String schemaName, String tableName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Boolean isLowerCaseTableName() {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBObjectIdentity> listPartitionTables(String partitionMethod) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBTableConstraint> listTableConstraints(String schemaName, String tableName) {
        // GaussDB 兼容：不使用 LATERAL/WITH ORDINALITY（PG 9.3/9.4+），改用 generate_subscripts（8.1+）
        String sql = "SELECT con.conname AS constraint_name, "
                + "CASE con.contype WHEN 'p' THEN 'PRIMARY_KEY' WHEN 'u' THEN 'UNIQUE_KEY' "
                + "WHEN 'f' THEN 'FOREIGN_KEY' WHEN 'c' THEN 'CHECK' ELSE 'UNKNOWN' END AS constraint_type, "
                + "COALESCE(att.attname, '') AS column_name, "
                + "COALESCE(rns.nspname, '') AS ref_schema, "
                + "COALESCE(rtbl.relname, '') AS ref_table, "
                + "COALESCE(ratt.attname, '') AS ref_column, "
                + "pg_get_constraintdef(con.oid) AS definition "
                + "FROM pg_constraint con "
                + "JOIN pg_class t ON con.conrelid = t.oid "
                + "JOIN pg_namespace ns ON t.relnamespace = ns.oid "
                + "LEFT JOIN generate_subscripts(con.conkey, 1) AS ord ON true "
                + "LEFT JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[ord] "
                + "LEFT JOIN pg_class rtbl ON con.confrelid = rtbl.oid "
                + "LEFT JOIN pg_namespace rns ON rtbl.relnamespace = rns.oid "
                + "LEFT JOIN pg_attribute ratt ON ratt.attrelid = con.confrelid AND ratt.attnum = con.confkey[ord] "
                + "WHERE ns.nspname = ? AND t.relname = ? "
                + "ORDER BY con.conname, ord";
        Map<String, DBTableConstraint> constraints = new LinkedHashMap<>();
        jdbcOperations.query(sql, rs -> {
            String name = rs.getString("constraint_name");
            String typeName = rs.getString("constraint_type");
            String columnName = rs.getString("column_name");
            String refSchema = rs.getString("ref_schema");
            String refTable = rs.getString("ref_table");
            String refColumn = rs.getString("ref_column");
            String definition = rs.getString("definition");
            DBTableConstraint constraint = constraints.computeIfAbsent(name, k -> {
                DBTableConstraint c = new DBTableConstraint();
                c.setSchemaName(schemaName);
                c.setTableName(tableName);
                c.setName(name);
                c.setType(DBConstraintType.valueOf(typeName));
                c.setColumnNames(new ArrayList<>());
                c.setReferenceColumnNames(new ArrayList<>());
                if (c.getType() == DBConstraintType.CHECK) {
                    c.setCheckClause(definition);
                }
                if (refTable != null && !refTable.isEmpty()) {
                    c.setReferenceSchemaName(refSchema);
                    c.setReferenceTableName(refTable);
                }
                return c;
            });
            if (columnName != null && !columnName.isEmpty()) {
                constraint.getColumnNames().add(columnName);
            }
            if (refColumn != null && !refColumn.isEmpty()) {
                constraint.getReferenceColumnNames().add(refColumn);
            }
        }, schemaName, tableName);
        return new ArrayList<>(constraints.values());
    }

    @Override
    public DBTablePartition getPartition(String schemaName, String tableName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public List<DBTableIndex> listTableIndexes(String schemaName, String tableName) {
        // GaussDB 兼容：不使用 LATERAL/WITH ORDINALITY（PG 9.3/9.4+），改用 generate_subscripts（8.1+）
        String sql = "SELECT i.relname AS index_name, ix.indisprimary AS is_primary, "
                + "ix.indisunique AS is_unique, "
                + "COALESCE(obj_description(i.oid), '') AS index_comment, "
                + "COALESCE(att.attname, '') AS column_name "
                + "FROM pg_index ix "
                + "JOIN pg_class t ON ix.indrelid = t.oid "
                + "JOIN pg_namespace ns ON t.relnamespace = ns.oid "
                + "JOIN pg_class i ON ix.indexrelid = i.oid "
                + "LEFT JOIN generate_subscripts(ix.indkey, 1) AS ik_ord ON true "
                + "LEFT JOIN pg_attribute att ON att.attrelid = t.oid AND att.attnum = ix.indkey[ik_ord] "
                + "WHERE ns.nspname = ? AND t.relname = ? "
                + "ORDER BY i.relname, ik_ord";
        Map<String, DBTableIndex> indexes = new LinkedHashMap<>();
        jdbcOperations.query(sql, rs -> {
            String name = rs.getString("index_name");
            boolean primary = rs.getBoolean("is_primary");
            boolean unique = rs.getBoolean("is_unique");
            String indexComment = rs.getString("index_comment");
            String columnName = rs.getString("column_name");
            DBTableIndex index = indexes.computeIfAbsent(name, k -> {
                DBTableIndex idx = new DBTableIndex();
                idx.setSchemaName(schemaName);
                idx.setTableName(tableName);
                idx.setName(name);
                idx.setPrimary(primary);
                idx.setUnique(primary || unique);
                idx.setType(primary || unique ? DBIndexType.UNIQUE : DBIndexType.NORMAL);
                idx.setAlgorithm(DBIndexAlgorithm.BTREE);
                idx.setComment(indexComment);
                idx.setGlobal(true);
                idx.setVisible(true);
                idx.setColumnNames(new ArrayList<>());
                return idx;
            });
            if (columnName != null && !columnName.isEmpty()) {
                index.getColumnNames().add(columnName);
            }
        }, schemaName, tableName);
        return new ArrayList<>(indexes.values());
    }

    @Override
    public DBTableOptions getTableOptions(String schemaName, String tableName) {
        DBTableOptions options = new DBTableOptions();
        // GaussDB 兼容：不使用 to_regclass/format（PG 9.4+），标量子查询对不存在的表返回空串
        String sql = "SELECT COALESCE((SELECT obj_description(c.oid, 'pg_class') "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ? LIMIT 1), '') AS table_comment";
        options.setComment(jdbcOperations.queryForObject(sql, String.class, schemaName, tableName));
        return options;
    }

    @Override
    public DBTableOptions getTableOptions(String schemaName, String tableName, String ddl) {
        return getTableOptions(schemaName, tableName);
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String getTableDDL(String schemaName, String tableName) {
        List<DBTableColumn> columns = listTableColumns(schemaName, tableName);
        List<DBTableConstraint> constraints = listTableConstraints(schemaName, tableName);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ")
                .append(quoteIdentifier(schemaName)).append('.').append(quoteIdentifier(tableName)).append(" (\n");
        List<String> columnDefs = new ArrayList<>();
        for (DBTableColumn column : columns) {
            StringBuilder def = new StringBuilder("    ").append(quoteIdentifier(column.getName()))
                    .append(' ').append(column.getFullTypeName());
            if (column.getDefaultValue() != null) {
                def.append(" DEFAULT ").append(column.getDefaultValue());
            }
            if (Boolean.FALSE.equals(column.getNullable())) {
                def.append(" NOT NULL");
            }
            columnDefs.add(def.toString());
        }
        for (DBTableConstraint constraint : constraints) {
            StringBuilder def = new StringBuilder("    ");
            if (constraint.getType() == DBConstraintType.CHECK) {
                def.append(constraint.getCheckClause());
                columnDefs.add(def.toString());
                continue;
            }
            def.append("CONSTRAINT ").append(quoteIdentifier(constraint.getName())).append(' ')
                    .append(constraint.getType().getValue());
            if (constraint.getType() != DBConstraintType.NOT_NULL) {
                def.append(" (").append(String.join(", ",
                        constraint.getColumnNames().stream().map(PostgresSchemaAccessor::quoteIdentifier)
                                .collect(Collectors.toList())))
                        .append(')');
            }
            if (constraint.getType() == DBConstraintType.FOREIGN_KEY
                    && constraint.getReferenceTableName() != null) {
                def.append(" REFERENCES ");
                if (constraint.getReferenceSchemaName() != null) {
                    def.append(quoteIdentifier(constraint.getReferenceSchemaName())).append('.');
                }
                def.append(quoteIdentifier(constraint.getReferenceTableName())).append(" (")
                        .append(String.join(", ",
                                constraint.getReferenceColumnNames().stream()
                                        .map(PostgresSchemaAccessor::quoteIdentifier).collect(Collectors.toList())))
                        .append(')');
            }
            columnDefs.add(def.toString());
        }
        ddl.append(String.join(",\n", columnDefs)).append("\n);");
        return ddl.toString();
    }

    @Override
    public List<DBColumnGroupElement> listTableColumnGroups(String schemaName,
            String tableName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBView getView(String schemaName, String viewName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBFunction getFunction(String schemaName, String functionName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBProcedure getProcedure(String schemaName, String procedureName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBPackage getPackage(String schemaName, String packageName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBTrigger getTrigger(String schemaName, String packageName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBType getType(String schemaName, String typeName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBSequence getSequence(String schemaName, String sequenceName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBSynonym getSynonym(String schemaName, String synonymName,
            DBSynonymType synonymType) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public Map<String, DBTable> getTables(String schemaName,
            List<String> tableNames) {
        throw new UnsupportedOperationException("Not supported yet");
    }
}
