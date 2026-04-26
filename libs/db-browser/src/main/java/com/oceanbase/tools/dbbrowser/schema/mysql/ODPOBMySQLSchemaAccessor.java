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
package com.oceanbase.tools.dbbrowser.schema.mysql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.lang.NonNull;

import com.oceanbase.tools.dbbrowser.model.DBColumnTypeDisplay;
import com.oceanbase.tools.dbbrowser.model.DBDatabase;
import com.oceanbase.tools.dbbrowser.model.DBFunction;
import com.oceanbase.tools.dbbrowser.model.DBObjectIdentity;
import com.oceanbase.tools.dbbrowser.model.DBObjectType;
import com.oceanbase.tools.dbbrowser.model.DBPLObjectIdentity;
import com.oceanbase.tools.dbbrowser.model.DBProcedure;
import com.oceanbase.tools.dbbrowser.model.DBTable;
import com.oceanbase.tools.dbbrowser.model.DBTable.DBTableOptions;
import com.oceanbase.tools.dbbrowser.model.DBTableColumn;
import com.oceanbase.tools.dbbrowser.model.DBTableConstraint;
import com.oceanbase.tools.dbbrowser.model.DBTableIndex;
import com.oceanbase.tools.dbbrowser.model.DBTablePartition;
import com.oceanbase.tools.dbbrowser.model.DBTablePartitionOption;
import com.oceanbase.tools.dbbrowser.model.DBTablePartitionType;
import com.oceanbase.tools.dbbrowser.model.MySQLConstants;
import com.oceanbase.tools.dbbrowser.util.DBSchemaAccessorUtil;
import com.oceanbase.tools.dbbrowser.util.MySQLSqlBuilder;
import com.oceanbase.tools.dbbrowser.util.StringUtils;

/**
 * @author jingtian
 */
public class ODPOBMySQLSchemaAccessor extends MySQLNoLessThan5700SchemaAccessor {

    public ODPOBMySQLSchemaAccessor(JdbcOperations jdbcOperations) {
        super(jdbcOperations);
    }

    @Override
    public List<DBDatabase> listDatabases() {
        List<DBDatabase> dbDatabases = new ArrayList<>();
        String sql = "show databases";
        List<String> dbNames = jdbcOperations.queryForList(sql, String.class);
        for (String dbName : dbNames) {
            DBDatabase database = new DBDatabase();
            jdbcOperations.execute("use " + dbName);
            database.setName(dbName);
            database.setId(dbName);
            database.setCharset(
                    jdbcOperations.queryForObject("show variables like 'character_set_database';",
                            (rs, num) -> rs.getString(2)));
            database.setCollation(
                    jdbcOperations.queryForObject("show variables like 'collation_database';",
                            (rs, num) -> rs.getString(2)));
            dbDatabases.add(database);
        }
        return dbDatabases;
    }

    @Override
    public List<String> showTables(String schemaName) {
        MySQLSqlBuilder sb = new MySQLSqlBuilder();
        sb.append("SHOW TABLES ");
        if (StringUtils.isNotBlank(schemaName)) {
            sb.append("FROM ");
            sb.identifier(schemaName);
            sb.append(" ");
        }
        String sql = sb.toString();
        return jdbcOperations.queryForList(sql, String.class);
    }

    @Override
    public List<DBObjectIdentity> listTables(String schemaName, String tableNameLike) {
        String sql = "select database()";
        String currentSchema = jdbcOperations.queryForObject(sql, (rs, rowNum) -> rs.getString(1));
        List<DBObjectIdentity> results = new ArrayList<>();
        MySQLSqlBuilder builder = new MySQLSqlBuilder();
        builder.append("show full tables");
        if (StringUtils.isNotBlank(schemaName)) {
            builder.append(" from ").identifier(schemaName);
        }
        builder.append(" where Table_type='BASE TABLE'");
        List<String> tables = jdbcOperations.query(builder.toString(), (rs, rowNum) -> rs.getString(1));
        tables.forEach(name -> results.add(DBObjectIdentity
                .of(StringUtils.isBlank(schemaName) ? currentSchema : schemaName, DBObjectType.TABLE, name)));
        return results;
    }

    @Override
    public List<String> showTablesLike(String schemaName, String tableNameLike) {
        MySQLSqlBuilder sb = new MySQLSqlBuilder();
        if (tableNameLike == null) {
            sb.append("show tables");
        } else {
            sb.append("show tables like ").value("%" + tableNameLike + "%");
        }
        return jdbcOperations.queryForList(sb.toString(), String.class);
    }

    /**
     * Use SHOW FULL COLUMNS instead of querying information_schema.columns, because ODP sharding mode
     * does not support routing queries to information_schema tables, which would cause "Unable to find
     * table rule: null" error.
     */
    @Override
    public List<DBTableColumn> listTableColumns(String schemaName, String tableName) {
        MySQLSqlBuilder sb = new MySQLSqlBuilder();
        sb.append("SHOW FULL COLUMNS FROM ");
        if (StringUtils.isNotBlank(schemaName)) {
            sb.identifier(schemaName).append(".");
        }
        sb.identifier(tableName);
        return jdbcOperations.query(sb.toString(), (rs, rowNum) -> {
            DBTableColumn column = new DBTableColumn();
            column.setTableName(tableName);
            column.setSchemaName(schemaName);
            column.setName(rs.getString("Field"));
            String fullTypeName = rs.getString("Type");
            column.setFullTypeName(fullTypeName);
            // Extract the base type name from full type (e.g., "int(11)" -> "int")
            String typeName = fullTypeName;
            if (typeName.contains("(")) {
                typeName = typeName.substring(0, typeName.indexOf("("));
            }
            // Remove unsigned/zerofill modifiers for type name matching
            typeName = typeName.replaceAll("\\s+(unsigned|zerofill)", "");
            column.setTypeName(typeName.trim());
            if (StringUtils.isNotBlank(column.getTypeName())
                    && (isTypeEnum(column.getTypeName()) || isTypeSet(column.getTypeName()))) {
                column.setEnumValues(DBSchemaAccessorUtil.parseEnumValues(fullTypeName));
            }
            DBColumnTypeDisplay columnTypeDisplay = DBColumnTypeDisplay.fromName(column.getTypeName());
            if (columnTypeDisplay.displayPrecision() && fullTypeName.contains("(")) {
                String inParentheses = fullTypeName.substring(fullTypeName.indexOf("(") + 1,
                        fullTypeName.lastIndexOf(")"));
                try {
                    if (isTypeEnum(column.getTypeName()) || isTypeSet(column.getTypeName())) {
                        // enum/set: precision is not applicable
                    } else {
                        String[] parts = inParentheses.split(",");
                        if (parts.length == 1) {
                            column.setPrecision(Long.parseLong(parts[0].trim()));
                        } else if (parts.length >= 2) {
                            column.setPrecision(Long.parseLong(parts[0].trim()));
                            column.setScale(Integer.parseInt(parts[1].trim()));
                        }
                    }
                } catch (NumberFormatException e) {
                    // ignore parse errors for non-numeric precision
                }
            }
            if (columnTypeDisplay.displayScale()) {
                // Scale already set above if applicable
            }
            column.setMaxLength(column.getPrecision());
            column.setCollationName(rs.getString("Collation"));
            column.setNullable("YES".equalsIgnoreCase(rs.getString("Null")));
            String keyTypeName = rs.getString("Key");
            if (StringUtils.isNotBlank(keyTypeName)) {
                column.setKeyType(DBTableColumn.KeyType.valueOf(keyTypeName));
            }
            column.fillDefaultValue(rs.getString("Default"));
            column.setExtraInfo(rs.getString("Extra"));
            column.setComment(rs.getString("Comment"));
            column.setOrdinalPosition(rowNum);
            if (Objects.nonNull(column.getExtraInfo())) {
                column.setAutoIncrement(
                        column.getExtraInfo().equalsIgnoreCase(MySQLConstants.EXTRA_AUTO_INCREMENT));
                column.setOnUpdateCurrentTimestamp(
                        column.getExtraInfo().equalsIgnoreCase(MySQLConstants.EXTRA_ON_UPDATE_CURRENT_TIMESTAMP));
                column.setStored(column.getExtraInfo()
                        .equalsIgnoreCase(MySQLConstants.EXTRA_STORED_GENERATED));
            }
            column.setTypeModifiers(new ArrayList<>());
            column.setZerofill(false);
            column.setUnsigned(false);
            for (String modifier : fullTypeName.toLowerCase().split("\\s+")) {
                switch (modifier) {
                    case "zerofill":
                        column.setZerofill(true);
                        column.getTypeModifiers().add("zerofill");
                        break;
                    case "unsigned":
                        column.setUnsigned(true);
                        column.getTypeModifiers().add("unsigned");
                        break;
                }
            }
            return column;
        });
    }

    /**
     * Use SHOW TABLE STATUS instead of querying information_schema.tables and
     * information_schema.collation_character_set_applicability, because ODP sharding mode does not
     * support routing queries to information_schema tables.
     */
    @Override
    public Map<String, DBTableOptions> listTableOptions(String schemaName) {
        Map<String, DBTableOptions> tableName2TableOptions = new LinkedHashMap<>();
        MySQLSqlBuilder sb = new MySQLSqlBuilder();
        sb.append("SHOW TABLE STATUS FROM ");
        sb.identifier(schemaName);
        jdbcOperations.query(sb.toString(), t -> {
            String tableName = t.getString("Name");
            String tableType = t.getString("Comment");
            // SHOW TABLE STATUS returns all tables and views, only process BASE TABLE
            if ("VIEW".equalsIgnoreCase(tableType)) {
                return;
            }
            DBTableOptions options = new DBTableOptions();
            tableName2TableOptions.putIfAbsent(tableName, options);
            options.setCreateTime(t.getTimestamp("Create_time"));
            options.setUpdateTime(t.getTimestamp("Update_time"));
            long autoIncrement = t.getLong("Auto_increment");
            if (!t.wasNull()) {
                options.setAutoIncrementInitialValue(autoIncrement);
            }
            options.setCollationName(t.getString("Collation"));
            options.setComment(t.getString("Comment"));
        });
        return tableName2TableOptions;
    }

    /**
     * Use SHOW TABLE STATUS instead of querying information_schema.tables for single table options,
     * because ODP sharding mode does not support routing queries to information_schema tables.
     */
    @Override
    public DBTableOptions getTableOptions(String schemaName, String tableName) {
        return getTableOptions(schemaName, tableName, getTableDDL(schemaName, tableName));
    }

    @Override
    public DBTableOptions getTableOptions(String schemaName, String tableName, @NonNull String ddl) {
        DBTableOptions dbTableOptions = new DBTableOptions();
        MySQLSqlBuilder sb = new MySQLSqlBuilder();
        sb.append("SHOW TABLE STATUS FROM ");
        sb.identifier(schemaName);
        sb.append(" LIKE ");
        sb.value(tableName);
        jdbcOperations.query(sb.toString(), t -> {
            dbTableOptions.setCreateTime(t.getTimestamp("Create_time"));
            dbTableOptions.setUpdateTime(t.getTimestamp("Update_time"));
            long autoIncrement = t.getLong("Auto_increment");
            if (!t.wasNull()) {
                dbTableOptions.setAutoIncrementInitialValue(autoIncrement);
            }
            dbTableOptions.setCollationName(t.getString("Collation"));
            dbTableOptions.setComment(t.getString("Comment"));
        });
        try {
            obtainOptionsByParser(dbTableOptions, ddl);
        } catch (Exception e) {
            // ignore parse failures
        }
        return dbTableOptions;
    }

    private void obtainOptionsByParser(DBTableOptions dbTableOptions, String ddl) {
        try {
            com.oceanbase.tools.sqlparser.statement.Statement statement =
                    com.oceanbase.tools.dbbrowser.parser.SqlParser.parseMysqlStatement(ddl);
            if (statement instanceof com.oceanbase.tools.sqlparser.statement.createtable.CreateTable) {
                com.oceanbase.tools.sqlparser.statement.createtable.CreateTable stmt =
                        (com.oceanbase.tools.sqlparser.statement.createtable.CreateTable) statement;
                com.oceanbase.tools.sqlparser.statement.createtable.TableOptions options =
                        stmt.getTableOptions();
                if (Objects.nonNull(options)) {
                    dbTableOptions.setCharsetName(options.getCharset());
                    dbTableOptions.setRowFormat(options.getRowFormat());
                    dbTableOptions.setCompressionOption(options.getCompression());
                    dbTableOptions.setReplicaNum(options.getReplicaNum());
                    dbTableOptions.setBlockSize(options.getBlockSize());
                    dbTableOptions.setUseBloomFilter(options.getUseBloomFilter());
                    dbTableOptions.setTabletSize(
                            Objects.nonNull(options.getTabletSize()) ? options.getTabletSize().longValue() : null);
                }
            }
        } catch (Exception e) {
            // ignore parse failures
        }
    }

    /**
     * Use SHOW INDEX per table instead of querying information_schema.statistics, because ODP sharding
     * mode does not support routing queries to information_schema tables.
     */
    @Override
    public Map<String, List<DBTableIndex>> listTableIndexes(String schemaName) {
        Map<String, List<DBTableIndex>> tableName2Indexes = new LinkedHashMap<>();
        List<String> tableNames = showTables(schemaName);
        for (String tableName : tableNames) {
            try {
                List<DBTableIndex> indexes = listTableIndexes(schemaName, tableName);
                if (!indexes.isEmpty()) {
                    tableName2Indexes.put(tableName, indexes);
                }
            } catch (Exception e) {
                // skip tables whose indexes cannot be retrieved
            }
        }
        return tableName2Indexes;
    }

    /**
     * Partition querying is not supported in ODP sharding mode because it relies on
     * information_schema.partitions. Return not-partitioned by default.
     */
    @Override
    public DBTablePartition getPartition(String schemaName, String tableName) {
        DBTablePartition partition = new DBTablePartition();
        DBTablePartitionOption partitionOption = new DBTablePartitionOption();
        partitionOption.setType(DBTablePartitionType.NOT_PARTITIONED);
        partition.setPartitionOption(partitionOption);
        partition.setPartitionDefinitions(Collections.emptyList());
        return partition;
    }

    /**
     * Partition querying is not supported in ODP sharding mode because it relies on
     * information_schema.partitions. Return empty list.
     */
    @Override
    public List<DBObjectIdentity> listPartitionTables(String partitionMethod) {
        return Collections.emptyList();
    }

    /**
     * Batch version of listTableColumns. Uses SHOW FULL COLUMNS per table instead of querying
     * information_schema.columns, because ODP sharding mode does not support routing queries to
     * information_schema tables.
     */
    @Override
    public Map<String, List<DBTableColumn>> listTableColumns(String schemaName,
            List<String> tableNames) {
        Map<String, List<DBTableColumn>> result = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            try {
                List<DBTableColumn> columns = listTableColumns(schemaName, tableName);
                if (!columns.isEmpty()) {
                    result.put(tableName, columns);
                }
            } catch (Exception e) {
                // skip tables whose columns cannot be retrieved
            }
        }
        return result;
    }

    /**
     * Use SHOW FULL COLUMNS per table instead of querying information_schema.columns for basic column
     * info, because ODP sharding mode does not support routing queries to information_schema tables.
     */
    @Override
    public Map<String, List<DBTableColumn>> listBasicTableColumns(String schemaName) {
        Map<String, List<DBTableColumn>> result = new LinkedHashMap<>();
        List<String> tableNames = showTables(schemaName);
        for (String tableName : tableNames) {
            try {
                List<DBTableColumn> columns = listTableColumns(schemaName, tableName);
                if (!columns.isEmpty()) {
                    result.put(tableName, columns);
                }
            } catch (Exception e) {
                // skip tables whose columns cannot be retrieved
            }
        }
        return result;
    }

    /**
     * Not supported in ODP sharding mode, returns empty map.
     */
    @Override
    public Map<String, List<DBTableColumn>> listBasicViewColumns(String schemaName) {
        return Collections.emptyMap();
    }

    /**
     * Not supported in ODP sharding mode, returns empty list.
     */
    @Override
    public List<DBTableColumn> listBasicViewColumns(String schemaName, String viewName) {
        return Collections.emptyList();
    }

    /**
     * Not supported in ODP sharding mode, returns empty list.
     */
    @Override
    public List<DBTableColumn> listBasicTableColumns(String schemaName, String tableName) {
        return Collections.emptyList();
    }

    /**
     * Not supported in ODP sharding mode, returns empty map.
     */
    @Override
    public Map<String, List<DBTableColumn>> listBasicColumnsInfo(String schemaName) {
        return Collections.emptyMap();
    }

    /**
     * Override to avoid querying information_schema.tables in ODP sharding mode. Uses listTables +
     * listTableColumns + listTableIndexes per table.
     */
    @Override
    public Map<String, DBTable> getTables(@NonNull String schemaName, List<String> tableNames) {
        Map<String, DBTable> returnVal = new LinkedHashMap<>();
        List<String> tables = showTables(schemaName);
        if (tables.isEmpty()) {
            return returnVal;
        }
        for (String tableName : tables) {
            try {
                DBTable table = new DBTable();
                table.setSchemaName(schemaName);
                table.setOwner(schemaName);
                table.setName(tableName);
                table.setColumns(listTableColumns(schemaName, tableName));
                table.setIndexes(listTableIndexes(schemaName, tableName));
                table.setConstraints(listTableConstraints(schemaName, tableName));
                table.setTableOptions(getTableOptions(schemaName, tableName));
                table.setPartition(getPartition(schemaName, tableName));
                table.setDDL(getTableDDL(schemaName, tableName));
                returnVal.put(tableName, table);
            } catch (Exception e) {
                // skip tables whose info cannot be fully retrieved
            }
        }
        return returnVal;
    }

    @Override
    public List<DBObjectIdentity> listViews(String schemaName) {
        return Collections.emptyList();
    }

    @Override
    public List<DBObjectIdentity> listAllViews(String viewNameLike) {
        return Collections.emptyList();
    }

    @Override
    public List<DBObjectIdentity> listAllUserViews() {
        String sql = "select database()";
        String currentSchema = jdbcOperations.queryForObject(sql, (rs, rowNum) -> rs.getString(1));
        List<DBObjectIdentity> results = new ArrayList<>();
        sql = "show full tables where Table_type='VIEW'";
        List<String> views = jdbcOperations.query(sql, (rs, rowNum) -> rs.getString(1));
        views.forEach(name -> results.add(DBObjectIdentity.of(currentSchema, DBObjectType.VIEW, name)));
        return results;
    }

    @Override
    public Map<String, List<DBTableConstraint>> listTableConstraints(String schemaName) {
        // key_column_usage is not supported
        return new HashMap<>();
    }

    @Override
    public List<DBTableConstraint> listTableConstraints(String schemaName, String tableName) {
        // key_column_usage is not supported
        return Collections.emptyList();
    }

    @Override
    public List<DBObjectIdentity> listAllSystemViews() {
        return Collections.emptyList();
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
    public DBFunction getFunction(String schemaName, String functionName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

    @Override
    public DBProcedure getProcedure(String schemaName, String procedureName) {
        throw new UnsupportedOperationException("Not supported yet");
    }

}
