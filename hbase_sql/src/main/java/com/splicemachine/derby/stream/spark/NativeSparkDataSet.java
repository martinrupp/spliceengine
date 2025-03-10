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
 *
 */

package com.splicemachine.derby.stream.spark;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.GlobalDBProperties;
import com.splicemachine.db.iapi.services.property.PropertyUtil;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.SQLLongint;
import com.splicemachine.db.impl.sql.compile.ExplainNode;
import com.splicemachine.db.impl.sql.compile.SparkExpressionNode;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.impl.SpliceSpark;
import com.splicemachine.derby.impl.sql.execute.operations.*;
import com.splicemachine.derby.impl.sql.execute.operations.framework.SpliceGenericAggregator;
import com.splicemachine.derby.impl.sql.execute.operations.window.WindowAggregator;
import com.splicemachine.derby.impl.sql.execute.operations.window.WindowContext;
import com.splicemachine.derby.stream.function.*;
import com.splicemachine.derby.stream.iapi.*;
import com.splicemachine.derby.stream.output.*;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.spark.splicemachine.ShuffleUtils;
import com.splicemachine.sparksql.ParserUtils;
import com.splicemachine.system.CsvOptions;
import com.splicemachine.utils.Pair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.scheduler.SparkListener;
import org.apache.spark.scheduler.SparkListenerJobStart;
import org.apache.spark.scheduler.SparkListenerTaskEnd;
import org.apache.spark.scheduler.StageInfo;
import org.apache.spark.sql.*;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;
import scala.collection.JavaConverters;
import splice.com.google.common.base.Function;
import splice.com.google.common.collect.Iterators;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.splicemachine.derby.stream.spark.SparkDataSet.generateTableSchema;
import static com.splicemachine.derby.stream.spark.SparkExternalTableUtil.getCsvOptions;
import static org.apache.spark.sql.functions.*;


/**
 *
 * DataSet Implementation for Spark.
 *
 * @see DataSet
 * @see java.io.Serializable
 *
 */
@SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "Some checked exceptions are not declared")
public class NativeSparkDataSet<V> implements DataSet<V> {

    private static String SPARK_COMPRESSION_OPTION = "compression";

    private Dataset<Row> dataset;
    private Map<String,String> attributes;
    private ExecRow execRow;
    private OperationContext context;

    public NativeSparkDataSet(Dataset<Row> dataset) {
        this(dataset, (OperationContext) null);
    }
    
    public NativeSparkDataSet(Dataset<Row> dataset, OperationContext context) {
        int cols = dataset.columns().length;
        String[] colNames = new String[cols];
        for (int i = 0; i<cols; i++) {
            colNames[i] = "c"+i;
        }
        this.dataset = dataset.toDF(colNames);
        this.context = context;
    }

    public NativeSparkDataSet(Dataset<Row> dataset, OperationContext context,
                              boolean assignNewColumnNames) {
        if (assignNewColumnNames) {
            int cols = dataset.columns().length;
            String[] colNames = new String[cols];
            for (int i = 0; i < cols; i++) {
                colNames[i] = "c" + i;
            }
            this.dataset = dataset.toDF(colNames);
        }
        else
            this.dataset = dataset;
        this.context = context;
    }

    public NativeSparkDataSet(JavaRDD<V> rdd, String ignored, OperationContext context) {
        this(NativeSparkDataSet.<V>toSparkRow(rdd, context), context);
        try {
            this.execRow = context.getOperation().getExecRowDefinition();
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    public Dataset<Row> getDataset() {
        return dataset;
    }

    @Override
    public int partitions() {
        return dataset.rdd().getNumPartitions();
    }

    @Override
    public Pair<DataSet, Integer> materialize() {
        return Pair.newPair(this, (int) dataset.count());
    }

    @Override
    public Pair<DataSet, Integer> persistIt() {
        dataset.persist(StorageLevel.MEMORY_AND_DISK_SER());
        return Pair.newPair(this, (int) this.count());
    }

    @Override
    public DataSet getClone() {
        return this;
    }

    @Override
    public void unpersistIt() {
        dataset.unpersist();
        return;
    }

    /**
     * Execute the job and materialize the results as a List.  Be careful, all
     * data must be held in memory.
     */
    @Override
    public List<V> collect() {
        return (List<V>)Arrays.asList(dataset.collect());
    }

    /**
     *
     * Perform the execution asynchronously and returns a Future<List>.  Be careful, all
     * data must be held in memory.
     */
    @Override
    public Future<List<V>> collectAsync(boolean isLast, OperationContext context, boolean pushScope, String scopeDetail) {
        throw new UnsupportedOperationException();
    }

    /**
     * Wraps a function on an entire partition.
     * @see JavaRDD#mapPartitions(FlatMapFunction)
     */
    @Override
    public <Op extends SpliceOperation, U> DataSet<U> mapPartitions(SpliceFlatMapFunction<Op,Iterator<V>, U> f) {
        try {
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).mapPartitions(f);

        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    @Override
    public DataSet<V> shufflePartitions() {
        return new NativeSparkDataSet(ShuffleUtils.shuffle(dataset), this.context);
    }

    /**
     * Wraps a function on an entire partition.  IsLast is used for visualizing results.
     */
    @Override
    public <Op extends SpliceOperation, U> DataSet<U> mapPartitions(SpliceFlatMapFunction<Op,Iterator<V>, U> f, boolean isLast) {
        return mapPartitions(f);
    }

    @Override
    public <Op extends SpliceOperation, U> DataSet<U> mapPartitions(SpliceFlatMapFunction<Op,Iterator<V>, U> f, boolean isLast, boolean pushScope, String scopeDetail) {
        return mapPartitions(f);
    }

    /**
     *
     *
     * @see JavaRDD#distinct()
     *
     * @return
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet<V> distinct(OperationContext context) throws StandardException {
        return distinct("Remove Duplicates", false, context, false, null);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet<V> limit(int numRows, OperationContext context) {
        Dataset<Row> result = dataset.limit(numRows);
        return new NativeSparkDataSet<>(result, context);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet<V> distinct(String name, boolean isLast, OperationContext context, boolean pushScope, String scopeDetail) throws StandardException {
        boolean varcharDB2CompatibilityMode = PropertyUtil.getCachedBoolean(
                context.getActivation().getLanguageConnectionContext(),
                GlobalDBProperties.SPLICE_DB2_VARCHAR_COMPATIBLE);
        pushScopeIfNeeded(context, pushScope, scopeDetail);
        try {
            // Do not replace string columns using rtrimmed columns but add them as extra columns.
            // After distinct, for each group, we need to return a row originally in that group, not
            // a row with rtrimmed values.
            List<String> compColNames = new ArrayList<>();
            List<String> extraColNames = new ArrayList<>();
            List<Column> extraColumns = new ArrayList<>();
            if (varcharDB2CompatibilityMode) {
                List<String> strColNames = new ArrayList<>();
                Tuple2<String, String>[] colTypes = dataset.dtypes();
                for (Tuple2<String, String> colType : colTypes) {
                    if (colType._2().equals("StringType")) {
                        strColNames.add(colType._1());
                    } else {
                        compColNames.add(colType._1());
                    }
                }
                for (String colName : strColNames) {
                    String newColName = colName+"_rtrimmed";
                    compColNames.add(newColName);
                    extraColNames.add(newColName);
                    extraColumns.add(rtrim(col(colName)));
                }
                dataset = NativeSparkUtils.withColumns(extraColNames, extraColumns, dataset);
            }

            Dataset<Row> result;
            if (compColNames.isEmpty()) {
                result = dataset.distinct();
            } else {
                result = dataset.dropDuplicates(compColNames.toArray(new String[0]));
            }
            if (!extraColNames.isEmpty()) {
                result = result.drop(extraColNames.toArray(new String[0]));
            }
            return new NativeSparkDataSet<>(result, context);
        } finally {
            if (pushScope) context.popScope();
        }
    }

    @Override
    public <Op extends SpliceOperation, K,U> PairDataSet<K,U> index(SplicePairFunction<Op,V,K,U> function) throws StandardException {
        return new SparkDataSet(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).index(function);
    }

    @Override
    public <Op extends SpliceOperation, K,U> PairDataSet<K,U> index(SplicePairFunction<Op,V,K,U> function, OperationContext context) throws StandardException {
        return new SparkPairDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context).mapToPair(new SparkSplittingFunction<>(function)), function.getSparkName());
    }

    @Override
    public <Op extends SpliceOperation, K,U>PairDataSet<K, U> index(SplicePairFunction<Op,V,K,U> function, boolean isLast) {
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <Op extends SpliceOperation, K,U>PairDataSet<K, U> index(SplicePairFunction<Op,V,K,U> function, boolean isLast, boolean pushScope, String scopeDetail) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <Op extends SpliceOperation, U> DataSet<U> map(SpliceFunction<Op,V,U> function) throws StandardException {
        return map(function, null, false, false, null);
    }

    public <Op extends SpliceOperation, U> DataSet<U> map(SpliceFunction<Op,V,U> function, boolean isLast) throws StandardException {
        return map(function, null, isLast, false, null);
    }

    public static boolean needsNonDerbyProcessingForGroupingFunction (OperationContext operationContext) {
        SpliceOperation op = operationContext.getOperation();
        if (op instanceof ProjectRestrictOperation) {
            ProjectRestrictOperation pr = (ProjectRestrictOperation) op;
            if (pr.hasGroupingFunction()){
                SpliceOperation childOp = pr.getSource();
                while (childOp instanceof WindowOperation ||
                       childOp instanceof ProjectRestrictOperation) {
                    if (childOp instanceof WindowOperation)
                        childOp = ((WindowOperation) childOp).getSource();
                    if (childOp instanceof ProjectRestrictOperation)
                        childOp = ((ProjectRestrictOperation) childOp).getSource();
                }
                if (childOp instanceof GenericAggregateOperation)
                    return !((GenericAggregateOperation) childOp).nativeSparkUsed();
            }
        }
        return false;
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <Op extends SpliceOperation, U> DataSet<U> map(SpliceFunction<Op,V,U> f, String name, boolean isLast, boolean pushScope, String scopeDetail) throws StandardException {
        OperationContext<Op> context = f.operationContext;
        if (f.hasNativeSparkImplementation() &&
            !needsNonDerbyProcessingForGroupingFunction(f.operationContext)) {
            Pair<Dataset<Row>, OperationContext> pair = f.nativeTransformation(dataset, context);
            OperationContext c = pair.getSecond() == null ? this.context : pair.getSecond();
            return new NativeSparkDataSet<>(pair.getFirst(), c);
        }
        return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, f.getExecRow(), context)).map(f, name, isLast, pushScope, scopeDetail);
    }

    @Override
    public Iterator<V> toLocalIterator() {
        return Iterators.transform(dataset.toLocalIterator(), new Function<Row, V>() {
            @Nullable
            @Override
            public V apply(@Nullable Row input) {
                ValueRow vr;
                try {
                    if (context != null &&
                        context.getOperation().getExecRowDefinition()
                                                  instanceof ValueRow)
                        vr = (ValueRow) context.getOperation().getExecRowDefinition().getClone();
                    else
                        vr = new ValueRow(input.size());
                }
                catch (StandardException se) {
                    vr = new ValueRow(input.size());
                }
                vr.fromSparkRow(input);
                return (V) vr;
            }
        });
    }

    @Override
    public <Op extends SpliceOperation, K> PairDataSet< K, V> keyBy(SpliceFunction<Op, V, K> f) {
        try {
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).keyBy(f);
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    public <Op extends SpliceOperation, K> PairDataSet< K, V> keyBy(SpliceFunction<Op, V, K> f, String name) {
        return keyBy(f);
    }


    public <Op extends SpliceOperation, K> PairDataSet< K, V> keyBy(SpliceFunction<Op, V, K> f, OperationContext context) throws StandardException {
        return new SparkPairDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context).keyBy(new SparkSpliceFunctionWrapper<>(f)), f.getSparkName());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <Op extends SpliceOperation, K> PairDataSet< K, V> keyBy(
            SpliceFunction<Op, V, K> f, String name, boolean pushScope, String scopeDetail) {
        return keyBy(f);
    }

    @Override
    public long count() {
        return dataset.count();
    }

    @Override
    public DataSet<V> union(DataSet<V> dataSet, OperationContext operationContext) {
        return union(dataSet, operationContext, RDDName.UNION.displayName(), false, null);
    }


    @Override
    public DataSet<V> parallelProbe(List<ScanSetBuilder<ExecRow>> scanSetBuilders, OperationContext<MultiProbeTableScanOperation> operationContext) throws StandardException {
        DataSet<V> toReturn = null;
        DataSet<V> branch = null;
        int i = 0;
        MultiProbeTableScanOperation operation = operationContext.getOperation();
        for (ScanSetBuilder<ExecRow> builder: scanSetBuilders) {
            DataSet<V> dataSet = (DataSet<V>) builder.buildDataSet(operation);
            if (i % 100 == 0) {
                if (branch != null) {
                    if (toReturn == null)
                        toReturn = branch;
                    else
                        toReturn = toReturn.union(branch, operationContext);
                }
                branch = dataSet;
            }
            else
                branch = branch.union(dataSet, operationContext);
            i++;
        }
        if (branch != null) {
            if (toReturn == null)
                toReturn = branch;
            else
                toReturn = toReturn.union(branch, operationContext);
        }
        return toReturn;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet< V> union(DataSet<V> dataSet, OperationContext operationContext, String name, boolean pushScope, String scopeDetail) {
        pushScopeIfNeeded((SpliceFunction)null, pushScope, scopeDetail);
        try {
            Dataset right = getDataset(dataSet);
            Dataset rdd1 = dataset.union(right);
            return new NativeSparkDataSet<>(rdd1, operationContext);
        } catch (Exception se){
            throw new RuntimeException(se);
        } finally {
            if (pushScope) SpliceSpark.popScope();
        }
    }

    @Override
    public DataSet<V> union(List<DataSet<V>> dataSetList, OperationContext operationContext) {
        DataSet<V> toReturn = null;
        for (DataSet<V> aSet: dataSetList) {
            if (toReturn == null)
                toReturn = aSet;
            else
                toReturn = aSet.union(aSet, operationContext, RDDName.UNION.displayName(), false, null);
        }

        return toReturn;
    }

    @Override
    public DataSet<V> orderBy(OperationContext operationContext, int[] keyColumns, boolean[] descColumns, boolean[] nullsOrderedLow) {
        try {
            Column[] orderedCols = new Column[keyColumns.length];
            for (int i = 0; i < keyColumns.length; i++) {
                Column orderCol = col(ValueRow.getNamedColumn(keyColumns[i]));
                if (descColumns[i]) {
                    if (nullsOrderedLow[i])
                        orderedCols[i] = orderCol.desc_nulls_last();
                    else
                        orderedCols[i] = orderCol.desc_nulls_first();
                }
                else {
                    if (nullsOrderedLow[i])
                        orderedCols[i] = orderCol.asc_nulls_first();
                    else
                        orderedCols[i] = orderCol.asc_nulls_last();
                }
            }
            return new NativeSparkDataSet<>(dataset.orderBy(orderedCols), operationContext);
        }
        catch (Exception se){
            throw new RuntimeException(se);
        }
    }

    @Override
    public <Op extends SpliceOperation> DataSet< V> filter(SplicePredicateFunction<Op, V> f) {
        return filter(f,false, false, "");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <Op extends SpliceOperation> DataSet< V> filter(
        SplicePredicateFunction<Op,V> f, boolean isLast, boolean pushScope, String scopeDetail) {

        OperationContext<Op> context = f.operationContext;
        if (f.hasNativeSparkImplementation()) {
            // TODO:  Enable the commented try-catch block after regression testing.
            //        This would be a safeguard against unanticipated exceptions:
            //             org.apache.spark.sql.catalyst.parser.ParseException
            //             org.apache.spark.sql.AnalysisException
            //    ... which may occur if the Splice parser fails to detect a
            //        SQL expression which SparkSQL does not support.
//          try {
                Pair<Dataset<Row>, OperationContext> pair = f.nativeTransformation(dataset, context);
                OperationContext c = pair.getSecond() == null ? this.context : pair.getSecond();
                return new NativeSparkDataSet<>(pair.getFirst(), c);
//          }
//          catch (Exception e) {
//               Switch back to non-native DataSet if the SparkSQL filter is not supported.
//          }
        }
        try {
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).filter(f, isLast, pushScope, scopeDetail);
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet< V> intersect(DataSet<V> dataSet, OperationContext context) {
        return intersect(dataSet,"Intersect Operator",context,false,null);
    }

    /**
     * Spark implementation of Window function,
     * We convert the derby specification using SparkWindow helper
     * Most of the specifications is identical to Spark except the one position index
     * and some specific functions. Look at SparkWindow for more
     * @param windowContext
     * @param context
     * @param pushScope
     * @param scopeDetail
     * @return
     */
    public DataSet<V> windows(WindowContext windowContext, OperationContext context,  boolean pushScope, String scopeDetail) {
        pushScopeIfNeeded(context, pushScope, scopeDetail);
        try {
            Dataset<Row> dataset = this.dataset;
            WindowAggregator[] windowFunctions = windowContext.getWindowFunctions();
            List<String> names = new ArrayList<>(windowFunctions.length);
            List<Column> cols = new ArrayList<>(windowFunctions.length);

            for(WindowAggregator aggregator : windowContext.getWindowFunctions()) {
                // we need to remove to convert resultColumnId from a 1 position index to a 0position index
                DataType resultDataType = dataset.schema().fields()[aggregator.getResultColumnId()-1].dataType();
                // We define the window specification and we get a back a spark.
                // Simply provide all the information and spark window will build it for you
                Column col = SparkWindow.partitionBy(aggregator.getPartitions())
                        .function(aggregator.getType())
                        .inputs(aggregator.getInputColumnIds())
                        .orderBy(aggregator.getOrderings())
                        .frameBoundary(aggregator.getFrameDefinition())
                        .specificArgs(aggregator.getFunctionSpecificArgs())
                        .resultColumn(aggregator.getResultColumnId())
                        .resultDataType(resultDataType)
                        .functionSpecificArgs(aggregator.getFunctionSpecificArgs())
                        .toColumn();

                names.add(ValueRow.getNamedColumn(aggregator.getResultColumnId()-1));
                cols.add(col);
            }
            // Now we replace the result column by the spark specification.
            // the result column is already define by derby. We need to replace it
            dataset = NativeSparkUtils.withColumns(names, cols, dataset);
           return new NativeSparkDataSet<>(dataset, context);

        } catch (Exception se){
            throw new RuntimeException(se);
        }finally {
            if (pushScope) context.popScope();
        }

    }

    private Dataset getDataset(DataSet dataSet) throws StandardException {
        if (dataSet instanceof NativeSparkDataSet) {
            return ((NativeSparkDataSet) dataSet).dataset;
        } else {
            //Convert the right operand to a untyped dataset

            return SpliceSpark.getSession()
                    .createDataFrame(
                            ((SparkDataSet)dataSet).rdd
                                    .map(new LocatedRowToRowFunction()),
                            context.getOperation().schema());
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet< V> intersect(DataSet< V> dataSet, String name, OperationContext context, boolean pushScope, String scopeDetail) {
        pushScopeIfNeeded(context, pushScope, scopeDetail);
        try {
            //Convert this dataset backed iterator to a Spark untyped dataset
            Dataset<Row> left = dataset;

            //Convert the left operand to a untyped dataset
            Dataset<Row> right = getDataset(dataSet);

            //Do the intesect
            Dataset<Row> result = left.intersect(right);

            return new NativeSparkDataSet<V>(result, context);

        }
        catch (Exception se){
            throw new RuntimeException(se);
        }finally {
            if (pushScope) context.popScope();
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet<V> subtract(DataSet<V> dataSet, OperationContext context, boolean isDistinct){
        return subtract(dataSet, "Substract/Except Operator", context, isDistinct, false, null);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public DataSet< V> subtract(DataSet<V> dataSet, String name, OperationContext context, boolean isDistinct, boolean pushScope, String scopeDetail) {
        pushScopeIfNeeded(context, pushScope, scopeDetail);
        try {
            //Convert this dataset backed iterator to a Spark untyped dataset
            Dataset<Row> left = dataset;

            //Convert the right operand to a untyped dataset
            Dataset<Row> right = getDataset(dataSet);

            //Do the subtract
            Dataset<Row> result;
            if(isDistinct) {
                result = left.distinct().except(right.distinct());
            } else {
                result = left.except(right);
            }

            return new NativeSparkDataSet<>(result, context);
        }
        catch (Exception se){
            throw new RuntimeException(se);
        }finally {
            if (pushScope) context.popScope();
        }
    }



    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException("isEmpty() not supported");
    }

    @Override
    public <Op extends SpliceOperation, U> DataSet< U> flatMap(SpliceFlatMapFunction<Op, V, U> f) {
        try {
            OperationContext<Op> context = f.operationContext;
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).flatMap(f);
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    public <Op extends SpliceOperation, U> DataSet< U> flatMap(SpliceFlatMapFunction<Op, V, U> f, String name) {
        try {
            OperationContext<Op> context = f.operationContext;
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).flatMap(f, name);
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    public <Op extends SpliceOperation, U> DataSet< U> flatMap(SpliceFlatMapFunction<Op, V, U> f, boolean isLast) {
        try {
            OperationContext<Op> context = f.operationContext;
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).flatMap(f, isLast);
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }
    
    @Override
    public void close() {
    }

    @Override
    public <Op extends SpliceOperation> DataSet<V> take(TakeFunction<Op,V> takeFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ExportDataSetWriterBuilder writeToDisk() {
        try {
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).writeToDisk();
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    @Override
    public KafkaDataSetWriterBuilder writeToKafka() {
        try {
            return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context)).writeToKafka();
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    @Override
    public void saveAsTextFile(String path) {
        throw new UnsupportedOperationException("saveAsTextFile() not supported");
    }

    @Override
    public PairDataSet<V, Long> zipWithIndex(OperationContext context) throws StandardException {
        return new SparkPairDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context).zipWithIndex());
    }

    @SuppressWarnings("unchecked")
    @Override
    public ExportDataSetWriterBuilder<String> saveAsTextFile(OperationContext operationContext){
        try {
            return new SparkExportDataSetWriter.Builder<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context));
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public DataSet<V> coalesce(int numPartitions, boolean shuffle) {
        if (shuffle) {
            return new NativeSparkDataSet<>(dataset.repartition(numPartitions), this.context);
        } else {
            return new NativeSparkDataSet<>(dataset.coalesce(numPartitions), this.context);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public DataSet<V> coalesce(int numPartitions, boolean shuffle, boolean isLast, OperationContext context, boolean pushScope, String scopeDetail) {
        return coalesce(numPartitions, shuffle);
    }

    @Override
    public void persist() {
        dataset.persist(StorageLevel.MEMORY_AND_DISK_SER_2());
    }

    @Override
    public void setAttribute(String name, String value) {
        if (attributes == null)
            attributes = new HashMap<>();
        attributes.put(name,value);
    }

    @Override
    public String getAttribute(String name) {
        if (attributes == null)
            return null;
        return attributes.get(name);
    }

    private void pushScopeIfNeeded(AbstractSpliceFunction function, boolean pushScope, String scopeDetail) {
        if (pushScope) {
            if (function != null && function.operationContext != null)
                function.operationContext.pushScopeForOp(scopeDetail);
            else
                SpliceSpark.pushScope(scopeDetail);
        }
    }

    private void pushScopeIfNeeded(OperationContext operationContext, boolean pushScope, String scopeDetail) {
        if (pushScope) {
            if (operationContext != null)
                operationContext.pushScopeForOp(scopeDetail);
            else
                SpliceSpark.pushScope(scopeDetail);
        }
    }

    @SuppressWarnings("rawtypes")
    private String planIfLast(AbstractSpliceFunction f, boolean isLast) {
        if (!isLast) return f.getSparkName();
        String plan = f.getOperation()==null?null:f.getOperation().getPrettyExplainPlan();
        return (plan != null && !plan.isEmpty() ? plan : f.getSparkName());
    }

    public static boolean joinProjectsOnlyLeftTableColumns(JoinType joinType) {
        return (joinType == DataSet.JoinType.LEFTANTI ||
                joinType == DataSet.JoinType.LEFTSEMI);
    }

    private static boolean
    leftDFOfJoinProjectsFewerColumnsThanDefined(JoinOperation op,
                                                JoinType      joinType,
                                                Dataset<Row>  leftDF) {
        return !joinProjectsOnlyLeftTableColumns(joinType) &&
               !op.wasRightOuterJoin &&
                op.getLeftRow().nColumns() > leftDF.schema().length();
    }

    // If the left DataSet defines more columns than the left DataFrame,
    // the column names will be off.  Let's fill the gaps to match the
    // ExecRowDefinition.
    static private Dataset<Row> fixupColumnNames(JoinOperation op, JoinType joinType,
                                          Dataset<Row> leftDF, Dataset<Row> rightDF,
                                          Dataset<Row> joinedDF,
                                          SpliceOperation leftOp,
                                          SpliceOperation rightOp) throws StandardException {
        boolean isSemiOrAntiJoin =
                joinType.strategy().equals(JoinType.LEFTANTI.strategy()) ||
                joinType.strategy().equals(JoinType.LEFTSEMI.strategy());
        boolean needsFixup =
                isSemiOrAntiJoin ||
                leftDFOfJoinProjectsFewerColumnsThanDefined(op, joinType, leftDF);
        if (!needsFixup)
            return joinedDF;

        int adjColNo = op.getLeftRow().nColumns();
        int baseColNo = leftDF.schema().length();

        // Now add another select expression so that the named columns are
        // in the correct column positions.
        StringBuilder expression = new StringBuilder();
        String[] expressions = new String[op.getLeftRow().nColumns() + rightDF.schema().length()];
        for (int i = 0; i < leftDF.schema().length(); i++) {
            String fieldName = ValueRow.getNamedColumn(i);
            expression.setLength(0);
            expression.append(fieldName);
            expressions[i] = expression.toString();
        }
        for (int i = leftDF.schema().length(); i < op.getLeftRow().nColumns(); i++) {
            String fieldName = ValueRow.getNamedColumn(i);
            DataType dataType;
            try {
                dataType =
                    op.getLeftRow().getColumn(i + 1).getStructField(fieldName).dataType();
            }
            catch (StandardException e) {
                throw new RuntimeException(e);
            }
            expression.setLength(0);
            expression.append("CAST(null as ");
            expression.append(dataType.typeName());
            expression.append(")");
            expressions[i] = expression.toString();
        }

        if (isSemiOrAntiJoin) {
            ExecRow rowDef = rightOp.getExecRowDefinition();
            for (int i = 0; i < rightDF.schema().length(); i++) {
                String fieldName = ValueRow.getNamedColumn(i + adjColNo);
                String sourceFieldName = ValueRow.getNamedColumn(i);
                DataType dataType =
                        rowDef.getColumn(i+1).getStructField(sourceFieldName).dataType();
                String dataTypeString = dataType.typeName();

                expression.setLength(0);
                expression.append("CAST (null as ");
                expression.append(dataTypeString);
                expression.append(") as ");
                expression.append(fieldName);
                expressions[i + adjColNo] = expression.toString();
            }
        }
        else {
            for (int i = 0; i < rightDF.schema().length(); i++) {
                String fieldName = ValueRow.getNamedColumn(i + baseColNo);
                expression.setLength(0);
                expression.append(fieldName);
                expression.append(" as ");
                expression.append(ValueRow.getNamedColumn(i + adjColNo));
                expressions[i + adjColNo] = expression.toString();
            }
        }
        joinedDF = joinedDF.selectExpr(expressions);

        return joinedDF;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public DataSet<V> join(OperationContext context, DataSet<V> rightDataSet, JoinType joinType, boolean isBroadcast) {
        try {
            JoinOperation op = (JoinOperation) context.getOperation();
            Dataset<Row> leftDF = dataset;
            Dataset<Row> rightDF;
            if (rightDataSet instanceof NativeSparkDataSet) {
                rightDF = ((NativeSparkDataSet)rightDataSet).dataset;
            } else {
                rightDF = SpliceSpark.getSession().createDataFrame(
                        ((SparkDataSet)rightDataSet).rdd.map(new LocatedRowToRowFunction()),
                        context.getOperation().getRightOperation().schema());
            }

            Dataset<Row> joinedDF = getJoinedDataset(context, leftDF, rightDF, false, op.wasRightOuterJoin,
                    null, isBroadcast, op.getSparkJoinPredicate(), joinType);
            DataSet joinedSet;

            if (op.wasRightOuterJoin) {
                NativeSparkDataSet nds = new NativeSparkDataSet(joinedDF, context);
                joinedSet = nds;
                nds.dataset = fixupColumnNames(op, joinType, rightDF, leftDF, nds.dataset,
                                               op.getRightOperation(), op.getLeftOperation());
            }
            else {
                NativeSparkDataSet nds = new NativeSparkDataSet(joinedDF, context);
                joinedSet = nds;
                nds.dataset = fixupColumnNames(op, joinType, leftDF, rightDF, nds.dataset,
                                               op.getLeftOperation(), op.getRightOperation());
            }

            return joinedSet;
        }  catch (StandardException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getRTrimmedColumnName(String colName) {
        if (colName == null)
            return null;
        return colName + "_rtrimmed";
    }

    private Dataset<Row> getJoinedDataset(OperationContext context, Dataset<Row> leftDF, Dataset<Row> rightDF, boolean isCross, boolean wasRightOuter,
                                          Broadcast crossBType, boolean isBroadcast, SparkExpressionNode sparkJoinPredicate,
                                          JoinType joinType) throws StandardException {
        Column filterExpr = null;
        int[] rightJoinKeys = ((JoinOperation)context.getOperation()).getRightHashKeys();
        int[] leftJoinKeys = ((JoinOperation)context.getOperation()).getLeftHashKeys();

        boolean varcharDB2CompatibilityMode = PropertyUtil.getCachedBoolean(
                context.getActivation().getLanguageConnectionContext(),
                GlobalDBProperties.SPLICE_DB2_VARCHAR_COMPATIBLE);

        HashMap<String, Column> leftColMap = new HashMap<>();
        HashMap<String, Column> rightColMap = new HashMap<>();

        List<String> extraColNamesLeft = new ArrayList<>();
        List<Column> extraColumnsLeft = new ArrayList<>();
        List<String> extraColNamesRight = new ArrayList<>();
        List<Column> extraColumnsRight = new ArrayList<>();
        if ( leftJoinKeys!=null || rightJoinKeys!=null ) {
            assert rightJoinKeys != null && leftJoinKeys != null && rightJoinKeys.length == leftJoinKeys.length : "Join Keys Have Issues";

            Tuple2<String, String>[] leftColTypes = leftDF.dtypes();
            Tuple2<String, String>[] rightColTypes = rightDF.dtypes();
            String leftColNames[] = new String[rightJoinKeys.length];
            String rightColNames[] = new String[rightJoinKeys.length];
            // Add new rtrimmed join key columns for string types
            // if using DB2 compatibility mode.
            for (int i = 0; i < rightJoinKeys.length; i++) {
                leftColNames[i] = ValueRow.getNamedColumn(leftJoinKeys[i]);
                if (varcharDB2CompatibilityMode && leftColTypes[leftJoinKeys[i]]._2().equals("StringType")) {
                    String leftTrimmedColName = getRTrimmedColumnName(leftColNames[i]);
                    leftColMap.put(leftTrimmedColName, rtrim(col(leftColNames[i])));
                    leftColNames[i] = leftTrimmedColName;
                }
                rightColNames[i] = ValueRow.getNamedColumn(rightJoinKeys[i]);
                if (varcharDB2CompatibilityMode && rightColTypes[rightJoinKeys[i]]._2().equals("StringType")) {
                    String rightTrimmedColName = getRTrimmedColumnName(rightColNames[i]);
                    rightColMap.put(rightTrimmedColName, rtrim(col(rightColNames[i])));
                    rightColNames[i] = rightTrimmedColName;
                }
            }
            if (sparkJoinPredicate != null && varcharDB2CompatibilityMode)
                sparkJoinPredicate.transformToRTrimmedColumnExpression(leftColTypes,
                                                                       rightColTypes,
                                                                       leftColMap,
                                                                       rightColMap);
            if (!leftColMap.isEmpty()) {
                for (Map.Entry<String, Column> pair : leftColMap.entrySet()) {
                    extraColNamesLeft.add(pair.getKey());
                    extraColumnsLeft.add(pair.getValue());
                }
                leftDF = NativeSparkUtils.withColumns(extraColNamesLeft, extraColumnsLeft, leftDF);
            }
            if (!rightColMap.isEmpty()) {
                for (Map.Entry<String, Column> pair : rightColMap.entrySet()) {
                    extraColNamesRight.add(pair.getKey());
                    extraColumnsRight.add(pair.getValue());
                }
                rightDF = NativeSparkUtils.withColumns(extraColNamesRight, extraColumnsRight, rightDF);
            }

            if (sparkJoinPredicate != null) {
                filterExpr = sparkJoinPredicate.getColumnExpression(leftDF, rightDF, ParserUtils::getDataTypeFromString);
            } else {
                for (int i = 0; i < rightJoinKeys.length; i++) {
                    Column joinEquality = (leftDF.col(leftColNames[i]).equalTo(rightDF.col(rightColNames[i])));
                    filterExpr = i != 0 ? filterExpr.and(joinEquality) : joinEquality;
                    leftDF = leftDF.filter(leftColNames[i] + " IS NOT NULL");
                    rightDF = rightDF.filter(rightColNames[i] + " IS NOT NULL");
                }
            }
        }
        if (!isCross && filterExpr == null && rightJoinKeys.length == 0)
            filterExpr= lit(true);

        if (isBroadcast) {
            rightDF = broadcast(rightDF);
        }

        Dataset<Row> joinedDF = null;
        if (isCross) {
            switch (crossBType) {
                case LEFT:
                    joinedDF = broadcast(leftDF).crossJoin(rightDF);
                    break;
                case RIGHT:
                    joinedDF = leftDF.crossJoin(broadcast(rightDF));
                    break;
                case NONE:
                default:
                    joinedDF = leftDF.crossJoin(rightDF);
                    break;
            }
            if (filterExpr != null) {
                joinedDF = joinedDF.filter(filterExpr);
            }
        } else if (wasRightOuter) {
            joinedDF = rightDF.join(leftDF, filterExpr, JoinType.RIGHTOUTER.strategy());
        } else {
            joinedDF = leftDF.join(rightDF, filterExpr, joinType.strategy());
        }
        if (!extraColNamesLeft.isEmpty())
            joinedDF = joinedDF.drop(extraColNamesLeft.toArray(new String[0]));
        if (!extraColNamesRight.isEmpty())
            joinedDF = joinedDF.drop(extraColNamesRight.toArray(new String[0]));

        return joinedDF;
    }


    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public DataSet<V> crossJoin(OperationContext context, DataSet<V> rightDataSet, Broadcast type) {
        try {
            Dataset<Row> leftDF = dataset;
            Dataset<Row> rightDF;
            if (rightDataSet instanceof NativeSparkDataSet) {
                rightDF = ((NativeSparkDataSet) rightDataSet).dataset;
            } else {
                rightDF = SpliceSpark.getSession().createDataFrame(
                        ((SparkDataSet) rightDataSet).rdd.map(new LocatedRowToRowFunction()),
                        context.getOperation().getRightOperation().schema());
            }
            Dataset<Row> joinedDF = getJoinedDataset(context, leftDF, rightDF, true, false, type,
                    false, null, null);

            DataSet joinedSet = new NativeSparkDataSet(joinedDF, context);
            NativeSparkDataSet nds = (NativeSparkDataSet)joinedSet;
            SpliceOperation op = context.getOperation();
            nds.dataset = fixupColumnNames((JoinOperation) op,
                                           DataSet.JoinType.INNER, leftDF, rightDF, nds.dataset,
                                           op.getLeftOperation(), op.getRightOperation());
            return joinedSet;

        }  catch (StandardException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Take a Splice DataSet and  convert to a Spark Dataset
     * doing a map
     * @param context
     * @return
     * @throws Exception
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static <V> Dataset<Row> toSparkRow(JavaRDD<V> rdd, OperationContext context) {
        try {
            return SpliceSpark.getSession()
                    .createDataFrame(
                            rdd.map(new LocatedRowToRowFunction()),
                            context.getOperation().schema());
        } catch (Exception e) {
            throw Exceptions.throwAsRuntime(e);
        }
    }

    /**
     * Take a spark dataset and translate that to Splice format
     * @param dataSet
     * @param context
     * @return
     */

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <V> JavaRDD<V> toSpliceLocatedRow(Dataset<Row> dataSet, OperationContext context) throws StandardException {
        return (JavaRDD<V>) dataSet.javaRDD().map(new RowToLocatedRowFunction(context));
    }

    public static <V> JavaRDD<V> toSpliceLocatedRow(Dataset<Row> dataSet, ExecRow execRow, OperationContext context) throws StandardException {
        if (execRow != null) {
            return (JavaRDD<V>) dataSet.javaRDD().map(new RowToLocatedRowFunction(context, execRow));
        }
        return (JavaRDD<V>) dataSet.javaRDD().map(new RowToLocatedRowFunction(context));
    }

    private DataSet<ExecRow> getRowsWritten(OperationContext context) {
        ValueRow valueRow = new ValueRow(1);
        valueRow.setColumn(1, new SQLLongint(context.getRecordsWritten()));
        return new SparkDataSet<>(SpliceSpark.getContext()
                .parallelize(Collections.singletonList(valueRow), 1));
    }

    @Override
    public DataSet<ExecRow> writeParquetFile(int[] partitionBy, String location,
                                             String compression, OperationContext context) throws StandardException {
        compression = SparkExternalTableUtil.getParquetCompression( compression );
        try( CountingListener counter = new CountingListener(context) ) {
            getDataFrameWriter(dataset, generateTableSchema(context), partitionBy, context)
                    .option(SPARK_COMPRESSION_OPTION, compression)
                    .parquet(location);
        }

        return getRowsWritten(context);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public DataSet<ExecRow> writeAvroFile(DataSetProcessor dsp,
                                          int[] partitionBy,
                                          String location,
                                          String compression,
                                          OperationContext context) throws StandardException
    {
        StructType tableSchema = generateTableSchema(context);
        StructType dataSchema = SparkExternalTableUtil.getDataSchemaAvro(dsp, tableSchema, partitionBy, location);
        if (dataSchema == null)
            dataSchema = tableSchema;
        return writeAvroFile(dataSchema, partitionBy, location, compression, context);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public DataSet<ExecRow> writeAvroFile(StructType tableSchema,
                                          int[] partitionBy,
                                          String location,
                                          String compression,
                                          OperationContext context) throws StandardException {
        compression = SparkExternalTableUtil.getAvroCompression(compression);
        try( CountingListener counter = new CountingListener(context) ) {
            getDataFrameWriter(dataset, tableSchema, partitionBy, context)
                    .option(SPARK_COMPRESSION_OPTION, compression)
                    .format("com.databricks.spark.avro").save(location);
        }
        return getRowsWritten(context);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public DataSet<ExecRow> writeTextFile(int[] partitionBy, String location, CsvOptions csvOptions,
                                          OperationContext context) throws StandardException {
        try( CountingListener counter = new CountingListener(context) ) {
            getDataFrameWriter(dataset, generateTableSchema(context), partitionBy, context)
                    .options(getCsvOptions(csvOptions))
                    .csv(location);
        }
        return getRowsWritten(context);
    }
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public DataSet<ExecRow> writeORCFile(int[] partitionBy, String location, String compression,
                                         OperationContext context) throws StandardException {
        try( CountingListener counter = new CountingListener(context) ) {
            getDataFrameWriter(dataset, generateTableSchema(context), partitionBy, context)
                    .option(SPARK_COMPRESSION_OPTION, compression)
                    .orc(location);
        }
        return getRowsWritten(context);
    }

    class CountingListener extends SparkListener implements AutoCloseable
    {
        OperationContext context;
        SparkContext sc;
        String uuid;
        Set<Integer> stageIdsToWatch;

        public CountingListener(OperationContext context) {
            this( context, UUID.randomUUID().toString() );
        }
        public CountingListener(OperationContext context, String uuid) {
            sc = SpliceSpark.getSession().sparkContext();
            sc.getLocalProperties().setProperty("operation-uuid", uuid);
            sc.addSparkListener(this);
            this.context = context;
            this.uuid = uuid;
        }

        public void onJobStart(SparkListenerJobStart jobStart) {
            if ( !jobStart.properties().getProperty("operation-uuid", "").equals(uuid) ) {
                return;
            }

            List<StageInfo> stageInfos = JavaConverters.seqAsJavaListConverter(jobStart.stageInfos()).asJava();
            stageIdsToWatch = stageInfos.stream().map(s -> s.stageId()).collect(Collectors.toSet());
        }

        @Override
        public void onTaskEnd(SparkListenerTaskEnd taskEnd) {
            if( stageIdsToWatch != null && stageIdsToWatch.contains( taskEnd.stageId() ))
                context.recordPipelineWrites( taskEnd.taskMetrics().outputMetrics().recordsWritten() );
        }

        @Override
        public void close() {
            sc.removeSparkListener(this);
        }
    }

    static private DataFrameWriter getDataFrameWriter(Dataset<Row> dataset, StructType tableSchema,
                                                      int[] partitionBy, OperationContext context) throws StandardException {
        // OperationContext context is used in 2.8
        Dataset<Row> insertDF = SpliceSpark.getSession().createDataFrame(
                dataset.rdd(),
                tableSchema);

        String colNames[] = tableSchema.fieldNames();
        String[] partitionByCols = new String[partitionBy.length];
        for (int i = 0; i < partitionBy.length; i++) {
            partitionByCols[i] = colNames[partitionBy[i]];
        }
        if (partitionBy.length > 0) {
            List<Column> repartitionCols = new ArrayList();
            for (int i = 0; i < partitionBy.length; i++) {
                repartitionCols.add(new Column(colNames[partitionBy[i]]));
            }
            insertDF = insertDF.repartition(scala.collection.JavaConversions.asScalaBuffer(repartitionCols).toList());
        }
        return insertDF.write().partitionBy(partitionByCols).mode(SaveMode.Append);
    }

    @Override @SuppressWarnings({ "unchecked", "rawtypes" })
    public void pin(ExecRow template, long conglomId) throws StandardException {
        dataset.createOrReplaceTempView("SPLICE_"+conglomId);
        SpliceSpark.getSession().catalog().cacheTable("SPLICE_"+conglomId);
    }

    @Override
    public DataSet<V> sampleWithoutReplacement(final double fraction) {
        return new NativeSparkDataSet<>(dataset.sample(false,fraction), this.context);
    }

    @Override
    public BulkInsertDataSetWriterBuilder bulkInsertData(OperationContext operationContext) throws StandardException {
        return new SparkBulkInsertTableWriterBuilder(this);
    }

    @Override
    public BulkLoadIndexDataSetWriterBuilder bulkLoadIndex(OperationContext operationContext) throws StandardException {
        return new SparkBulkLoadIndexDataSetWriterBuilder(this);
    }

    @Override
    public BulkDeleteDataSetWriterBuilder bulkDeleteData(OperationContext operationContext) throws StandardException {
        return new SparkBulkDeleteTableWriterBuilder(this);
    }

    @Override
    public DataSetWriterBuilder deleteData(OperationContext operationContext) throws StandardException{
        return new SparkDeleteTableWriterBuilder<>(((SparkPairDataSet) this.index(new EmptySparkPairDataSet<>(), operationContext))
                .wrapExceptions());
    }

    @Override
    public InsertDataSetWriterBuilder insertData(OperationContext operationContext) throws StandardException{
        return new SparkInsertTableWriterBuilder<>(this);
    }

    @Override
    public UpdateDataSetWriterBuilder updateData(OperationContext operationContext) throws StandardException{
        return new SparkUpdateTableWriterBuilder<>(((SparkPairDataSet) this.index(new EmptySparkPairDataSet<>()))
                .wrapExceptions());
    }

    @Override
    public TableSamplerBuilder sample(OperationContext operationContext) throws StandardException {
        return new SparkTableSamplerBuilder(this, this.context);
    }

    @Override
    public DataSet upgradeToSparkNativeDataSet(OperationContext operationContext) {
         return this;
    }

    @Override
    public DataSet convertNativeSparkToSparkDataSet() throws StandardException {
        return new SparkDataSet<>(NativeSparkDataSet.<V>toSpliceLocatedRow(dataset, this.context));
    }

    /**
     * This function takes the current source NativeSparkDataSet ("this")
     * and builds a new NativeSparkDataSet with aggregate functions applied.
     *
     * The aggregations described in the aggregates array are applied to the
     * DataFrame contained in this NativeSparkDataSet (this.dataset) using
     * the RelationalGroupedDataset agg method (for grouped aggregations), or
     * the Dataset agg method (for non-grouped aggregations).
     *
     * @param isRollup, if true, causes a ROLLUP to be done.  Otherwise,
     *                  GROUP BY is done.
     * @param groupByColumns is an array of zero-based GROUP BY columns.
     *                       If null, or having zero length, no
     *                       grouping is done before aggregation.
     * @param aggregates is an array describing the aggregate functions to compute
     *                   and return as columns in the output row.
     * @param operationContext
     * @return A new SparkNativeDataSet containing a DataFrame with
     *         aggregations applied.  The consumer expects null columns were used
     *         as placeholders for splice aggregators, so new column names that pack
     *         column names consecutively (e.g. c0,c1,c2,c3...) will not be done.
     *         Instead the target column ids defined in aggregates will be used,
     *         (e.g.  c3, c6, c9 ...).  The null placeholder columns are stripped
     *         out of the input Dataset (in case there is some performance advantage).
     *         Columns present in the ExecRow definition, but absent from the select
     *         list, are added as null values to the result dataset.
     *         A return value of null means a NativeSparkDataSet could not be generated for
     *         the aggregations described in the aggregates array and traditional
     *         Splice low-level functions should be used.
     */
    @Override
    public DataSet applyNativeSparkAggregation(int[] groupByColumns, SpliceGenericAggregator[] aggregates, boolean isRollup, OperationContext operationContext) {
        context.pushScopeForOp(OperationContext.Scope.AGGREGATE);
        try {
            RelationalGroupedDataset rgd = null;
            String groupingColumn1;
            final boolean noGroupingColumns =
                  groupByColumns == null || groupByColumns.length == 0;
            if (!noGroupingColumns) {
                groupingColumn1 = ValueRow.getNamedColumn(groupByColumns[0]);
                String[] groupingColumns2ToN = new String[groupByColumns.length - 1];
                for (int i = 0; i < groupingColumns2ToN.length; i++)
                    groupingColumns2ToN[i] = ValueRow.getNamedColumn(groupByColumns[i + 1]);
                if (isRollup) {
                    rgd = dataset.rollup(groupingColumn1, groupingColumns2ToN);
                }
                else
                    rgd = dataset.groupBy(groupingColumn1, groupingColumns2ToN);
            }
            ExecRow rowDef = operationContext.getOperation().getExecRowDefinition();
            Column aggregateColumn1 = null;
            int arrayLength = isRollup ? aggregates.length + groupByColumns.length - 1 :
                              aggregates.length - 1;
            if (arrayLength < 0)
                arrayLength = 0;
            Column [] aggregateColumns2ToN = new Column [arrayLength];
            HashSet<String> inputColumns = new HashSet<>();
            Column column = null;
            for (int i = 0; i < aggregates.length; i++) {

                String sourceColName =
                    ValueRow.getNamedColumn(aggregates[i].getInputColumnId() - 1);
                String targetColName =
                    ValueRow.getNamedColumn(aggregates[i].getResultColumnId() - 1);
                DataType targetDataType = operationContext.getOperation().getExecRowDefinition().
                           getColumn(aggregates[i].getResultColumnId()).getStructField(targetColName).dataType();
                String [] arrOfStr = aggregates[i].getAggregatorInfo().getAggregateName().split("[.]");
                String aggName = arrOfStr[arrOfStr.length-1];
                Column sourceColumn = col(sourceColName);

                switch (aggName) {
                    case "SUM":
                        column = aggregates[i].isDistinct() ?
                                  sumDistinct(sourceColumn) :
                                  sum(sourceColumn);
                        column = column.cast(targetDataType);
                        inputColumns.add(sourceColName);
                        break;
                    case "MAX":
                        column = max(sourceColumn);
                        inputColumns.add(sourceColName);
                        break;
                    case "MIN":
                        column = min(sourceColumn);
                        inputColumns.add(sourceColName);
                        break;
                    case "AVG":
                        inputColumns.add(sourceColName);
                        // Splice already bumps the scale up by 4 for AVG,
                        // Spark does the same as well in class Average.
                        // The redundant scale adjustment causes overflows,
                        // which manifest as nulls in Spark.  If we attempt to
                        // remove the redundant scale adjustment by applying a CAST,
                        // we get truncated digits (e.g. 4 trailing zeroes in the
                        // rightmost positions to the right of the decimal place).
                        // To make results from the control path match results from
                        // the Spark path we evaluate avg as sum/count.

                        if (aggregates[i].isDistinct()) {
                            Column dividend = sumDistinct(sourceColumn);
                            Column divisor  = countDistinct(sourceColumn);
                            column = dividend.divide(divisor).cast(targetDataType);
                        }
                        else {
                            Column dividend = sum(sourceColumn);
                            Column divisor  = org.apache.spark.sql.functions.count(sourceColumn);
                            column = dividend.divide(divisor).cast(targetDataType);
                        }
                        break;
                    case "COUNT":
                        column = aggregates[i].isDistinct() ?
                                  countDistinct(sourceColumn) :
                                  org.apache.spark.sql.functions.count(sourceColumn);
                        column = column.cast(targetDataType);
                        inputColumns.add(sourceColName);
                        break;
                    case "COUNT(*)":
                        // There is no COUNT(DISTINCT *) in the grammar.
                        if (aggregates[i].isDistinct())
                            return null;

                        column = org.apache.spark.sql.functions.count(new Column("*"));
                        column = column.cast(targetDataType);
                        break;
                    case "SpliceStddevPop":
                        column = stddev_pop(sourceColName);
                        column = column.cast(targetDataType);
                        // Distinct not currently supported.  See SPLICE-1820.
                        if (aggregates[i].isDistinct())
                            return null;
                        inputColumns.add(sourceColName);
                        break;
                    case "SpliceStddevSamp":
                        column = stddev_samp(sourceColName);
                        column = column.cast(targetDataType);
                        // Distinct not currently supported.  See SPLICE-1820.
                        if (aggregates[i].isDistinct())
                            return null;

                        // stddev_samp may return NaN when the count is 1, so
                        // return null directly when the count is 1 or less.
                        // See SPARK-13860.
                        column = when(column.isNaN(),null).otherwise(column);
                        inputColumns.add(sourceColName);
                        break;
                    default:
                        return null;
                }
                column = column.as(targetColName);
                if (i == 0)
                    aggregateColumn1 = column;
                else
                    aggregateColumns2ToN[i-1] = column;
            }

            Dataset<Row> newDS;
            List<String> toDrop = new ArrayList<>();
            // Prune out unused columns.
            for (String colname:dataset.columns()) {
                if (!inputColumns.contains(colname))
                    toDrop.add(colname);
            }
            if (!toDrop.isEmpty()) {
                dataset = dataset.drop(toDrop.toArray(new String[toDrop.size()]));
            }
            if (noGroupingColumns) {
                newDS = dataset.agg(aggregateColumn1, aggregateColumns2ToN);
            }
            else {
                if (isRollup) {
                    for (int i = 0; i < groupByColumns.length; i++) {
                        column = grouping(col(ValueRow.getNamedColumn(groupByColumns[i])));
                        column = column.as(ValueRow.getNamedColumn(groupByColumns.length+i+1));
                        if (aggregates.length == 0 && i == 0)
                            aggregateColumn1 = column;
                        else
                            aggregateColumns2ToN[aggregates.length + i - 1] = column;
                    }
                }
                if (isRollup || aggregates.length > 0)
                    newDS = rgd.agg(aggregateColumn1, aggregateColumns2ToN);
                else
                    return null;
            }

            // Add in any missing columns as nulls, since copying of column
            // values from the spark row in ValueRow.fromSparkRow() is done by
            // column position, not column name, the Dataset schema must
            // match the ExecRow definition in case the parent operation
            // does not use native spark execution.
            List<String> names = new ArrayList<>();
            List<Column> cols = new ArrayList<>();
            for (int i=0; i < rowDef.nColumns(); i++) {
                String fieldName =  ValueRow.getNamedColumn(i);

                try {
                    int fieldIndex = newDS.schema().fieldIndex(fieldName);
                }
                catch (IllegalArgumentException e) {
                    DataType dataType =
                        rowDef.getColumn(i+1).getStructField(fieldName).dataType();
                    Column newCol = lit(null).cast(dataType);
                    names.add(fieldName);
                    cols.add(newCol);
                }
            }
            if(!names.isEmpty()) {
                newDS = NativeSparkUtils.withColumns(names, cols, newDS);
            }

            // Now add another select expression so that the named columns are
            // in the correct column positions.
            String [] expressions = new String[rowDef.nColumns()];
            for (int i=0; i < rowDef.nColumns(); i++) {
                expressions[i] = ValueRow.getNamedColumn(i);
            }
            newDS = newDS.selectExpr(expressions);
            return new NativeSparkDataSet(newDS, this.context, false);

        } catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            context.popScope();
        }
    }

    @Override
    public boolean isNativeSpark() {
        return true;
    }

    @Override
    public List<String> buildNativeSparkExplain(ExplainNode.SparkExplainKind sparkExplainKind) {
        if (dataset != null) {
            String [] arrOfStr = null;
            switch (sparkExplainKind) {
                case LOGICAL:
                    arrOfStr =
                    dataset.queryExecution().logical().toString().split("\n");
                    break;
                case OPTIMIZED:
                    arrOfStr =
                    dataset.queryExecution().optimizedPlan().toString().split("\n");
                    break;
                case ANALYZED:
                    arrOfStr =
                    dataset.queryExecution().analyzed().toString().split("\n");
                    break;
                default:
                    arrOfStr =
                    dataset.queryExecution().executedPlan().toString().split("\n");
                    break;
            }

            // Remove trailing whitespaces.
            for (int i = 0; i < arrOfStr.length; i++) {
                arrOfStr[i] = arrOfStr[i].replaceFirst("\\s++$", "");
            }
            return Arrays.asList(arrOfStr);
        }
        List<String> warnMsg = new ArrayList<>();
        warnMsg.add("Spark EXPLAIN not available.\n");
        return warnMsg;
    }

}
