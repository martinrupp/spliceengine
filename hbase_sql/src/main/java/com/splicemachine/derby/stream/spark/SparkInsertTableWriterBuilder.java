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

package com.splicemachine.derby.stream.spark;

import java.io.IOException;

import com.splicemachine.derby.stream.iapi.DataSet;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.spark.api.java.JavaPairRDD;

import com.splicemachine.access.HConfiguration;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.derby.stream.output.DataSetWriter;
import com.splicemachine.derby.stream.output.insert.InsertTableWriterBuilder;
import com.splicemachine.derby.stream.utils.TableWriterUtils;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.stream.output.SMOutputFormat;
import scala.util.Either;

/**
 * @author Scott Fines
 *         Date: 1/25/16
 */
public class SparkInsertTableWriterBuilder<K,V> extends InsertTableWriterBuilder{
    private DataSet dataSet;

    public SparkInsertTableWriterBuilder(DataSet dataSet){
        this.dataSet = dataSet;
    }

    public SparkInsertTableWriterBuilder(){
    }

    @SuppressWarnings("unchecked")
    @Override
    public DataSetWriter build() throws StandardException{
        if(operationContext.getOperation()!=null){
            operationContext.getOperation().fireBeforeStatementTriggers();
        }
        final Configuration conf=new Configuration(HConfiguration.unwrapDelegate());
        try{
            // workaround for SPARK-21549 on spark-2.2.0
            conf.set("mapreduce.output.fileoutputformat.outputdir","/tmp");
            TableWriterUtils.serializeInsertTableWriterBuilder(conf,this);
        }catch(IOException e){
            throw Exceptions.parseException(e);
        }
        conf.setClass(JobContext.OUTPUT_FORMAT_CLASS_ATTR,SMOutputFormat.class,SMOutputFormat.class);
        return new InsertDataSetWriter<>(dataSet,
                operationContext,
                conf,
                pkCols,
                tableVersion,
                execRowDefinition,
                autoIncrementRowLocationArray,
                spliceSequences,
                heapConglom,
                isUpsert,
                sampleFraction,
                updateCounts);
    }
}
