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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Unit tests for {@link JdbcResultRenderer}. The renderer turns a JDBC {@link ResultSet} into a
 * psql-style ASCII table pushed to the xterm frontend, so the tests assert on the rendered text
 * using a mocked {@link ResultSet}.
 *
 * @author moicena
 */
public class JdbcResultRendererTest {

    private final JdbcResultRenderer renderer = new JdbcResultRenderer();

    @Test
    public void render_twoColumnsTwoRows_containsHeaderColumnsAndRowCount() throws Exception {
        ResultSet rs = mockResultSet(new String[] {"id", "name"},
                new Object[][] {{1, "foo"}, {2, "bar"}});

        String output = renderer.render(rs);

        assertTrue("header 'id' should be rendered, got:\n" + output, output.contains("id"));
        assertTrue("header 'name' should be rendered, got:\n" + output, output.contains("name"));
        assertTrue("row value 'foo' should be rendered, got:\n" + output, output.contains("foo"));
        assertTrue("row value 'bar' should be rendered, got:\n" + output, output.contains("bar"));
        assertTrue("row count '(2 rows)' should be rendered, got:\n" + output, output.contains("(2 rows)"));
    }

    @Test
    public void render_singleRow_usesSingularForm() throws Exception {
        ResultSet rs = mockResultSet(new String[] {"v"}, new Object[][] {{42}});

        String output = renderer.render(rs);

        assertTrue("single row should be '(1 row)', got:\n" + output, output.contains("(1 row)"));
    }

    @Test
    public void render_longValue_truncatedWithEllipsis() throws Exception {
        String longValue =
                "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789";
        ResultSet rs = mockResultSet(new String[] {"c"}, new Object[][] {{longValue}});

        String output = renderer.render(rs, 10);

        assertTrue("truncated value should end with ellipsis, got:\n" + output, output.contains("…"));
        assertTrue("full long value must not appear, got:\n" + output, !output.contains(longValue));
    }

    @Test
    public void render_nullCell_stillCounted() throws Exception {
        ResultSet rs = mockResultSet(new String[] {"c"}, new Object[][] {{null}});

        String output = renderer.render(rs);

        assertTrue("null row still counted, got:\n" + output, output.contains("(1 row)"));
    }

    @Test
    public void render_emptyResultSet_zeroRows() throws Exception {
        ResultSet rs = mockResultSet(new String[] {"id"}, new Object[][] {});

        String output = renderer.render(rs);

        assertTrue("empty result should show '(0 rows)', got:\n" + output, output.contains("(0 rows)"));
        assertTrue("header still rendered, got:\n" + output, output.contains("id"));
    }

    /**
     * Build a minimal mock {@link ResultSet} backed by an in-memory column/row matrix. Only the
     * methods exercised by {@link JdbcResultRenderer} are stubbed. The cursor advances once per
     * {@code next()} call and {@code getObject(col)} reads from the row at the current cursor
     * position.
     */
    static ResultSet mockResultSet(String[] columnLabels, Object[][] rows) throws Exception {
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(metaData.getColumnCount()).thenReturn(columnLabels.length);
        for (int i = 0; i < columnLabels.length; i++) {
            when(metaData.getColumnLabel(i + 1)).thenReturn(columnLabels[i]);
        }
        final ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(metaData);

        // Cursor state: -1 == before first row.
        final AtomicInteger cursor = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(inv -> {
            int next = cursor.incrementAndGet();
            return next < rows.length;
        });
        when(rs.getObject(org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            int col = inv.getArgument(0);
            int row = cursor.get();
            if (row < 0 || row >= rows.length) {
                return null;
            }
            return rows[row][col - 1];
        });
        return rs;
    }
}
