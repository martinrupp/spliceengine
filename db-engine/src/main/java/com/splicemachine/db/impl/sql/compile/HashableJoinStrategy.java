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
import com.splicemachine.db.iapi.services.cache.ClassSize;
import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.compile.*;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.sql.dictionary.IndexRowGenerator;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.util.JBitSet;

import java.util.Vector;

/**
 * User: pjt
 * Date: 6/10/13
 */
public abstract class HashableJoinStrategy extends BaseJoinStrategy {

    public HashableJoinStrategy() {
    }

    @Override
    public boolean feasible(Optimizable innerTable,
                            OptimizablePredicateList predList,
                            Optimizer optimizer,
                            CostEstimate outerCost,
                            boolean wasHinted,
                            boolean skipKeyCheck) throws StandardException {
        if (optimizer.getJoinPosition() > 0 && innerTable.outerTableOnly())
            return false;
        int[] hashKeyColumns;
        ConglomerateDescriptor cd = null;
        OptimizerTrace tracer = optimizer.tracer();
        boolean foundUnPushedJoinPred = false;
        AccessPath ap = innerTable.getCurrentAccessPath();
        ap.setMissingHashKeyOK(false);

		/* If the innerTable is a VTI, then we must check to see if there are any
		 * join columns in the VTI's parameters.  If so, then hash join is not feasible.
		 */
        /*
        if (! innerTable.isMaterializable()) {
            tracer.trace(OptimizerFlag.HJ_SKIP_NOT_MATERIALIZABLE,0,0,0.0,null);
            return false;
        }
        */

		/* If the predicate given by the user _directly_ references
		 * any of the base tables _beneath_ this node, then we
		 * cannot safely use the predicate for a hash because the
		 * predicate correlates two nodes at different nesting levels.
		 * If we did a hash join in this case, materialization of
		 * innerTable could lead to incorrect results--and in particular,
		 * results that are missing rows.  We can check for this by
		 * looking at the predicates' reference maps, which are set based
		 * on the initial query (as part of pre-processing).  Note that
		 * by the time we get here, it's possible that a predicate's
		 * reference map holds table numbers that do not agree with the
		 * table numbers of the column references used by the predicate.
		 * That's okay--this occurs as a result of "remapping" predicates
		 * that have been pushed down the query tree.  And in fact
		 * it's a good thing because, by looking at the column reference's
		 * own table numbers instead of the predicate's referenced map,
		 * we are more readily able to find equijoin predicates that
		 * we otherwise would not have found.
		 *
		 * Note: do not perform this check if innerTable is a FromBaseTable
		 * because a base table does not have a "subtree" to speak of.
		 */
        /*
        if ((predList != null) && (predList.size() > 0) && !(innerTable instanceof FromBaseTable)) {
            FromTable ft = (FromTable)innerTable;
            // First get a list of all of the base tables in the subtree
            // below innerTable.
            JBitSet tNums = new JBitSet(ft.getReferencedTableMap().size());
            BaseTableNumbersVisitor btnVis = new BaseTableNumbersVisitor(tNums);
            ft.accept(btnVis);

            // Now get a list of all table numbers referenced by the
            // join predicates that we'll be searching.
            JBitSet pNums = new JBitSet(tNums.size());
            Predicate pred = null;
            for (int i = 0; i < predList.size(); i++) {
                pred = (Predicate)predList.getOptPredicate(i);
                if (pred.isJoinPredicate())
                    pNums.or(pred.getReferencedSet());
            }

            // If tNums and pNums have anything in common, then at
            // least one predicate in the list refers directly to
            // a base table beneath this node (as opposed to referring
            // just to this node), which means it's not safe to do a
            // hash join.
            tNums.and(pNums);
            if (tNums.getFirstSetBit() != -1) {
                return false;
            }
        }
        */
        if (innerTable.isBaseTable()) {
            /* Must have an equijoin on a column in the conglomerate */
            cd = innerTable.getCurrentAccessPath().getConglomerateDescriptor();
        }

        /* Look for equijoins in the predicate list */
        hashKeyColumns = findHashKeyColumns(innerTable, cd, predList, optimizer.getAssignedTableMap());

        if (SanityManager.DEBUG) {
            if (hashKeyColumns == null) {
                if (skipKeyCheck)
                    tracer.trace(OptimizerFlag.HJ_NO_EQUIJOIN_COLUMNS, 0, 0, 0.0, null);
                else
                    tracer.trace(OptimizerFlag.HJ_SKIP_NO_JOIN_COLUMNS, 0, 0, 0.0, null);
            } else {
                tracer.trace(OptimizerFlag.HJ_HASH_KEY_COLUMNS, 0, 0, 0.0, hashKeyColumns);
            }
        }
        // Allow inequality join if we're skipping the key check and this is
        // truly a join (we have a predList with join terms).
        // Also don't allow a VALUES list, eg.,
        //       select * from (values ('a'), ('b') ... ('z')) mytab,
        // constructed with UnionNodes and RowResultSetNodes, to be misconstrued as a join.
        // The same conditions as in NestedLoopJoinStrategy.feasible are added for safety.
        // Could these be removed in the future?
        if (hashKeyColumns == null && skipKeyCheck) {
            // For full outer join, broadcast join is the default join strategy that can be applied
            // to any kinds of join predicate, so make the eligibility check more general
            if ((outerCost.getJoinType() == JoinNode.FULLOUTERJOIN ||
                 outerCost.getJoinType() == JoinNode.LEFTOUTERJOIN) &&
                (innerTable.isMaterializable() ||
                 innerTable.supportsMultipleInstantiations())) {
                ap.setMissingHashKeyOK(true);
                return true;
            }

            if ((innerTable.isMaterializable() ||
                 innerTable.supportsMultipleInstantiations())                 &&
                optimizer instanceof OptimizerImpl                            &&
                !(innerTable instanceof RowResultSetNode)                     &&
                !(innerTable instanceof SetOperatorNode)) {

                // Consider BroadcastNestedLoopJoin for anything other
                // than single-table scan.
                if (!isSingleTableScan(optimizer)) {
                    ap.setMissingHashKeyOK(true);
                    return true;
                }
            }
            else
                ap.setMissingHashKeyOK(false);
        }

        return hashKeyColumns!=null;
    }

    /** @see com.splicemachine.db.iapi.sql.compile.JoinStrategy#ignoreBulkFetch */
    public boolean ignoreBulkFetch() {
        return true;
    }

    /** @see com.splicemachine.db.iapi.sql.compile.JoinStrategy#multiplyBaseCostByOuterRows */
    public boolean multiplyBaseCostByOuterRows() {
        return false;
    }

    /**
     * @see com.splicemachine.db.iapi.sql.compile.JoinStrategy#getBasePredicates
     *
     * @exception StandardException		Thrown on error
     */
    public OptimizablePredicateList getBasePredicates(OptimizablePredicateList predList,OptimizablePredicateList basePredicates,
                                                      Optimizable innerTable) throws StandardException {

        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(basePredicates.size() == 0,"The base predicate list should be empty.");
        }

        if (predList != null) {
            predList.transferAllPredicates(basePredicates);
            basePredicates.classify(innerTable, innerTable.getCurrentAccessPath(), false);
        }

        /*
         * We want all the join predicates to be included, so we just pass everything through and filter
         * it out through the actual costing algorithm
         */
        return basePredicates;
//        for (int i = predList.size() - 1; i >= 0; i--) {
//            OptimizablePredicate pred = predList.getOptPredicate(i);
//            if (innerTable.getReferencedTableMap().contains(pred.getReferencedMap())) {
//                basePredicates.addOptPredicate(pred);
//                predList.removeOptPredicate(i);
//            }
//        }
//        basePredicates.classify(innerTable,innerTable.getCurrentAccessPath().getConglomerateDescriptor());
//        return basePredicates;
    }

    /** @see com.splicemachine.db.iapi.sql.compile.JoinStrategy#nonBasePredicateSelectivity */
    public double nonBasePredicateSelectivity(Optimizable innerTable,OptimizablePredicateList predList) throws StandardException {
        double retval = 1.0;
        if (predList != null) {
            for (int i = 0; i < predList.size(); i++) {
                // Don't include redundant join predicates in selectivity calculations
                if (predList.isRedundantPredicate(i)) {
                    continue;
                }
                retval *= predList.getOptPredicate(i).selectivity(innerTable);
            }
        }
        return retval;
    }

    /**
     * @see com.splicemachine.db.iapi.sql.compile.JoinStrategy#putBasePredicates
     *
     * @exception StandardException		Thrown on error
     */
    public void putBasePredicates(OptimizablePredicateList predList,OptimizablePredicateList basePredicates) throws StandardException {
        for (int i = basePredicates.size() - 1; i >= 0; i--) {
            OptimizablePredicate pred = basePredicates.getOptPredicate(i);
            predList.addOptPredicate(pred);
            basePredicates.removeOptPredicate(i);
        }
    }

    /** @see JoinStrategy#maxCapacity */
    public int maxCapacity( int userSpecifiedCapacity, int maxMemoryPerTable, double perRowUsage) {
        if( userSpecifiedCapacity >= 0)
            return userSpecifiedCapacity;
        perRowUsage += ClassSize.estimateHashEntrySize();
        if( perRowUsage <= 1)
            return maxMemoryPerTable;
        return (int)(maxMemoryPerTable/perRowUsage);
    }

    /**
     * @see JoinStrategy#getScanArgs
     *
     * @exception StandardException		Thrown on error
     */
    @Override
    public int getScanArgs(
            TransactionController tc,
            MethodBuilder mb,
            Optimizable innerTable,
            OptimizablePredicateList storeRestrictionList,
            OptimizablePredicateList nonStoreRestrictionList,
            ExpressionClassBuilderInterface acbi,
            int bulkFetch,
            MethodBuilder resultRowAllocator,
            int colRefItem,
            int indexColItem,
            int lockMode,
            boolean tableLocked,
            int isolationLevel,
            int maxMemoryPerTable,
            boolean genInListVals, String tableVersion,
            int splits,
            String delimited,
            String escaped,
            String lines,
            String storedAs,
            String location,
            int partitionRefItem
    ) throws StandardException {
        ExpressionClassBuilder acb = (ExpressionClassBuilder) acbi;
        int numArgs;
		/* If we're going to generate a list of IN-values for index probing
		 * at execution time then we push TableScanResultSet arguments plus
		 * four additional arguments: 1) the list of IN-list values, and 2)
		 * a boolean indicating whether or not the IN-list values are already
		 * sorted, 3) the in-list column position in the index or primary key,
		 * 4) array of types of the in-list columns.
		 */
        if (genInListVals) {
            numArgs = 38;
        }
        else {
            numArgs = 34 ;
        }
        // Splice: our Hashable joins (MSJ, Broadcast) don't have a notion of store vs. non-store
        // filters, so include any nonStoreRestrictions in the storeRestrictionList
        for (int i = 0, size = nonStoreRestrictionList.size() ; i < size ; i++) {
           storeRestrictionList.addOptPredicate(nonStoreRestrictionList.getOptPredicate(i));
        }
        fillInScanArgs1(tc, mb, innerTable, storeRestrictionList, acb, resultRowAllocator);
        if (genInListVals)
            ((PredicateList)storeRestrictionList).generateInListValues(acb, mb);

        if (SanityManager.DEBUG) {
			/* If we're not generating IN-list values with which to probe
			 * the table then storeRestrictionList should not have any
			 * IN-list probing predicates.  Make sure that's the case.
			 */
            if (!genInListVals) {
                Predicate pred = null;
                for (int i = storeRestrictionList.size() - 1; i >= 0; i--) {
                    pred = (Predicate)storeRestrictionList.getOptPredicate(i);
                    if (pred.isInListProbePredicate()) {
                        SanityManager.THROWASSERT("Found IN-list probing " +
                                "predicate (" + pred.binaryRelOpColRefsToString() +
                                ") when no such predicates were expected.");
                    }
                }
            }
        }

        fillInScanArgs2(mb,innerTable, bulkFetch, colRefItem, indexColItem, lockMode, tableLocked, isolationLevel,tableVersion,
                splits, delimited, escaped, lines, storedAs, location, partitionRefItem);
        return numArgs;
    }

    /**
     * @see JoinStrategy#divideUpPredicateLists
     *
     * @exception StandardException		Thrown on error
     */
    public void divideUpPredicateLists(
            Optimizable innerTable,
            JBitSet joinedTableSet,
            OptimizablePredicateList originalRestrictionList,
            OptimizablePredicateList storeRestrictionList,
            OptimizablePredicateList nonStoreRestrictionList,
            OptimizablePredicateList requalificationRestrictionList,
            DataDictionary dd
    ) throws StandardException {
		/*
		** If we are walking a non-covering index, then all predicates that
		** get evaluated in the HashScanResultSet, whether during the building
		** or probing of the hash table, need to be evaluated at both the
		** IndexRowToBaseRowResultSet and the HashScanResultSet to ensure
		** that the rows materialized into the hash table still qualify when
		** we go to read the row from the heap.  This also includes predicates
        ** that are not qualifier/start/stop keys (hence not in store/non-store
        ** list).
		*/
        originalRestrictionList.copyPredicatesToOtherList(requalificationRestrictionList);
        ConglomerateDescriptor cd = innerTable.getTrulyTheBestAccessPath().getConglomerateDescriptor();

		/* For the inner table of a hash join, then divide up the predicates:
         *
		 *	o restrictionList	- predicates that get applied when creating
		 *						  the hash table (single table clauses)
         *
		 *  o nonBaseTableRestrictionList
		 *						- those that get applied when probing into the
		 *						  hash table (equijoin clauses on key columns,
		 *						  ordered by key column position first, followed
		 *						  by any other join predicates. (All predicates
         *						  in this list are qualifiers which can be
         *						  evaluated in the store).
         *
		 *  o baseTableRL		- Only applicable if this is not a covering
         *                        index.  In that case, we will need to
         *                        requalify the data page.  Thus, this list
         *                        will include all predicates.
		 */

        // Build the list to be applied when creating the hash table
        originalRestrictionList.transferPredicates(storeRestrictionList, innerTable.getReferencedTableMap(), innerTable, joinedTableSet);
		/*
         * Eliminate any non-qualifiers that may have been pushed, but
         * are redundant and not useful for hash join.
         *
         * For instance "in" (or other non-qualifier) was pushed down for
         * start/stop key, * but for hash join, it may no longer be because
         * previous key column may have been disqualified (eg., correlation).
         * We simply remove
         * such non-qualifier ("in") because we left it as residual predicate
         * anyway.  It's easier/safer to filter it out here than detect it
         * ealier (and not push it down). Beetle 4316.
         *
         * Can't filter out OR list, as it is not a residual predicate,
		 */
        for (int i = storeRestrictionList.size() - 1; i >= 0; i--) {
            Predicate p1 = (Predicate) storeRestrictionList.getOptPredicate(i);
            if (!p1.isStoreQualifier() && !p1.isStartKey() && !p1.isStopKey()) {
                storeRestrictionList.removeOptPredicate(i);
            }
        }

        for (int i = originalRestrictionList.size() - 1; i >= 0; i--) {
            Predicate p1 = (Predicate) originalRestrictionList.getOptPredicate(i);
            if (!p1.isStoreQualifier())
                originalRestrictionList.removeOptPredicate(i);
        }

		/* Copy the rest of the predicates to the non-store list */
        originalRestrictionList.copyPredicatesToOtherList(nonStoreRestrictionList);

		/* If innerTable is ProjectRestrictNode, we need to use its child
		 * to find hash key columns, this is because ProjectRestrictNode may
		 * not have underlying node's every result column as result column,
		 * and the predicate's column reference was bound to the underlying
		 * node's column position.  Also we have to pass in the
	 	 * ProjectRestrictNode rather than the underlying node to this method
		 * because a predicate's referencedTableMap references the table number
		 * of the ProjectRestrictiveNode.  And we need this info to see if
		 * a predicate is in storeRestrictionList that can be pushed down.
		 * Beetle 3458.
		 */
        Optimizable hashTableFor = innerTable;
        if (innerTable instanceof ProjectRestrictNode) {
            ProjectRestrictNode prn = (ProjectRestrictNode) innerTable;
            if (prn.getChildResult() instanceof Optimizable)
                hashTableFor = (Optimizable) (prn.getChildResult());
        }
        int[] hashKeyColumns = findHashKeyColumns(hashTableFor, cd, nonStoreRestrictionList, joinedTableSet);

        if (hashKeyColumns == null) {
            if (!innerTable.getTrulyTheBestAccessPath().isMissingHashKeyOK()) {
                String name;
                if (cd != null && cd.isIndex()) {
                    name = cd.getConglomerateName();
                } else {
                    name = innerTable.getBaseTableName();
                }
                throw StandardException.newException(SQLState.LANG_HASH_NO_EQUIJOIN_FOUND, name, innerTable.getBaseTableName());
            }
            else
                hashKeyColumns = new int[0];  // To designate there is no hash key: inequality join
        }

        innerTable.setHashKeyColumns(hashKeyColumns);

        // Mark all of the predicates in the probe list as qualifiers
        nonStoreRestrictionList.markAllPredicatesQualifiers();

        // The remaining logic deals with hash key columns, so exit if none were found.
        if (hashKeyColumns.length == 0)
            return;

        int[] conglomColumn = new int[hashKeyColumns.length];
        if (cd != null && cd.isIndex()) {
			/*
			** If the conglomerate is a normal index, get the column numbers of the
			** hash keys in the base heap.
			*/
            if (!cd.getIndexDescriptor().isOnExpression()) {
                for (int index = 0; index < hashKeyColumns.length; index++) {
                    conglomColumn[index] =
                            cd.getIndexDescriptor().baseColumnPositions()[hashKeyColumns[index]];
                }
            }
        }
        else {
			/*
			** If the conglomerate is a heap, the column numbers of the hash
			** key are the column numbers returned by findHashKeyColumns().
			**
			** NOTE: Must switch from zero-based to one-based
			*/
            for (int index = 0; index < hashKeyColumns.length; index++)
            {
                conglomColumn[index] = hashKeyColumns[index] + 1;
            }
        }
		/* Put the equality predicates on the key columns for the hash first.
		 */
        if (cd != null && cd.isIndex() && cd.getIndexDescriptor().isOnExpression()) {
            IndexRowGenerator irg = cd.getIndexDescriptor();
            assert innerTable instanceof QueryTreeNode;
            QueryTreeNode inner = (QueryTreeNode) innerTable;

            ValueNode[] exprAsts = irg.getParsedIndexExpressions(inner.getLanguageConnectionContext(), innerTable);
            for (int index = hashKeyColumns.length - 1; index >= 0; index--) {
                nonStoreRestrictionList.putOptimizableEqualityPredicateFirst(innerTable, exprAsts[hashKeyColumns[index]]);
            }
        } else {
            for (int index = hashKeyColumns.length - 1; index >= 0; index--) {
                nonStoreRestrictionList.putOptimizableEqualityPredicateFirst(innerTable, conglomColumn[index]);
            }
        }
    }

    /**
     * @see JoinStrategy#isHashJoin
     */
    public boolean isHashJoin() {
        return false;
    }

    /**
     * @see JoinStrategy#doesMaterialization
     */
    public boolean doesMaterialization() {
        return false;
    }

    /**
     * Find the hash key columns, if any, to use with this join.
     *
     * @param innerTable    The inner table of the join
     * @param cd            The conglomerate descriptor to use on inner table
     * @param predList      The predicate list to look for the equijoin in
     * @param joinedTableSet  The set of table numbers of the optimizables that
     *                        have been joined up to the current join position
     * @return	the numbers of the hash key columns, or null if no hash key column
     *
     * @exception StandardException		Thrown on error
     */
    public int[] findHashKeyColumns(Optimizable innerTable,
                                    ConglomerateDescriptor cd,
                                    OptimizablePredicateList predList,
                                    JBitSet joinedTableSet) throws StandardException {
        if (predList == null)
            return (int[]) null;

		/* Find the column to use as the hash key.
		 * (There must be an equijoin condition on this column.)
		 * If cd is null, then Optimizable is not a scan.
		 * For indexes, we start at the first column in the key
		 * and walk the key columns until we find the first one with
		 * an equijoin condition on it.  We do essentially the same
		 * for heaps.  (From column 1 through column n.)
		 */
        int[] columns = null;
        if (cd == null) {
            columns = new int[innerTable.getNumColumnsReturned()];
            for (int j = 0; j < columns.length; j++) {
                columns[j] = j + 1;
            }
        }
        else if (cd.isIndex()) {
            if (!cd.getIndexDescriptor().isOnExpression()) {
                columns = cd.getIndexDescriptor().baseColumnPositions();
            }
        }
        else {
            columns = new int[innerTable.getTableDescriptor().getNumberOfColumns()];
            for (int j = 0; j < columns.length; j++) {
                columns[j] = j + 1;
            }
        }

        // Build a Vector of all the hash key columns
        Vector hashKeyVector = new Vector();
        if (cd != null && cd.isIndex() && cd.getIndexDescriptor().isOnExpression()) {
            if (innerTable.isCoveringIndex(cd)) {
                // Check for hash keys only for covering IoEs. If not covering, expressions in
                // predicates won't be replaced with column references. Consequently, we could
                // have an expression such as "col1 + col2" as a hash key. Currently, we don't
                // support this kind of hash key because in preparing for execution (fillResultSet),
                // we need to get a row template for a projection. For that we need to invoke
                // the projection generated method. If there contains something more than
                // plain column references like arithmetic ops, it causes an NPE because data
                // is not yet there. In theory, we should be able to support an expression as
                // a hash key. But then we need to tune our code generation logic.
                IndexRowGenerator irg = cd.getIndexDescriptor();
                assert innerTable instanceof QueryTreeNode;
                QueryTreeNode inner = (QueryTreeNode) innerTable;

                ValueNode[] exprAsts = irg.getParsedIndexExpressions(inner.getLanguageConnectionContext(), innerTable);
                for (int colIdx = 0; colIdx < exprAsts.length; colIdx++) {
                    if (predList.hasOptimizableEquijoin(innerTable, exprAsts[colIdx], joinedTableSet)) {
                        hashKeyVector.add(colIdx);
                    }
                }
            }
        } else {
            for (int colCtr = 0; colCtr < columns.length; colCtr++) {
                // Is there an equijoin condition on this column?
                if (predList.hasOptimizableEquijoin(innerTable, columns[colCtr], joinedTableSet)) {
                    hashKeyVector.add(colCtr);
                }
            }
        }

        // Convert the Vector into an int[], if there are hash key columns
        if (!hashKeyVector.isEmpty()) {
            int[] keyCols = new int[hashKeyVector.size()];
            for (int index = 0; index < keyCols.length; index++) {
                keyCols[index] = (Integer) hashKeyVector.get(index);
            }
            return keyCols;
        }
        else
            return (int[]) null;
    }

    /**
     * Can this join strategy be used on the
     * outermost table of a join.
     *
     * @return Whether or not this join strategy
     * can be used on the outermose table of a join.
     */
    protected boolean validForOutermostTable() {
        return true;
    }

    /** @see JoinStrategy#joinResultSetMethodName */
    public abstract String joinResultSetMethodName();

    /** @see JoinStrategy#halfOuterJoinResultSetMethodName */
    public abstract String halfOuterJoinResultSetMethodName();

}
