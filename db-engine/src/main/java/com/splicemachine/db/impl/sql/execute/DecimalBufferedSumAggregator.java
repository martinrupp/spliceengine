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

package com.splicemachine.db.impl.sql.execute;

import com.google.protobuf.ByteString;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecAggregator;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.SQLDecimal;
import com.splicemachine.db.impl.sql.CatalogMessage;

import java.io.*;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;

import static com.splicemachine.db.iapi.types.SQLDecimal.getBigDecimal;

/**
 * @author Scott Fines
 *         Date: 5/16/14
 */
public class DecimalBufferedSumAggregator extends SumAggregator {
    private BigDecimal[] buffer;
    private int length;
    private int position;

    private BigDecimal sum = BigDecimal.ZERO;
    private boolean isNull = true;
    private int bufferSize;

    public DecimalBufferedSumAggregator() { // SERDE

    }

    public DecimalBufferedSumAggregator(CatalogMessage.SystemAggregator agg) throws IOException, ClassNotFoundException {
        init(agg);
    }

    public DecimalBufferedSumAggregator(int bufferSize) {
        init(bufferSize);
    }

    private void init(int bufferSize) {
        int s = 1;
        while(s<bufferSize){
            s<<=1;
        }
        buffer = new BigDecimal[s];
        this.length = s-1;
        position = 0;
        this.bufferSize = bufferSize;
    }
    @Override
    protected void accumulate(DataValueDescriptor addend) throws StandardException {
        buffer[position] = getBigDecimal(addend);
        incrementPosition();
    }


    @Override
    public void merge(ExecAggregator addend) throws StandardException {
        if(addend==null) return; //treat null entries as zero
        //In Splice, we should never see a different type of an ExecAggregator
        DecimalBufferedSumAggregator other = (DecimalBufferedSumAggregator)addend;
        if (other.isNull){
            return;
        }

        if (!Objects.equals(other.sum, BigDecimal.ZERO)) {
            buffer[position] = other.sum;
            incrementPosition();
        }
        for (int i = 0; i< other.position;i++) {
            buffer[position] = other.buffer[i];
            incrementPosition();
        }
    }

    @Override
    protected void writeExternalOld(ObjectOutput out) throws IOException {
        //Need to sum up all the intermediate values before serializing
        if(position!=0){
            try {
                sum(position);
            } catch (StandardException e) {
                throw new IOException(e);
            }
            position=0;
        }
        out.writeBoolean(eliminatedNulls);
        out.writeBoolean(isNull);
        out.writeObject(sum);
    }

    @Override
    protected void readExternalOld(ObjectInput in) throws IOException, ClassNotFoundException {
        this.eliminatedNulls = in.readBoolean();
        this.isNull = in.readBoolean();
        this.sum = (BigDecimal)in.readObject();
    }

    @Override
    protected CatalogMessage.SystemAggregator.Builder toProtobufBuilder() throws IOException {
        if(position!=0){
            try {
                sum(position);
            } catch (StandardException e) {
                throw new IOException(e);
            }
            position=0;
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream os = new ObjectOutputStream(bos)) {
            os.writeObject(sum);
            os.flush();
            byte[] bs = bos.toByteArray();

            CatalogMessage.DecimalBufferedSumAggregator aggregator =
                    CatalogMessage.DecimalBufferedSumAggregator.newBuilder()
                            .setIsNull(isNull)
                            .setSum(ByteString.copyFrom(bs))
                            .setBufferSize(bufferSize)
                            .build();

            CatalogMessage.SystemAggregator.Builder builder = super.toProtobufBuilder();
            builder.setType(CatalogMessage.SystemAggregator.Type.DecimalBufferedSumAggregator)
                    .setExtension(CatalogMessage.DecimalBufferedSumAggregator.decimalBufferedSumAggregator, aggregator);
            return builder;
        }
    }

    @Override
    protected void init(CatalogMessage.SystemAggregator systemAggregator) throws IOException, ClassNotFoundException {
        super.init(systemAggregator);
        CatalogMessage.DecimalBufferedSumAggregator aggregator =
                systemAggregator.getExtension(CatalogMessage.DecimalBufferedSumAggregator.decimalBufferedSumAggregator);
        isNull = aggregator.getIsNull();
        byte[] ba = aggregator.getSum().toByteArray();
        try (ByteArrayInputStream bis = new ByteArrayInputStream(ba);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            this.sum =    (BigDecimal)ois.readObject();
        }
        init(aggregator.getBufferSize());
    }

    @Override
    public DataValueDescriptor getResult() throws StandardException {
        if (value == null) {
            value = new SQLDecimal();
        }
        if(isNull){
            value.setToNull();
            return value;
        }
        if(position!=0){
            sum(position);
            position=0;
        }
        value.setBigDecimal(sum);
        return value;
    }

    /**
     * Can only be safely called after first calling getResult();
     * e.g. after GenericAggregator.finish() has been called
     * @return the current sum;
     */
    public BigDecimal getSum(){
        assert position==0: "There are entries still to be buffered!";
        return sum;
    }

    public void init(BigDecimal sum,boolean eliminatedNulls){
        this.sum = sum;
        this.eliminatedNulls = eliminatedNulls;
        this.isNull=false;
    }

    @Override
    public ExecAggregator newAggregator() {
        return new DecimalBufferedSumAggregator(buffer.length);
    }

    private void sum(int bufferLength) throws StandardException {
        for (int i=0;i<bufferLength;i++) {
            BigDecimal l = buffer[i];
            sum=sum.add(l);
        }
    }

    private void incrementPosition() throws StandardException {
        int newposition = (position+1) & length;
        if(newposition==0){
            sum(buffer.length);
        }
        isNull=false;
        position = newposition;
    }

    public void addDirect(BigDecimal bigDecimal) throws StandardException {
        buffer[position] = bigDecimal;
        incrementPosition();
    }

    public String toString() {
        String bufferInfo = isNull ? null : (position < 25 && position > 0 ?
                Arrays.toString(Arrays.copyOfRange(buffer, 0, position))
                : String.format("%s buffered", position));
        return "DecimalBufferedSumAggregator: " + (isNull ? "NULL" :
                String.format("{ sum=%s buffer=%s }", sum, bufferInfo));
    }


}
