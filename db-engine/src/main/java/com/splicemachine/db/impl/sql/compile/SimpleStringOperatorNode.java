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

import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;

import com.splicemachine.db.iapi.error.StandardException;

import com.splicemachine.db.iapi.types.TypeId;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;

import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.reference.ClassName;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.sql.Types;

import java.util.List;

/**
 * This node represents a unary upper or lower operator
 *
 */
@SuppressFBWarnings(value = "HE_INHERITS_EQUALS_USE_HASHCODE", justification="DB-9277")
public class SimpleStringOperatorNode extends UnaryOperatorNode
{

    public SimpleStringOperatorNode(ValueNode operand, String methodName, ContextManager cm) {
        setContextManager(cm);
        setNodeType(C_NodeTypes.SIMPLE_STRING_OPERATOR_NODE);
        super.init(operand, methodName, methodName);
    }

    /**
     * Bind this operator
     *
     * @param fromList            The query's FROM list
     * @param subqueryList        The subquery list being built as we find SubqueryNodes
     * @param aggregateVector    The aggregate vector being built as we find AggregateNodes
     *
     * @return    The new top of the expression tree.
     *
     * @exception StandardException        Thrown on error
     */
    @Override
    public ValueNode bindExpression(FromList fromList,
                                    SubqueryList subqueryList,
                                    List<AggregateNode> aggregateVector) throws StandardException {
        TypeId    operandType;

        bindOperand(fromList, subqueryList,
                aggregateVector);

        /*
        ** Check the type of the operand - this function is allowed only on
        ** string value (char and bit) types.
        */
        operandType = getOperand().getTypeId();

        switch (operandType.getJDBCTypeId())
        {
                case Types.CHAR:
                case Types.VARCHAR:
                case Types.LONGVARCHAR:
                case Types.CLOB:
                    break;
                case Types.JAVA_OBJECT:
                case Types.OTHER:
                {
                    throw StandardException.newException(SQLState.LANG_UNARY_FUNCTION_BAD_TYPE,
                                        methodName,
                                        operandType.getSQLTypeName());
                }

                default:
                    DataTypeDescriptor dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR, true,
                              getOperand().getTypeCompiler().
                                getCastToCharWidth(
                                    getOperand().getTypeServices(), getCompilerContext()));

                    setOperand((ValueNode)
                        getNodeFactory().getNode(
                            C_NodeTypes.CAST_NODE,
                            getOperand(),
                            dtd,
                            getContextManager()));

                // DERBY-2910 - Match current schema collation for implicit cast as we do for
                // explicit casts per SQL Spec 6.12 (10)
                getOperand().setCollationUsingCompilationSchema();

                ((CastNode) getOperand()).bindCastNodeOnly();
                    operandType = getOperand().getTypeId();
        }

        /*
        ** The result type of upper()/lower() is the type of the operand.
        */

        setType(new DataTypeDescriptor(operandType,
                getOperand().getTypeServices().isNullable(),
                getOperand().getTypeCompiler().
                    getCastToCharWidth(getOperand().getTypeServices(), getCompilerContext())
                        )
                );
        //Result of upper()/lower() will have the same collation as the
        //argument to upper()/lower().
        setCollationInfo(getOperand().getTypeServices());

        return this;
    }

    /**
     * Bind a ? parameter operand of the upper/lower function.
     *
     * @exception StandardException        Thrown on error
     */

    void bindParameter()
            throws StandardException
    {
        /*
        ** According to the SQL standard, if bit_length has a ? operand,
        ** its type is bit varying with the implementation-defined maximum length
        ** for a bit.
        */

        getOperand().setType(DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.VARCHAR));
        //collation of ? operand should be same as the compilation schema
        getOperand().setCollationUsingCompilationSchema();
    }

    /**
     * This is a length operator node.  Overrides this method
     * in UnaryOperatorNode for code generation purposes.
     */
    public String getReceiverInterfaceName() {
        return ClassName.StringDataValue;
    }

    @Override
    public double getBaseOperationCost() throws StandardException {
        double lowerCost = super.getBaseOperationCost();
        double localCost = SIMPLE_OP_COST * (getOperand() == null ? 1.0 : Math.min(getOperand().getTypeServices().getNull().getLength(), 64));
        double callCost = SIMPLE_OP_COST * FN_CALL_COST_FACTOR;
        return lowerCost + localCost + callCost;
    }
}
