/*
 * Copyright (c) 2012 - 2020 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.EngineDriver;
import com.splicemachine.access.configuration.SQLConfiguration;
import com.splicemachine.db.iapi.services.io.FormatableIntHolder;
import com.splicemachine.db.iapi.sql.compile.CompilerContext;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.derby.iapi.sql.execute.*;
import com.splicemachine.derby.stream.function.CountJoinedLeftFunction;
import com.splicemachine.derby.stream.function.merge.MergeAntiJoinFlatMapFunction;
import com.splicemachine.derby.stream.function.merge.MergeInnerJoinFlatMapFunction;
import com.splicemachine.derby.stream.function.merge.MergeOuterJoinFlatMapFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.utils.SpliceLogUtils;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.sql.Activation;
import org.apache.log4j.Logger;
import java.io.IOException;
import java.util.*;

import static com.splicemachine.db.iapi.sql.compile.CompilerContext.NewMergeJoinExecutionType.*;

/**
 * @author P Trolard
 *         Date: 18/11/2013
 */
public class MergeJoinOperation extends JoinOperation {
    private static final Logger LOG = Logger.getLogger(MergeJoinOperation.class);
    private int leftHashKeyItem;
    private int rightHashKeyItem;
    private int rightHashKeyToBaseTableMapItem;
    private int rightHashKeySortOrderItem;
    public int[] leftHashKeys;
    public int[] rightHashKeys;
    public int[] rightHashKeyToBaseTableMap;
    public int[] rightHashKeySortOrders;
    private CompilerContext.NewMergeJoinExecutionType newMergeJoin;
    protected boolean oldMergeJoin = false;

    protected static final String NAME = MergeJoinOperation.class.getSimpleName().replaceAll("Operation","");

	@Override
	public String getName() {
			return NAME;
	}

    
    public MergeJoinOperation() {
        super();
    }

    public MergeJoinOperation(SpliceOperation leftResultSet,
                              int leftNumCols,
                              SpliceOperation rightResultSet,
                              int rightNumCols,
                              int leftHashKeyItem,
                              int rightHashKeyItem,
                              int rightHashKeyToBaseTableMapItem,
                              int rightHashKeySortOrderItem,
                              Activation activation,
                              GeneratedMethod restriction,
                              int resultSetNumber,
                              boolean oneRowRightSide,
                              byte semiJoinType,
                              boolean rightFromSSQ,
                              double optimizerEstimatedRowCount,
                              double optimizerEstimatedCost,
                              String userSuppliedOptimizerOverrides,
                              String sparkExpressionTreeAsString,
                              CompilerContext.NewMergeJoinExecutionType newMergeJoin)
            throws StandardException {
        super(leftResultSet, leftNumCols, rightResultSet, rightNumCols,
                 activation, restriction, resultSetNumber, oneRowRightSide,
                semiJoinType, rightFromSSQ, optimizerEstimatedRowCount,
                 optimizerEstimatedCost, userSuppliedOptimizerOverrides, sparkExpressionTreeAsString);
        this.leftHashKeyItem = leftHashKeyItem;
        this.rightHashKeyItem = rightHashKeyItem;
        this.rightHashKeyToBaseTableMapItem = rightHashKeyToBaseTableMapItem;
        this.rightHashKeySortOrderItem = rightHashKeySortOrderItem;
        this.newMergeJoin = newMergeJoin;
        oldMergeJoin = false;
        if (newMergeJoin == SYSTEM ||
            newMergeJoin == SYSTEM_OFF) {
            this.newMergeJoin =
                EngineDriver.driver().getConfiguration().getNewMergeJoin();
            if (this.newMergeJoin == FORCED)
                oldMergeJoin = false;
            else if (this.newMergeJoin == ON) {
                if (newMergeJoin == SYSTEM_OFF)
                    oldMergeJoin = true;
            }
            else
                oldMergeJoin = true;
        }
        else if (newMergeJoin == OFF)
            oldMergeJoin = true;
        init();
    }

    protected int[] generateIntArray(int item) {
        FormatableIntHolder[] fihArray = (FormatableIntHolder[]) activation.getPreparedStatement().getSavedObject(item);
        int[] cols = new int[fihArray.length];
        for (int i = 0, s = fihArray.length; i < s; i++){
            cols[i] = fihArray[i].getInt();
        }
        return cols;
    }

    @Override
    public void init(SpliceOperationContext context) throws StandardException, IOException {
    	super.init(context);
        leftHashKeys = generateHashKeys(leftHashKeyItem);
        rightHashKeys = generateHashKeys(rightHashKeyItem);
        if (rightHashKeyToBaseTableMapItem != -1)
            rightHashKeyToBaseTableMap = generateIntArray(rightHashKeyToBaseTableMapItem);
        else
            rightHashKeyToBaseTableMap = null;
        if (rightHashKeySortOrderItem != -1)
            rightHashKeySortOrders = generateIntArray(rightHashKeySortOrderItem);
        else
            rightHashKeySortOrders = null;

    	if (LOG.isDebugEnabled()) {
    		SpliceLogUtils.debug(LOG,"left hash keys {%s}",Arrays.toString(leftHashKeys));
    		SpliceLogUtils.debug(LOG,"right hash keys {%s}",Arrays.toString(rightHashKeys));
            SpliceLogUtils.debug(LOG,"right hash keys to base table map {%s}",Arrays.toString(rightHashKeyToBaseTableMap));
            SpliceLogUtils.debug(LOG,"right hash keys' sort order {%s}", Arrays.toString(rightHashKeySortOrders));
    	}
    }

    @Override
    public DataSet<ExecRow> getDataSet(DataSetProcessor dsp) throws StandardException {
        if (!isOpen)
            throw new IllegalStateException("Operation is not open");

        boolean isSparkExplain = dsp.isSparkExplain();
        OperationContext<JoinOperation> operationContext = dsp.<JoinOperation>createOperationContext(this);
        dsp.incrementOpDepth();
        DataSet<ExecRow> left = leftResultSet.getDataSet(dsp);
        dsp.finalizeTempOperationStrings();
        DataSet<ExecRow> right = null;
        if (isSparkExplain) {
            // Need to call getDataSet to fully print the spark explain.
            right = rightResultSet.getDataSet(dsp);
            dsp.decrementOpDepth();
        }
        operationContext.pushScope();
        try {
            left = left.map(new CountJoinedLeftFunction(operationContext));
            DataSet<ExecRow> joined = null;
            if (isOuterJoin())
                joined = left.mapPartitions(new MergeOuterJoinFlatMapFunction(operationContext, oldMergeJoin), true);
            else {
                if (isAntiJoin())
                    joined = left.mapPartitions(new MergeAntiJoinFlatMapFunction(operationContext, oldMergeJoin), true);
                else {
                    joined = left.mapPartitions(new MergeInnerJoinFlatMapFunction(operationContext, oldMergeJoin), true);
                }
            }
            if (isSparkExplain)
                handleSparkExplain(joined, left, right, dsp);

            return joined;
        } finally {
            operationContext.popScope();
        }
    }

    @Override
    public int[] getLeftHashKeys() {
        return leftHashKeys;
    }

    @Override
    public int[] getRightHashKeys() {
        return rightHashKeys;
    }

    public int[] getRightHashKeyToBaseTableMap() {
	    return rightHashKeyToBaseTableMap;
    }

    public int[] getRightHashKeySortOrders() {
	    return rightHashKeySortOrders;
    }
}
