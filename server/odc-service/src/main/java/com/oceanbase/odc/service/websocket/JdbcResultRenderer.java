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
package com.oceanbase.odc.service.websocket;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import com.oceanbase.odc.common.util.tableformat.BorderStyle;
import com.oceanbase.odc.common.util.tableformat.CellStyle;
import com.oceanbase.odc.common.util.tableformat.Table;

/**
 * Renders a JDBC {@link ResultSet} as a psql-style ASCII table so that the JDBC-backed web terminal
 * (see {@code JdbcClientProxy}) can push human-readable output to the xterm frontend, replacing the
 * text that the {@code psql} subprocess used to print.
 *
 * <p>
 * Example output:
 * </p>
 *
 * <pre>
 *  id | name
 * ----+------
 *   1 | foo
 *   2 | bar
 * (2 rows)
 * </pre>
 *
 * @author moicena
 */
public class JdbcResultRenderer {

    /**
     * Default upper bound on a single column's rendered width. Cell values longer than this are
     * truncated and suffixed with an ellipsis, matching psql's expanded display behaviour roughly.
     */
    public static final int DEFAULT_MAX_COLUMN_WIDTH = 64;

    private static final String ELLIPSIS = "…";

    /**
     * Render the current result set as an ASCII table followed by a row-count line. The cursor is
     * consumed (positioned after the last row) when this method returns.
     *
     * @param resultSet result set to render; not {@code null}
     * @return rendered table text WITHOUT a trailing prompt; caller appends its own prompt
     * @throws SQLException if reading the result set fails
     */
    public String render(ResultSet resultSet) throws SQLException {
        return render(resultSet, DEFAULT_MAX_COLUMN_WIDTH);
    }

    /**
     * Render the result set with an explicit maximum column width.
     *
     * @param resultSet result set to render; not {@code null}
     * @param maxColumnWidth maximum number of characters shown per column cell; values exceeding this
     *        are truncated with an ellipsis
     * @return rendered table text
     * @throws SQLException if reading the result set fails
     */
    public String render(ResultSet resultSet, int maxColumnWidth) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        if (columnCount == 0) {
            return "(no columns)\n";
        }
        // psql renders left-aligned text columns; the table formatter left-aligns by default via CellStyle.
        CellStyle cellStyle = new CellStyle();
        Table table = new Table(columnCount, BorderStyle.HORIZONTAL_ONLY);
        for (int i = 0; i < columnCount; i++) {
            String label = metaData.getColumnLabel(i + 1);
            table.setColumnWidth(i, 0, maxColumnWidth);
            table.addCell(truncate(label, maxColumnWidth), cellStyle);
        }
        long rows = 0;
        while (resultSet.next()) {
            for (int i = 0; i < columnCount; i++) {
                Object value = resultSet.getObject(i + 1);
                String text = value == null ? "" : value.toString();
                table.addCell(truncate(text, maxColumnWidth), cellStyle);
            }
            rows++;
        }
        StringBuilder output = new StringBuilder();
        output.append(table.render()).append("\n");
        output.append("(").append(rows).append(rows == 1 ? " row" : " rows").append(")\n\n");
        return output.toString();
    }

    private String truncate(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxWidth) {
            return value;
        }
        if (maxWidth <= ELLIPSIS.length()) {
            return value.substring(0, maxWidth);
        }
        return value.substring(0, maxWidth - ELLIPSIS.length()).concat(ELLIPSIS);
    }
}
