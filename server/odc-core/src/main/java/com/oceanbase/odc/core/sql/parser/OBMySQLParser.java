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

package com.oceanbase.odc.core.sql.parser;

import org.antlr.v4.runtime.tree.ParseTree;

import com.oceanbase.tools.sqlparser.statement.Statement;

/**
 * {@link OBMySQLParser}
 *
 * @author yh263208
 * @date 2023-11-20 15:20
 * @since ODC_release_4.2.1
 */
class OBMySQLParser extends com.oceanbase.tools.sqlparser.OBMySQLParser {

    @Override
    public Statement buildStatement(ParseTree root) {
        Statement statement = super.buildStatement(root);
        if (statement != null) {
            return statement;
        }
        statement = new OBMySQLDropStatementVisitor().visit(root);
        if (statement != null) {
            return statement;
        }
        return new OBMySQLCreateStatementVisitor().visit(root);
    }

}
