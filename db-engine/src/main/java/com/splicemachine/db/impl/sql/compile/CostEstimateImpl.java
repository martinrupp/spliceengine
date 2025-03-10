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

import com.splicemachine.db.iapi.sql.compile.CostEstimate;
import com.splicemachine.db.iapi.sql.compile.OptimizablePredicateList;
import com.splicemachine.db.iapi.sql.compile.Optimizer;
import com.splicemachine.db.iapi.sql.compile.RowOrdering;
import com.splicemachine.db.iapi.store.access.StoreCostResult;
import com.splicemachine.db.iapi.services.sanity.SanityManager;

public class CostEstimateImpl implements CostEstimate {
    public double	cost;
    public double	rowCount;
    public double	singleScanRowCount;
    public int joinType;
    public boolean isAntiJoin;

    private boolean singleRow = false;

    public CostEstimateImpl() {
    }

    public CostEstimateImpl(double theCost,
                            double theRowCount,
                            double theSingleScanRowCount) {
        if (SanityManager.DEBUG)
        {
            if (theCost < 0.0 ||
                    theRowCount < 0.0 ||
                    theSingleScanRowCount < 0.0)
            {
                SanityManager.THROWASSERT(
                        "All parameters expected to be < 0.0, " +
                                "\n\ttheCost = " + theCost +
                                "\n\ttheRowCount = " + theRowCount +
                                "\n\ttheSingleScanRowCount = " + theSingleScanRowCount
                );
            }
        }
        this.cost = theCost;
        this.rowCount = theRowCount;
        this.singleScanRowCount = theSingleScanRowCount;
    }

    @Override
    public void setCost(double cost, double rowCount, double singleScanRowCount) {
        setCost(cost,rowCount,singleScanRowCount,0,0);
    }

    /** @see CostEstimate#setCost */
    @Override
    public void setCost(double cost, double rowCount,
                        double singleScanRowCount, int numPartitions, int parallelism) {
        if (SanityManager.DEBUG) {
            if (cost < 0.0 ||
                    rowCount < 0.0 ||
                    singleScanRowCount < 0.0)
            {
                SanityManager.THROWASSERT(
                        "All parameters expected to be >= 0, " +
                                "\n\tcost = " + cost +
                                "\n\trowCount = " + rowCount +
                                "\n\tsingleScanRowCount = " + singleScanRowCount
                );
            }
        }
        this.cost = cost;
        this.rowCount = rowCount;
        this.singleScanRowCount = singleScanRowCount;
    }

    @Override public void setRemoteCost(double remoteCost){  }

    @Override public void setLocalCost(double remoteCost){ this.cost = remoteCost; }

    @Override public double remoteCost(){ return 0d; }

    @Override public double localCost(){ return cost; }

    @Override public void setEstimatedHeapSize(long estHeapBytes){ throw new UnsupportedOperationException(); }
    @Override public long getEstimatedHeapSize(){ throw new UnsupportedOperationException(); }

    //no-op
    @Override public void setNumPartitions(int numPartitions) {  }

    //no-op
    @Override public void setFirstColumnStats(FirstColumnOfIndexStats firstColumnStats) {  }

    @Override public FirstColumnOfIndexStats getFirstColumnStats() { return null; }

    //no-op
    @Override public void setParallelism(int numparallelTasks) {  }

    @Override public int getParallelism() { return 1; }

    //derby always scans a single partition
    @Override public int partitionCount() { return 1; }

    /** @see CostEstimate#setCost */
    public void setCost(CostEstimate other) {
        cost = other.localCost();
        rowCount = other.rowCount();
        singleScanRowCount = other.singleScanRowCount();
    }

    /** @see CostEstimate#setSingleScanRowCount */
    public void setSingleScanRowCount(double singleScanRowCount) {
        if (SanityManager.DEBUG) {
            if (singleScanRowCount < 0.0) {
                SanityManager.THROWASSERT("All parameters expected to be < 0.0, \n\tsingleScanRowCount = " + singleScanRowCount);
            }
        }
        this.singleScanRowCount = singleScanRowCount;
    }

    /** @see CostEstimate#compare */
    public double compare(CostEstimate other) {
        if (SanityManager.DEBUG) {
            if (other == null) {
                SanityManager.THROWASSERT("Comparing with null CostEstimate");
            }

            if ( ! (other instanceof CostEstimateImpl)) {
                SanityManager.THROWASSERT(other.getClass().getName());
            }
        }

		/* Note: if both CostEstimates are infinity, an attempt to
		 * substract them will result in NaN, which tells us nothing
		 * and thus makes it impossible to do a comparison.  So in
		 * that case we fallback and check the row counts as a secondary
		 * point of comparison, and the singleScanRowCounts as a
		 * third comparison.  If all three values are infinity
		 * for both CostEstimates then we just consider the two
		 * costs to equal (equally as bad?) and so return 0.0d (instead
		 * NaN).  RESOLVE: Ideally the optimizer could be updated
		 * to give more reasonable estimates than infinity, but
		 * until that happens we're forced to deal with such
		 * comparisons.  Note that we're most likely to end up with
		 * infinite cost estimates in situations where we have deeply
		 * nested subqueries and/or FROM lists with a large number of
		 * FromTables (such as 10 or more). The reason is that each
		 * FromTable's cost estimate is (potentially) multiplied by
		 * the row counts of all preceding FromTables, so if the
		 * row counts for the preceding FromTables are large, we
		 * can eventually end up going beyond Double.MAX_VALUE,
		 * which then gives us infinity.
		 */

        // If at least one of costs is _not_ infinity, then just do
        // a normal compare (the other side is less).
        if ((this.cost != Double.POSITIVE_INFINITY) ||
                (other.getEstimatedCost() != Double.POSITIVE_INFINITY))
        {
            return this.cost - ((CostEstimateImpl) other).cost;
        }

        // If both costs are infinity, then compare row counts.
        if ((this.rowCount != Double.POSITIVE_INFINITY) ||
                (other.rowCount() != Double.POSITIVE_INFINITY))
        {
            return this.rowCount - other.rowCount();
        }

        // If both row counts are infinity, try singleScan counts.
        if ((this.singleScanRowCount != Double.POSITIVE_INFINITY) ||
                (other.singleScanRowCount() != Double.POSITIVE_INFINITY))
        {
            return this.singleScanRowCount - other.singleScanRowCount();
        }

        // If we get here, all three parts of both cost estimates are
        // Infinity; for lack of better choice, just say they're "equal".
        return 0.0d;
    }

    public double compareLocal(CostEstimate other) {
        return compare(other);
    }

    /** @see CostEstimate#add */
    public CostEstimate add(CostEstimate other, CostEstimate retval) {
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(other instanceof CostEstimateImpl);
            SanityManager.ASSERT(retval == null ||
                    retval instanceof CostEstimateImpl);
        }

        CostEstimateImpl	addend = (CostEstimateImpl) other;

        double sumCost = this.cost + addend.cost;
        double sumRowCount = this.rowCount + addend.rowCount;
        if (SanityManager.DEBUG)
        {
            if (sumCost < 0.0 ||
                    sumRowCount < 0.0)
            {
                SanityManager.THROWASSERT(
                        "All sums expected to be < 0.0, " +
                                "\n\tthis.cost = " + this.cost +
                                "\n\taddend.cost = " + addend.cost +
                                "\n\tsumCost = " + sumCost +
                                "\n\tthis.rowCount = " + this.rowCount +
                                "\n\taddend.rowCount = " + addend.rowCount +
                                "\n\tsumRowCount = " + sumRowCount
                );
            }
        }

		/* Presume that ordering is not maintained */
        return setState(sumCost,
                sumRowCount,
                (CostEstimateImpl) retval);
    }

    /** @see CostEstimate#multiply */
    public CostEstimate multiply(double multiplicand, CostEstimate retval) {
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(retval == null ||
                    retval instanceof CostEstimateImpl);
        }

        double multCost = this.cost * multiplicand;
        double multRowCount = this.rowCount * multiplicand;

        if (SanityManager.DEBUG)
        {
            if (multCost < 0.0 ||
                    multRowCount < 0.0)
            {
                SanityManager.THROWASSERT(
                        "All products expected to be < 0.0, " +
                                "\n\tthis.cost = " + this.cost +
                                "\n\tmultiplicand = " + multiplicand +
                                "\n\tmultCost = " + multCost +
                                "\n\tthis.rowCount = " + this.rowCount +
                                "\n\tmultRowCount = " + multRowCount
                );
            }
        }

		/* Presume that ordering is not maintained */
        return setState(multCost,
                multRowCount,
                (CostEstimateImpl) retval);
    }

    /** @see CostEstimate#divide */
    public CostEstimate divide(double divisor, CostEstimate retval) {
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(retval == null ||
                    retval instanceof CostEstimateImpl);
        }

        double divCost = this.cost / divisor;
        double divRowCount = this.rowCount / divisor;

        if (SanityManager.DEBUG)
        {
            if (divCost < 0.0 ||
                    divRowCount < 0.0)
            {
                SanityManager.THROWASSERT(
                        "All products expected to be < 0.0, " +
                                "\n\tthis.cost = " + this.cost +
                                "\n\tdivisor = " + divisor +
                                "\n\tdivCost = " + divCost +
                                "\n\tthis.rowCount = " + this.rowCount +
                                "\n\tdivRowCount = " + divRowCount
                );
            }
        }

		/* Presume that ordering is not maintained */
        return setState(divCost,
                divRowCount,
                (CostEstimateImpl) retval);
    }

    /** @see CostEstimate#rowCount */
    public double rowCount() {
        return rowCount;
    }

    /** @see CostEstimate#singleScanRowCount */
    public double singleScanRowCount() {
        return singleScanRowCount;
    }

    /** @see CostEstimate#cloneMe */
    public CostEstimate cloneMe() {
        CostEstimate costEstimate =
               new CostEstimateImpl(cost,
                rowCount,
                singleScanRowCount);
        costEstimate.setSingleRow(isSingleRow());
        return costEstimate;
    }

    /** @see CostEstimate#isUninitialized */
    public boolean isUninitialized()
    {
        return (cost == Double.MAX_VALUE &&
                rowCount == Double.MAX_VALUE &&
                singleScanRowCount == Double.MAX_VALUE);
    }

    /** @see StoreCostResult#getEstimatedCost */
    public double getEstimatedCost() {
        return cost+remoteCost();
    }

    /** @see StoreCostResult#setEstimatedCost */
    public void setEstimatedCost(double cost) {
        this.cost = cost;
    }

    /** @see StoreCostResult#getEstimatedRowCount */
    public long getEstimatedRowCount() {
        return (long) rowCount;
    }

    /** @see StoreCostResult#setEstimatedRowCount */
    public void setEstimatedRowCount(long count) {
		/* This method is called by the store to
		 * give us the estimate for the # of rows
		 * returned in a scan.  So, we set both
		 * rowCount and singleScanRowCount here.
		 */
        rowCount = (double) count;
        singleScanRowCount = (double) count;
    }

    public CostEstimateImpl setState(double theCost,
                                     double theRowCount,
                                     CostEstimateImpl retval) {
        if (retval == null) {
            retval = new CostEstimateImpl();
        }

        retval.cost = theCost;
        retval.rowCount = theRowCount;

        return retval;
    }

    public void setRowOrdering(RowOrdering rowOrdering) {
        throw new RuntimeException("Not Supported");
    }

    public OptimizablePredicateList getPredicateList() {
        throw new RuntimeException("Not Supported");
    }

    public void setPredicateList(OptimizablePredicateList predicateList) {
        throw new RuntimeException("Not Supported");
    }

    public RowOrdering getRowOrdering() {
        throw new RuntimeException("Not Supported");
    }
    public CostEstimate getBase() {
        return this;
    }

    public void setBase(CostEstimate baseCost) {
        throw new RuntimeException("Not Supported");
    }

    @Override
    public boolean isRealCost(){
        return true;
    }

    @Override
    public void setIsRealCost(boolean isRealCost){
        //no-op
    }

    @Override public double getOpenCost(){ throw new UnsupportedOperationException(); }
    @Override public void setOpenCost(double openCost){ throw new UnsupportedOperationException();  }
    @Override public void setCloseCost(double closeCost){ throw new UnsupportedOperationException(); }

    @Override
    public void setRowCount(double rowCount){
        this.rowCount = rowCount;
    }

    @Override
    public String prettyProcessingString(){
        return toString();
    }

    @Override
    public String prettyProcessingString(String attrDelim){
        return toString();
    }

    @Override
    public String prettyScrollInsensitiveString() {
        return toString();
    }

    @Override
    public String prettyScrollInsensitiveString(String attrDelim) {
        return toString();
    }

    @Override
    public String prettyFromBaseTableString() {
        return toString();
    }

    @Override
    public String prettyFromBaseTableString(String attrDelim) {
        return toString();
    }

    @Override
    public String prettyIndexLookupString() {
        return toString();
    }

    @Override
    public String prettyIndexLookupString(String attrDelim) {
        return toString();
    }

    @Override
    public String prettyProjectionString() {
        return toString();
    }

    @Override
    public String prettyProjectionString(String attrDelim) {
        return toString();
    }

    @Override
    public String prettyDmlStmtString(String rowsLabel) {
        return toString();
    }

    @Override
    public String prettyDmlStmtString(double cost, long rows, String attrDelim, String rowsLabel) {
        return toString();
    }

    @Override public double getCloseCost(){ throw new UnsupportedOperationException(); }

    /**
     *
     * get joinType so that we can take this into account for JoinSelectivity calculation.
     *
     * @return
     */
    @Override
    public int getJoinType() {
        return joinType;
    }

    @Override
    public boolean isOuterJoin() {
        return joinType == JoinNode.LEFTOUTERJOIN || joinType == JoinNode.FULLOUTERJOIN;
    }
    /**
     *
     * Boolean flag that is set so we can take this into account for JoinSelectivity calculation.
     *
     * @return
     * @param joinType
     */
    @Override
    public void setJoinType(int joinType) {
        this.joinType = joinType;
    }

    @Override
    public boolean isAntiJoin() {
        return isAntiJoin;
    }

    @Override
    public void setAntiJoin(boolean isAntiJoin) {
        this.isAntiJoin = isAntiJoin;
    }

    @Override
    public void setFromBaseTableCost(double fromBaseTableCost) {

    }

    @Override
    public double getProjectionRows() {
        return 0;
    }

    @Override
    public void setProjectionRows(double projectionRows) {

    }

    @Override
    public double getProjectionCost() {
        return 0;
    }

    @Override
    public void setProjectionCost(double projectionCost) {

    }

    @Override
    public double getIndexLookupRows() {
        return 0;
    }

    @Override
    public void setIndexLookupRows(double indexLookupRows) {

    }

    @Override
    public double getIndexLookupCost() {
        return 0;
    }

    @Override
    public void setIndexLookupCost(double indexLookupCost) {

    }

    @Override
    public double getFromBaseTableRows() {
        return 0;
    }

    @Override
    public void setFromBaseTableRows(double fromBaseTableRows) {

    }

    @Override
    public double getScannedBaseTableRows() {
        return 0;
    }

    @Override
    public void setScannedBaseTableRows(double scannedBaseTableRows) {

    }

    @Override
    public double getFromBaseTableCost() {
        return 0;
    }

    @Override
    public double getLocalCost() {
        return 0;
    }

    @Override
    public double getRemoteCost() {
        return 0;
    }

    @Override
    public void setLocalCostPerParallelTask(double remoteCost) { }

    @Override
    public void setLocalCostPerParallelTask(double localCost, int parallelism) { }

    @Override
    public void setRemoteCostPerParallelTask(double remoteCost, int parallelism) { }

    @Override
    public double getLocalCostPerParallelTask() { return 0; }

    @Override
    public double getRemoteCostPerParallelTask() { return 0; }

    @Override
    public void setRemoteCostPerParallelTask(double remoteCost) { }

    @Override
    public double getAccumulatedMemory() {
        return 0.0d;
    }

    @Override
    public void setAccumulatedMemory(double memorySize) { }

    public boolean isSingleRow() {return singleRow;}

    public void setSingleRow(boolean singleRowInRelation) { singleRow = singleRowInRelation;}

    // No-op
    @Override
    public void setOptimizer(Optimizer optimizer) { }

    @Override
    public Optimizer getOptimizer() { return null; }
}
