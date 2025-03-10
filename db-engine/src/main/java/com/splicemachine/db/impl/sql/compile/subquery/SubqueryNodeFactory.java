/*
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Some parts of this source code are based on Apache Derby, and the following notices apply to
 * Apache Derby:
 *
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified the Apache Derby code in this file.
 *
 * All such Splice Machine modifications are Copyright 2012 - 2020 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.db.impl.sql.compile.subquery;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
import com.splicemachine.db.iapi.sql.compile.NodeFactory;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.iapi.types.TypeId;
import com.splicemachine.db.impl.sql.compile.*;

/**
 * Higher level NodeFactory API for use in subquery flattening code.
 */
public class SubqueryNodeFactory {

    private NodeFactory nodeFactory;
    private ContextManager contextManager;

    public SubqueryNodeFactory(ContextManager contextManager, NodeFactory nodeFactory) {
        this.contextManager = contextManager;
        this.nodeFactory = nodeFactory;
    }

    /**
     * AndNode
     */
    public AndNode buildAndNode() throws StandardException {
        ValueNode left = buildBooleanTrue();
        ValueNode right = buildBooleanTrue();
        return new AndNode(left, right, contextManager);
    }

    /**
     * IsNullNode
     */
    public IsNullNode buildIsNullNode(ColumnReference columnReference) throws StandardException {
        IsNullNode node = (IsNullNode) nodeFactory.getNode(C_NodeTypes.IS_NULL_NODE, columnReference, contextManager);
        node.bindComparisonOperator();
        return node;
    }

    /**
     * BooleanConstantNode -- TRUE
     */
    public BooleanConstantNode buildBooleanTrue() throws StandardException {
        BooleanConstantNode trueNode = new BooleanConstantNode(Boolean.TRUE,contextManager);
        trueNode.setType(new DataTypeDescriptor(TypeId.BOOLEAN_ID, false));
        return trueNode;
    }

    /**
     * FromSubquery
     */
    public FromSubquery buildFromSubqueryNode(SelectNode outerSelectNode,
                                              ResultSetNode subqueryResultSet,
                                              ResultColumnList newRcl,
                                              String subqueryAlias) throws StandardException {
        FromSubquery fromSubquery = new FromSubquery(
                subqueryResultSet,
                null,                  // order by
                null,                  // offset
                null,                  // fetchFirst
                false,                 // hasJDBClimitClause
                subqueryAlias,
                newRcl,
                null,
                contextManager);
        fromSubquery.setTableNumber(outerSelectNode.getCompilerContext().getNextTableNumber());
        return fromSubquery;
    }

    /**
     * HalfOuterJoinNode
     */
    public HalfOuterJoinNode buildOuterJoinNode(FromList outerFromList,
                                                FromSubquery fromSubquery,
                                                ValueNode joinClause,
                                                FromTable outerFromTable) throws StandardException {
        /* If outerFromTable is null, that means there is only one outer table.
         * If outerFromTable is not null, it specifies the outer table we are flattening.
         */
        FromTable outerTable = outerFromTable == null ? (FromTable)outerFromList.getNodes().get(0) : outerFromTable;

        HalfOuterJoinNode outerJoinNode = (HalfOuterJoinNode) nodeFactory.getNode(
                C_NodeTypes.HALF_OUTER_JOIN_NODE,
                outerTable,                          // left side  - will be outer table(s)
                fromSubquery,                        // right side - will be FromSubquery
                joinClause,                          // join clause
                null,                                // using clause
                Boolean.FALSE,                       // is right join
                null,                                // table props
                contextManager);
        outerJoinNode.setTableNumber(fromSubquery.getCompilerContext().getNextTableNumber());
        outerJoinNode.LOJ_bindResultColumns(true);
        return outerJoinNode;
    }

}
