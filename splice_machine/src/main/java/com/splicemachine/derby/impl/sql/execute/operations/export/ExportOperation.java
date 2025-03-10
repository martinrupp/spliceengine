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

package com.splicemachine.derby.impl.sql.execute.operations.export;

import com.splicemachine.db.iapi.types.SQLLongint;
import com.splicemachine.si.api.txn.TxnView;
import splice.com.google.common.base.Strings;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.impl.sql.compile.ExportNode;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.iapi.sql.execute.*;
import com.splicemachine.derby.impl.sql.execute.operations.SpliceBaseOperation;
import com.splicemachine.derby.stream.function.ExportFunction;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.derby.stream.iapi.OperationContext;
import com.splicemachine.derby.stream.output.DataSetWriter;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.*;
import java.util.Collections;
import java.util.List;

/**
 * Export the results of an arbitrary SELECT query to HDFS.
 */
public class ExportOperation extends SpliceBaseOperation {

    private static final long serialVersionUID = 0L;

    private SpliceOperation source;
    private ResultColumnDescriptor[] sourceColumnDescriptors;
    private ExportParams exportParams;

    private ExecRow currentTemplate;

    protected static final String NAME = ExportOperation.class.getSimpleName().replaceAll("Operation","");
    private static final Logger LOG = Logger.getLogger(ExportOperation.class);

	@Override
	public String getName() {
			return NAME;
	}
    
    public ExportOperation() {
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",justification = "Intentional")
    public ExportOperation(SpliceOperation source,
                           ResultColumnDescriptor[] sourceColumnDescriptors,
                           Activation activation,
                           int rsNumber,
                           String exportPath,
                           String compression,
                           String format,
                           int replicationCount,
                           String encoding,
                           String fieldSeparator,
                           String quoteCharacter,
                           String quoteMode,
                           String floatingPointNotation,
                           String timestampFormat) throws StandardException {
        super(activation, rsNumber, 0d, 0d);

        if (replicationCount <= 0 && replicationCount != ExportNode.DEFAULT_INT_VALUE) {
            throw StandardException.newException(SQLState.EXPORT_PARAMETER_IS_WRONG);
        }

        this.source = source;
        this.sourceColumnDescriptors = sourceColumnDescriptors;
        this.exportParams = new ExportParams(exportPath, compression, format, replicationCount, encoding,
                fieldSeparator, quoteCharacter, quoteMode, floatingPointNotation, timestampFormat);
        this.activation = activation;
        init();
    }

    @Override
    public void init(SpliceOperationContext context) throws StandardException, IOException {
        super.init(context);
        source.init(context);
        currentTemplate = new ValueRow(0);
    }

    @Override
    public SpliceOperation getLeftOperation() {
        return source;
    }

    @Override
    public List<SpliceOperation> getSubOperations() {
        return Collections.singletonList(source);
    }

    @Override
    public ExecRow getExecRowDefinition() throws StandardException {
        return currentTemplate;
    }

    @Override
    public String prettyPrint(int indentLevel) {
        String indent = "\n" + Strings.repeat("\t", indentLevel);
        return indent + "resultSetNumber:" + resultSetNumber + indent
                + "source:" + source.prettyPrint(indentLevel + 1);
    }

    @Override
    public int[] getRootAccessedCols(long tableNumber) throws StandardException {
        return source.getRootAccessedCols(tableNumber);
    }

    @Override
    public boolean isReferencingTable(long tableNumber) {
        return source.isReferencingTable(tableNumber);
    }

    // - - - - - - - - - - - -
    // export only methods
    // - - - - - - - - - - - -

    public ExportParams getExportParams() {
        return exportParams;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",justification = "Intentional")
    public ResultColumnDescriptor[] getSourceResultColumnDescriptors() {
        return this.sourceColumnDescriptors;
    }

    @SuppressWarnings("unchecked")
    @Override
    public DataSet<ExecRow> getDataSet(DataSetProcessor dsp) throws StandardException {
        try {
            ExportPermissionCheck checker = new ExportPermissionCheck(exportParams);
            checker.verify();
            checker.cleanup();

        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }

        if (!isOpen)
            throw new IllegalStateException("Operation is not open");

        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "getDataSet(): begin");
        dsp.incrementOpDepth();
        DataSet<ExecRow> dataset = source.getDataSet(dsp);
        dsp.decrementOpDepth();
        OperationContext<ExportOperation> operationContext = dsp.createOperationContext(this);
        dsp.prependSpliceExplainString(this.explainPlan);
        if (!dsp.isSparkExplain()) {
            String format = exportParams.getFormat();
            if(format.equalsIgnoreCase("csv")) {
                return writeCsv(dataset, operationContext);
            }
            else {
                return writeFileX(dsp, dataset, format);
            }
        }
        else
            return dataset;
    }

    private DataSet<ExecRow> writeFileX(DataSetProcessor dsp, DataSet<ExecRow> dataset,
                                          String extension) throws StandardException
    {
        OperationContext<?> writeContext = dsp.createOperationContext(this);
        long start = System.currentTimeMillis();
        String compression = null;
        if (exportParams.getCompression() == ExportFile.COMPRESSION.SNAPPY) {
            compression = "snappy";
        } else if (exportParams.getCompression() == ExportFile.COMPRESSION.NONE) {
            compression = "none";
        }

        if(extension.equalsIgnoreCase("parquet")) {
            dataset.writeParquetFile(new int[]{}, exportParams.getDirectory(),
                    compression, writeContext);
        }
        else if(extension.equalsIgnoreCase("orc")) {
            dataset.writeORCFile(new int[]{}, exportParams.getDirectory(),
                    compression, writeContext);
        }
        else {
            throw new RuntimeException("Unsupported export format " + extension);
        }

        long end = System.currentTimeMillis();
        ValueRow vr = new ValueRow(2);
        vr.setColumn(1, new SQLLongint(writeContext.getRecordsWritten()));
        vr.setColumn(2, new SQLLongint(end - start));
        return dsp.singleRowDataSet(vr);
    }

    private DataSet<ExecRow> writeCsv(DataSet<ExecRow> dataset,
                                      OperationContext<ExportOperation> operationContext) throws StandardException
    {
        DataSetWriter writer = dataset.writeToDisk()
            .directory(exportParams.getDirectory())
            .exportFunction(new ExportFunction(operationContext))
            .build();
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG, "getDataSet(): writing");
        operationContext.pushScope();
        try {
            DataSet<ExecRow> resultDs = writer.write();
            if (LOG.isTraceEnabled())
                SpliceLogUtils.trace(LOG, "getDataSet(): done");
            return resultDs;
        } finally {
            operationContext.popScope();
        }
    }

    @Override
    public TxnView getCurrentTransaction() throws StandardException{
        return source.getCurrentTransaction();
    }
}
