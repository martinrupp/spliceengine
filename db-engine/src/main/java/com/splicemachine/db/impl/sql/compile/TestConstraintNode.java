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

package com.splicemachine.db.impl.sql.compile;

import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;

import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
import com.splicemachine.db.iapi.reference.ClassName;

import com.splicemachine.db.iapi.error.StandardException;

import com.splicemachine.db.iapi.types.TypeId;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;

import com.splicemachine.db.iapi.services.classfile.VMOpcode;

import java.util.List;

/**
 * A TestConstraintNode is used to determine when a constraint
 * has been violated.
 *
 */

public class TestConstraintNode extends UnaryLogicalOperatorNode
{
    private String sqlState;
    private String tableName;
    private String constraintName;

    /**
     * Initializer for a TestConstraintNode
     *
     * @param booleanValue    The operand of the constraint test
     * @param sqlState    The SQLState of the exception to throw if the
     *                    constraint has failed
     * @param tableName    The name of the table that the constraint is on
     * @param constraintName    The name of the constraint being checked
     */

    public void init(Object booleanValue,
                     Object sqlState,
                     Object tableName,
                     Object constraintName)
    {
        super.init(booleanValue, "throwExceptionIfFalse");
        this.sqlState = (String) sqlState;
        this.tableName = (String) tableName;
        this.constraintName = (String) constraintName;
    }

    /**
     * Bind this logical operator.  All that has to be done for binding
     * a logical operator is to bind the operand, check that the operand
     * is SQLBoolean, and set the result type to SQLBoolean.
     *
     * @param fromList            The query's FROM list
     * @param subqueryList        The subquery list being built as we find SubqueryNodes
     * @param aggregateVector    The aggregate vector being built as we find AggregateNodes
     *
     * @return    The new top of the expression tree.
     *
     * @exception StandardException        Thrown on error
     */

    public ValueNode bindExpression(
        FromList fromList, SubqueryList subqueryList,
        List<AggregateNode> aggregateVector)
            throws StandardException
    {
        bindOperand(fromList, subqueryList, aggregateVector);

        /*
        ** If the operand is not boolean, cast it.
        */

        if (!getOperand().getTypeServices().getTypeId().isBooleanTypeId())
        {
            castOperandAndBindCast(new DataTypeDescriptor(TypeId.BOOLEAN_ID, true));
        }

        /* Set the type info */
        setFullTypeInfo();

        return this;
    }

    /**
     * Do code generation for the TestConstraint operator.
     *
     * @param acb    The ExpressionClassBuilder for the class we're generating
     * @param mb    The method the expression will go into
     *
     *
     * @exception StandardException        Thrown on error
     */

    public void generateExpression(ExpressionClassBuilder acb,
                                            MethodBuilder mb)
                                    throws StandardException
    {

        /*
        ** This generates the following code:
        **
        ** operand.testConstraint(sqlState, tableName, constraintName)
        */

        getOperand().generateExpression(acb, mb);

        mb.push(sqlState);
        mb.push(tableName);
        mb.push(constraintName);

        mb.callMethod(VMOpcode.INVOKEINTERFACE, ClassName.BooleanDataValue,
                "throwExceptionIfFalse", ClassName.BooleanDataValue, 3);

    }
}
