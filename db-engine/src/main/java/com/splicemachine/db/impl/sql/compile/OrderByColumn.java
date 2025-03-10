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

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;
import com.splicemachine.db.iapi.sql.compile.Visitor;

/**
 * An OrderByColumn is a column in the ORDER BY clause.  An OrderByColumn
 * can be ordered ascending or descending.
 *
 * We need to make sure that the named columns are
 * columns in that query, and that positions are within range.
 *
 */
public class OrderByColumn extends OrderedColumn {

    private ResultColumn    resultCol;
    private boolean            ascending = true;
    private boolean            nullsOrderedLow = false;
    private ValueNode expression;
    private OrderByList     list;
    /**
     * If this sort key is added to the result column list then it is at result column position
     * 1 + resultColumnList.size() - resultColumnList.getOrderBySelect() + addedColumnOffset
     * If the sort key is already in the result column list then addedColumnOffset < 0.
     */
    private int addedColumnOffset = -1;


       /**
     * Initializer.
     *
     * @param expression            Expression of this column
     */
    public void init(Object expression)
    {
        this.expression = (ValueNode)expression;
    }

    /**
     * Convert this object to a String.  See comments in QueryTreeNode.java
     * for how this should be done for tree printing.
     *
     * @return    This object as a String
     */
    public String toString() {
        if (SanityManager.DEBUG) {
            return
                "nullsOrderedLow: " + nullsOrderedLow + "\n" +
                "ascending; " + ascending + "\n" +
                "addedColumnOffset: " + addedColumnOffset + "\n" +
                super.toString();
        } else {
            return "";
        }
    }

    @Override
    public ValueNode getColumnExpression() {
        return expression;
    }

    @Override
    public void setColumnExpression(ValueNode cexpr)
    {
        this.expression = cexpr;

    }

    /**
     * Prints the sub-nodes of this object.  See QueryTreeNode.java for
     * how tree printing is supposed to work.
     *
     * @param depth        The depth of this node in the tree
     */
    public void printSubNodes(int depth)
    {
        if (SanityManager.DEBUG)
        {
            super.printSubNodes(depth);

            if (expression != null) {
                printLabel(depth, "expression: ");
                expression.treePrint(depth + 1);
            }

            if (resultCol != null) {
                printLabel(depth, "resultCol: ");
                resultCol.treePrint(depth + 1);
            }
        }
    }

    /**
     * Mark the column as descending order
     */
    public void setDescending() {
        ascending = false;
    }

    /**
     * Get the column order.  Overrides
     * OrderedColumn.isAscending.
     *
     * @return true if ascending, false if descending
     */
    public boolean isAscending() {
        return ascending;
    }

    /**
     * Mark the column as ordered NULL values lower than non-NULL values.
     */
    public void setNullsOrderedLow() {
        nullsOrderedLow = true;
    }

    /**
     * Get the column NULL ordering. Overrides
     * OrderedColumn.getIsNullsOrderedLow.
     *
     * @return true if NULLs ordered low, false if NULLs ordered high
     */
    public boolean isNullsOrderedLow() {
        return nullsOrderedLow;
    }

    /**
     * Get the underlying ResultColumn.
     *
     * @return The underlying ResultColumn.
     */
    ResultColumn getResultColumn()
    {
        return resultCol;
    }

    /**
     * Get the underlying expression, skipping over ResultColumns that
     * are marked redundant.
     */
    ValueNode getNonRedundantExpression()
    {
        ResultColumn    rc;
        ValueNode        value;
        ColumnReference    colref = null;

        for (rc = resultCol; rc.isRedundant(); rc = colref.getSource())
        {
            value = rc.getExpression();

            if (value instanceof ColumnReference)
            {
                colref = (ColumnReference) value;
            }
            else
            {
                if (SanityManager.DEBUG)
                {
                    SanityManager.THROWASSERT(
                        "value should be a ColumnReference, but is a " +
                        value.getClass().getName());
                }
            }
        }

        return rc.getExpression();
    }

    /**
     * Bind this column.
     *
     * During binding, we may discover that this order by column was pulled
     * up into the result column list, but is now a duplicate, because the
     * actual result column was expanded into the result column list when "*"
     * expressions were replaced with the list of the table's columns. In such
     * a situation, we will end up calling back to the OrderByList to
     * adjust the addedColumnOffset values of the columns; the "oblist"
     * parameter exists to allow that callback to be performed.
     *
     * @param target    The result set being selected from
     * @param oblist    OrderByList which contains this column
     *
     * @exception StandardException        Thrown on error
     * @exception StandardException        Thrown when column not found
     */
    public void bindOrderByColumn(ResultSetNode target, OrderByList oblist)
                throws StandardException {
        this.list = oblist;

        if (expression instanceof ColumnReference) {

            ColumnReference cr = (ColumnReference) expression;

            resultCol = resolveColumnReference(target,
                    cr);

            columnPosition = resultCol.getColumnPosition();

            if (addedColumnOffset >= 0 &&
                    target instanceof SelectNode &&
                    ((SelectNode) target).hasDistinct())
                throw StandardException.newException(SQLState.LANG_DISTINCT_ORDER_BY, cr.columnName);
        } else if (isReferedColByNum(expression)) {

            ResultColumnList targetCols = target.getResultColumns();
            columnPosition = (Integer) expression.getConstantValueAsObject();
            resultCol = targetCols.getOrderByColumn(columnPosition);

            /* Column is out of range if either a) resultCol is null, OR
             * b) resultCol points to a column that is not visible to the
             * user (i.e. it was generated internally).
             */
            if ((resultCol == null) ||
                    (resultCol.getColumnPosition() > targetCols.visibleSize())) {
                throw StandardException.newException(SQLState.LANG_COLUMN_OUT_OF_RANGE,
                        String.valueOf(columnPosition));
            }

        } else {
            if (list.isTableValueCtorOrdering()) {
                // For VALUES, we only allow ordering by column number,
                // SQL-92 style. This is a more general expression, so throw.
                throw StandardException.newException(
                        SQLState.LANG_TABLE_VALUE_CTOR_RESTRICTION);
            }
            /*checks for the conditions when using distinct*/
            if (addedColumnOffset >= 0 &&
                    target instanceof SelectNode &&
                    ((SelectNode) target).hasDistinct() &&
                    !expressionMatch(target)) {
                String col = null;
                boolean match = false;

                CollectNodesVisitor collectNodesVisitor =
                        new CollectNodesVisitor(ColumnReference.class);
                expression.accept(collectNodesVisitor);

                for (Object o : collectNodesVisitor.getList()) {//visits through the columns in this OrderByColumn
                    ColumnReference cr1 = (ColumnReference) o;
                    col = cr1.getColumnName();
                    match = columnMatchFound(target, cr1);
                    /* breaks if a match not found, this is needed
                     * because all column references in this
                     * OrderByColumn should be there in the select
                     * clause.*/
                    if (!match)
                        throw StandardException.newException(
                                SQLState.LANG_DISTINCT_ORDER_BY,
                                col);
                }
            }

            if (SanityManager.DEBUG)
                SanityManager.ASSERT(addedColumnOffset >= 0,
                        "Order by expression was not pulled into the result column list");
            resolveAddedColumn(target);
            if (resultCol == null)
                throw StandardException.newException(SQLState.LANG_UNION_ORDER_BY);
        }

        // Verify that the column is orderable
        resultCol.verifyOrderable();
        if (resultCol.getTypeId().getTypeFormatId() == StoredFormatIds.DECFLOAT_TYPE_ID) {
            {
                throw StandardException.newException(SQLState.LANG_COLUMN_NOT_ORDERABLE_DURING_EXECUTION,
                        resultCol.getTypeId().getSQLTypeName());
            }

        }
    }

        /**
         * Checks whether the whole expression (OrderByColumn) itself
     * found in the select clause.
     * @param target Result set
     * @return boolean: whether any expression match found
     * @throws StandardException
     */
    private boolean expressionMatch(ResultSetNode target)
                                        throws StandardException{
        ResultColumnList rcl=target.getResultColumns();
        for (int i=1; i<=rcl.visibleSize();i++){
            //since RCs are 1 based
            if((rcl.getResultColumn(i)).isEquivalent(
                    resultCol))
                return true;
        }
        return false;
    }

    /**
     * This method checks a ColumnReference of this OrderByColumn
     * against the ColumnReferences of the select clause of the query.
     * @param target result set
     * @param crOfExpression the CR to be checked
     * @return whether a match found or not
     * @throws StandardException
     */
    private boolean columnMatchFound(ResultSetNode target,
            ColumnReference crOfExpression) throws StandardException{
        ResultColumnList rcl=target.getResultColumns();
        for (int i=1; i<=rcl.visibleSize();
        i++){//grab the RCs related to select clause
            ValueNode exp=rcl.getResultColumn(i).getExpression();
            if(exp instanceof ColumnReference)
            {//visits through the columns in the select clause
                ColumnReference cr2 =
                    (ColumnReference) (exp);
                if(crOfExpression.isEquivalent(cr2))
                    return true;
            }
        }
        return false;
    }

    /**
     * Assuming this OrderByColumn was "pulled" into the received target's
     * ResultColumnList (because it wasn't there to begin with), use
     * this.addedColumnOffset to figure out which of the target's result
     * columns is the one corresponding to "this".
     *
     * The desired position is w.r.t. the original, user-specified result
     * column list--which is what "visibleSize()" gives us.  I.e. To get
     * this OrderByColumn's position in target's RCL, first subtract out
     * all columns which were "pulled" into the RCL for GROUP BY or ORDER
     * BY, then add "this.addedColumnOffset". As an example, if the query
     * was:
     *
     *   select sum(j) as s from t1 group by i, k order by k, sum(k)
     *
     * then we will internally add columns "K" and "SUM(K)" to the RCL for
     * ORDER BY, *AND* we will add a generated column "I" to the RCL for
     * GROUP BY.  Thus we end up with four result columns:
     *
     *          (1)        (2)  (3)   (4)
     *  select sum(j) as s, K, SUM(K), I from t1 ...
     *
     * So when we get here and we want to find out which column "this"
     * corresponds to, we begin by taking the total number of VISIBLE
     * columns, which is 1 (i.e. 4 total columns minus 1 GROUP BY column
     * minus 2 ORDER BY columns).  Then we add this.addedColumnOffset in
     * order to find the target column position.  Since addedColumnOffset
     * is 0-based, an addedColumnOffset value of "0" means we want the
     * the first ORDER BY column added to target's RCL, "1" means we want
     * the second ORDER BY column added, etc.  So if we assume that
     * this.addedColumnOffset is "1" in this example then we add that
     * to the RCL's "visible size". And finally, we add 1 more to account
     * for fact that addedColumnOffset is 0-based while column positions
     * are 1-based. This gives:
     *
     *  position = 1 + 1 + 1 = 3
     *
     * which points to SUM(K) in the RCL.  Thus an addedColumnOffset
     * value of "1" resolves to column SUM(K) in target's RCL; similarly,
     * an addedColumnOffset value of "0" resolves to "K". DERBY-3303.
     */
    private void resolveAddedColumn(ResultSetNode target)
    {
        ResultColumnList targetCols = target.getResultColumns();
        columnPosition = targetCols.visibleSize() + addedColumnOffset + 1;
        resultCol = targetCols.getResultColumn( columnPosition);
    }

    /**
     * Pull up this orderby column if it doesn't appear in the resultset
     *
     * @param target    The result set being selected from
     *
     */
    public void pullUpOrderByColumn(ResultSetNode target)
                throws StandardException
    {
        ResultColumnList targetCols = target.getResultColumns();

        if(expression instanceof ColumnReference){

            ColumnReference cr = (ColumnReference) expression;

            resultCol = targetCols.findResultColumnForOrderBy(
                    cr.getColumnName(), cr.getTableNameNode());

            if(resultCol == null){
                resultCol = new ResultColumn(cr.getColumnName(), cr, getContextManager());
                resultCol.markAsPulledupOrderingColumn();
                targetCols.addResultColumn(resultCol);
                addedColumnOffset = targetCols.getOrderBySelect();
                targetCols.incOrderBySelect();
            }

        }else if(!isReferedColByNum(expression)){
            resultCol = new ResultColumn((String) null, expression, getContextManager());
            targetCols.addResultColumn(resultCol);
            addedColumnOffset = targetCols.getOrderBySelect();
            targetCols.incOrderBySelect();
        }
    }

    /**
     * Order by columns now point to the PRN above the node of interest.
     * We need them to point to the RCL under that one.  This is useful
     * when combining sorts where we need to reorder the sorting
     * columns.
     */
    void resetToSourceRC()
    {
        if (SanityManager.DEBUG)
        {
            if (! (resultCol.getExpression() instanceof VirtualColumnNode))
            {
                SanityManager.THROWASSERT(
                    "resultCol.getExpression() expected to be instanceof VirtualColumnNode " +
                    ", not " + resultCol.getExpression().getClass().getName());
            }
        }

        resultCol = resultCol.getExpression().getSourceResultColumn();
    }

    /**
     * Is this OrderByColumn constant, according to the given predicate list?
     * A constant column is one where all the column references it uses are
     * compared equal to constants.
     */
    boolean constantColumn(PredicateList whereClause)
    {
        ValueNode sourceExpr = resultCol.getExpression();

        return sourceExpr.constantExpression(whereClause);
    }

    /**
     * Remap all the column references under this OrderByColumn to their
     * expressions.
     *
     * @exception StandardException        Thrown on error
     */
    void remapColumnReferencesToExpressions() throws StandardException
    {
        resultCol.setExpression(
            resultCol.getExpression().remapColumnReferencesToExpressions());
    }

    public static boolean isReferedColByNum(ValueNode expression)
    throws StandardException {

        return expression.isConstantExpression() && expression instanceof NumericConstantNode && expression.getConstantValueAsObject() instanceof Integer;

    }


    private ResultColumn resolveColumnReference(ResultSetNode target,
                               ColumnReference cr)
    throws StandardException{

        ResultColumn resultCol = null;

        int                    sourceTableNumber = -1;

        //bug 5716 - for db2 compatibility - no qualified names allowed in order by clause when union/union all operator is used

        if (target instanceof SetOperatorNode && cr.getTableName() != null){
            String fullName = cr.getSQLColumnName();
            throw StandardException.newException(SQLState.LANG_QUALIFIED_COLUMN_NAME_NOT_ALLOWED, fullName);
        }

        if(cr.getTableNameNode() != null){
            TableName tableNameNode = cr.getTableNameNode();

            FromTable fromTable = target.getFromTableByName(tableNameNode.getTableName(),
                                    (tableNameNode.hasSchema() ?
                                     tableNameNode.getSchemaName():null),
                                    true);
            if(fromTable == null){
                fromTable = target.getFromTableByName(tableNameNode.getTableName(),
                                      (tableNameNode.hasSchema() ?
                                       tableNameNode.getSchemaName():null),
                                      false);
                if(fromTable == null){
                    String fullName = cr.getTableNameNode().toString();
                    throw StandardException.newException(SQLState.LANG_EXPOSED_NAME_NOT_FOUND, fullName);
                }
            }

            /* HACK - if the target is a UnionNode, then we have to
             * have special code to get the sourceTableNumber.  This is
             * because of the gyrations we go to with building the RCLs
             * for a UnionNode.
             */
            if (target instanceof SetOperatorNode)
            {
                sourceTableNumber = ((FromTable) target).getTableNumber();
            }
            else
            {
                sourceTableNumber = fromTable.getTableNumber();
            }

        }

        ResultColumnList    targetCols = target.getResultColumns();

        resultCol = targetCols.getOrderByColumnToBind(cr.getColumnName(),
                            cr.getTableNameNode(),
                            sourceTableNumber,
                            this);
        /* Search targetCols before using addedColumnOffset because select list wildcards, '*',
         * are expanded after pullUpOrderByColumn is called. A simple column reference in the
         * order by clause may be found in the user specified select list now even though it was
         * not found when pullUpOrderByColumn was called.
         */
        if( resultCol == null && addedColumnOffset >= 0)
            resolveAddedColumn(target);

        if (resultCol == null || resultCol.isNameGenerated()){
            String errString = cr.columnName;
            throw StandardException.newException(SQLState.LANG_ORDER_BY_COLUMN_NOT_FOUND, errString);
        }

        return resultCol;

    }

    /**
     * Reset addedColumnOffset to indicate that column is no longer added
     *
     * An added column is one which was artificially added to the result
     * column list due to its presence in the ORDER BY clause, as opposed to
     * having been explicitly selected by the user. Since * is not expanded
     * until after the ORDER BY columns have been pulled up, we may add a
     * column, then later decide it is a duplicate of an explicitly selected
     * column. In that case, this method is called, and it does the following:
     * - resets addedColumnOffset to -1 to indicate this is not an added col
     * - calls back to the OrderByList to adjust any other added cols
     */
    void clearAddedColumnOffset()
    {
        list.closeGap(addedColumnOffset);
        addedColumnOffset = -1;
    }
    /**
     * Adjust addedColumnOffset to reflect that a column has been removed
     *
     * This routine is called when a previously-added result column has been
     * removed due to being detected as a duplicate. If that added column had
     * a lower offset than our column, we decrement our offset to reflect that
     * we have just been moved down one slot in the result column list.
     *
     * @param gap   offset of the column which has just been removed from list
     */
    void collapseAddedColumnGap(int gap)
    {
        if (addedColumnOffset > gap)
            addedColumnOffset--;
    }


    /**
     * Accept the visitor for all visitable children of this node.
     *
     * @param v the visitor
     */
    @Override
    public void acceptChildren(Visitor v) throws StandardException {
        super.acceptChildren(v);
        if (expression != null) {
            expression = (ValueNode)expression.accept(v, this);
        }
    }

}
