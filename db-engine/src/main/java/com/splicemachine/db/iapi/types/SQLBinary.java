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

package com.splicemachine.db.iapi.types;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.splicemachine.db.catalog.types.TypeMessage;
import com.splicemachine.db.iapi.services.io.*;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.conn.StatementContext;
import com.splicemachine.db.iapi.reference.ContextId;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.reference.MessageId;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.services.i18n.MessageService;
import com.splicemachine.db.iapi.services.cache.ClassSize;
import org.apache.datasketches.theta.UpdateSketch;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;

import java.io.*;
import java.sql.*;

/**
 * SQLBinary is the abstract class for the binary datatypes.
 * <UL>
 * <LI> CHAR FOR BIT DATA
 * <LI> VARCHAR FOR BIT DATA
 * <LI> LONG VARCHAR
 * <LI> BLOB
 * </UL>

  <P>
  Format : <encoded length><raw data>
  <BR>
  Length is encoded to support Cloudscape 5.x databases where the length was stored as the number of bits.
  The first bit of the first byte indicates if the format is an old (Cloudscape 5.x) style or a new Derby style.
  Derby then uses the next two bits to indicate how the length is encoded.
  <BR>
  <encoded length> is one of N styles.
  <UL>
  <LI> (5.x format zero) 4 byte Java format integer value 0 - either <raw data> is 0 bytes/bits  or an unknown number of bytes.
  <LI> (5.x format bits) 4 byte Java format integer value >0 (positive) - number of bits in <raw data>, number of bytes in <raw data>
  is the minimum number of bytes required to store the number of bits.
  <LI> (Derby format) 1 byte encoded length (0 <= L <= 31) - number of bytes of <raw data> - encoded = 0x80 & L
  <LI> (Derby format) 3 byte encoded length (32 <= L < 64k) - number of bytes of <raw data> - encoded = 0xA0 <L as Java format unsigned short>
  <LI> (Derby format) 5 byte encoded length (64k <= L < 2G) - number of bytes of <raw data> - encoded = 0xC0 <L as Java format integer>
  <LI> (future) to be determined L >= 2G - encoded 0xE0 <encoding of L to be determined>
  (0xE0 is an esacape to allow any number of arbitary encodings in the future).
  </UL>
  <BR>
  When the value was written from a byte array the Derby encoded byte
  length format was always used from Derby 10.0 onwards (ie. all open
  source versions).
  <BR>
  When the value was written from a stream (e.g. PreparedStatement.setBinaryStream)
  then the Cloudscape '5.x format zero' was used by 10.0 and 10.1.
  The was due to the class RawToBinaryFormatStream always writing
  four zero bytes for the length before the data.
  <BR>
  The Cloudscape '5.x format bits' format I think was never used by Derby.
 */
abstract class SQLBinary
    extends DataType implements BitDataValue
{

    static final byte PAD = (byte) 0x20;

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( SQLBinary.class);

    private static final int LEN_OF_BUFFER_TO_WRITE_BLOB = 1024;

    public int estimateMemoryUsage()
    {
        if (dataValue == null) {
            if (streamValueLength>=0) {
                return BASE_MEMORY_USAGE + streamValueLength;
            } else {
                return getMaxMemoryUsage();
            }
        } else {
            return BASE_MEMORY_USAGE + dataValue.length;
        }
    } // end of estimateMemoryUsage


    /**
     * Return max memory usage for a SQL Binary
     */
    abstract int getMaxMemoryUsage();

     /*
     * value as a blob
     */
    Blob _blobValue;
    
     /*
     * object state
     */
    byte[] dataValue;

    /**
     * Value as a stream, this stream represents the on-disk
     * format of the value. That is it has length information
     * encoded in the first fe bytes.
     */
    InputStream stream;

    /**
        Length of the value in bytes when this value
        is set as a stream. Represents the length of the
        value itself and not the length of the stream
        which contains this length encoded as the first
        few bytes. If the value of the stream is unknown
        then this will be set to -1. If this value is
        not set as a stream then this value should be ignored.
    */
    int streamValueLength;

    /**
        Create a binary value set to NULL
    */
    SQLBinary()
    {
    }

    SQLBinary(byte[] val)
    {
        setValue(val);
    }

    SQLBinary(Blob val)
    {
        setValue( val );
    }

   

    public final void setValue(byte[] theValue)
    {
        dataValue = theValue;
        _blobValue = null;
        stream = null;
        streamValueLength = -1;
        isNull = evaluateNull();
    }

    public final void setValue(Blob theValue)
    {
        dataValue = null;
        _blobValue = theValue;
        stream = null;
        streamValueLength = -1;
        isNull = evaluateNull();
    }

    @Override
    public void setValue(String value) throws StandardException {
        setValue(value==null?null:com.splicemachine.db.iapi.util.StringUtil.fromHexString(value,0,value.length()));
    }

    /**
     * Used by JDBC -- string should not contain
     * SQL92 formatting.
     *
     * @exception StandardException        Thrown on error
     */
    public final String    getString() throws StandardException
    {
        if (getValue() == null)
            return null;
        else if (dataValue.length * 2 < 0)  //if converted to hex, length exceeds max int
        {
            throw StandardException.newException(SQLState.LANG_STRING_TRUNCATION, getTypeName(),
                                    "",
                                    String.valueOf(Integer.MAX_VALUE));
        }
        else
        {
            return com.splicemachine.db.iapi.util.StringUtil.toHexString(dataValue, 0, dataValue.length);
        }
    }


    /**
     * @exception StandardException        Thrown on error
     */
    public final InputStream    getStream() throws StandardException
    {
        if (!hasStream()) {
            throw StandardException.newException(
                    SQLState.LANG_STREAM_INVALID_ACCESS, getTypeName());
        }
        return (stream);
    }

    /**
     *
     * @exception StandardException        Thrown on error
     */
    public final byte[]    getBytes() throws StandardException
    {
        return getValue();
    }

    byte[] getValue() throws StandardException
    {
        try
        {
            if ((dataValue == null) && (_blobValue != null) )
            {
                dataValue = _blobValue.getBytes( 1L,  getBlobLength() );
                
                _blobValue = null;
                 stream = null;
                streamValueLength = -1;
            }
            else if ((dataValue == null) && (stream != null) )
            {
                if (stream instanceof FormatIdInputStream) {
                    readExternal((FormatIdInputStream) stream);
                }
                else {
                    readExternal(new FormatIdInputStream(stream));
                }
                _blobValue = null;
                 stream = null;
                streamValueLength = -1;

            }
        }
        catch (IOException ioe)
        {
            throwStreamingIOException(ioe);
        }
        catch (SQLException se) { throw StandardException.plainWrapException( se ); }

        isNull = evaluateNull();
        return dataValue;
    }

    /**
     * length in bytes
     *
     * @exception StandardException        Thrown on error
     */
    public final int    getLength() throws StandardException
    {
        if ( _blobValue != null ) { return getBlobLength(); }
        else if (stream != null) {
            if (streamValueLength != -1)
                return streamValueLength;
            else if (stream instanceof Resetable){
                try {
                    // If we have the stream length encoded.
                    // just read that.
                    streamValueLength = readBinaryLength((ObjectInput) stream);
                    if (streamValueLength == 0) {
                        // Otherwise we will have to read the whole stream.
                        streamValueLength =
                                (int) InputStreamUtil.skipUntilEOF(stream);
                    }
                    return streamValueLength;
                }
                catch (IOException ioe) {
                    throwStreamingIOException(ioe);
                }
                finally {
                    try {
                        ((Resetable) stream).resetStream();
                    } catch (IOException ioe) {
                        throwStreamingIOException(ioe);
                    }
                }

            }
        }
        byte[] bytes = getBytes();
        return (bytes == null) ? 0 : bytes.length;

    }


    private void throwStreamingIOException(IOException ioe) throws StandardException {
        throw StandardException.
            newException(SQLState.LANG_STREAMING_COLUMN_I_O_EXCEPTION,
                         ioe, getTypeName());
    }

    /*
     * Storable interface, implies Externalizable, TypedFormat
     */


    /**
     * see if the Bit value is null.
     * @see com.splicemachine.db.iapi.services.io.Storable#isNull
     */

    private boolean evaluateNull()
    {
        return (dataValue == null) && (stream == null) && (_blobValue == null);
    }

    @Override
    public TypeMessage.DataValueDescriptor toProtobuf() throws IOException {
        TypeMessage.SQLBinary.Builder builder = TypeMessage.SQLBinary.newBuilder();
        builder.setIsNull(evaluateNull());
        if (!evaluateNull()) {
            if (_blobValue != null) {
                try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                     ObjectOutputStream os = new ObjectOutputStream(bos)) {
                    writeBlob(os);
                    os.flush();
                    byte[] bs = bos.toByteArray();
                    builder.setBlob(ByteString.copyFrom(bs));
                }
            }
            else {
                builder.setDataValue(ByteString.copyFrom(dataValue));
            }
        }
        if (this instanceof SQLBit) {
            builder.setType(TypeMessage.SQLBinary.Type.SQLBit);
        } else if (this instanceof SQLBlob) {
            builder.setType(TypeMessage.SQLBinary.Type.SQLBlob);
        } else if (this instanceof SQLLongVarbit) {
            builder.setType(TypeMessage.SQLBinary.Type.SQLLongVarbit);
        } else if (this instanceof SQLVarbit) {
            builder.setType(TypeMessage.SQLBinary.Type.SQLVarbit);
        }

        TypeMessage.DataValueDescriptor dvd =
                TypeMessage.DataValueDescriptor.newBuilder()
                        .setType(TypeMessage.DataValueDescriptor.Type.SQLBinary)
                        .setExtension(TypeMessage.SQLBinary.sqlBinary, builder.build())
                        .build();

        return dvd;
    }

    /**
        Serialize a blob using the 8.1 encoding. Not called if null.

     * @exception IOException        io exception
     */
    private void writeBlob(ObjectOutput out) throws IOException
    {
        try {
            int                 len = getBlobLength();
            InputStream         is = _blobValue.getBinaryStream();
            
            writeLength( out, len );
            
            int bytesRead = 0;
            int numOfBytes = 0;
            byte[] buffer = new byte[Math.min(len, LEN_OF_BUFFER_TO_WRITE_BLOB)];
            
            while(bytesRead < len) {
                numOfBytes = is.read(buffer);
                
                if (numOfBytes == -1) {
                    throw new DerbyIOException(
                        MessageService.getTextMessage(
                                SQLState.SET_STREAM_INEXACT_LENGTH_DATA),
                            SQLState.SET_STREAM_INEXACT_LENGTH_DATA);
                }
                
                out.write(buffer, 0, numOfBytes);
                bytesRead += numOfBytes; 
            }
        }
        catch (StandardException | SQLException se) { throw new IOException( se.getMessage() ); }
    }
    
    /**
        Write the length if
        using the 8.1 encoding.

     * @exception IOException        io exception
     */
    private void writeLength( ObjectOutput out, int len ) throws IOException
    {
        if (len <= 31)
        {
            out.write((byte) (0x80 | (len & 0xff)));
        }
        else if (len <= 0xFFFF)
        {
            out.write((byte) 0xA0);
            out.writeShort((short) len);
        }
        else
        {
            out.write((byte) 0xC0);
            out.writeInt(len);

        }
    }

    /**
     Write the value out from the byte array (not called if null)
     using the 8.1 encoding.

     * @exception IOException        io exception
     */
    @Override
    protected final void writeExternalOld(ObjectOutput out) throws IOException {
        out.writeBoolean(evaluateNull());
        if (evaluateNull())
            return;
        if ( _blobValue != null )
        {
            writeBlob(  out );
            return;
        }
        int len = dataValue.length;

        writeLength( out, len );
        out.write(dataValue, 0, dataValue.length);
    }

    /**
     * delegated to bit
     *
     * @exception IOException            io exception
     * @exception ClassNotFoundException    class not found
    */
    @Override
    public void readExternal(ObjectInput in) throws IOException {
        if (in instanceof FormatIdInputStream || DataInputUtil.shouldReadOldFormat()) {
            readExternalOld(in);
        }
        else {
            readExternalNew(in);
        }
    }

    @Override
    protected void readExternalNew(ObjectInput in) throws IOException {

        byte[] bs = ArrayUtil.readByteArray(in);
        ExtensionRegistry extensionRegistry = ProtobufUtils.getExtensionRegistry();
        TypeMessage.DataValueDescriptor dvd = TypeMessage.DataValueDescriptor.parseFrom(bs, extensionRegistry);
        TypeMessage.SQLBinary sqlBinary = dvd.getExtension(TypeMessage.SQLBinary.sqlBinary);
        init(sqlBinary);
    }

    protected void init(TypeMessage.SQLBinary sqlBinary) {
        // need to clear stream first, in case this object is reused, and
        // stream is set by previous use.  Track 3794.
        stream = null;
        streamValueLength = -1;
        _blobValue = null;

        isNull = sqlBinary.getIsNull();
        if (isNull) {
            return;
        }

        if (sqlBinary.hasDataValue())
        {
            dataValue = sqlBinary.getDataValue().toByteArray();
        }
        else if (sqlBinary.hasBlob()) {
            byte[] ba = sqlBinary.getBlob().toByteArray();
            try (ByteArrayInputStream bis = new ByteArrayInputStream(ba);
                 ObjectInputStream ois = new ObjectInputStream(bis)) {
                readFromStream(ois);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }

        }
        isNull = evaluateNull();
    }

    @Override
    protected void readExternalOld(ObjectInput in) throws IOException {
        // need to clear stream first, in case this object is reused, and
        // stream is set by previous use.  Track 3794.
        stream = null;
        streamValueLength = -1;
        _blobValue = null;

        if (in instanceof FormatIdInputStream) {

        } else {
            isNull = in.readBoolean();
            if (isNull) {
                return;
            }
        }


        int len = SQLBinary.readBinaryLength(in);

        if (len != 0)
        {
            dataValue = new byte[len];
            in.readFully(dataValue);
        }
        else
        {
            readFromStream((InputStream) in);
        }
        isNull = evaluateNull();
    }

    public final void readExternalFromArray(ArrayInputStream in) throws IOException
    {
        // need to clear stream first, in case this object is reused, and
        // stream is set by previous use.  Track 3794.
        stream = null;
        streamValueLength = -1;
        _blobValue = null;

/*        isNull = in.readBoolean();
        if (isNull) {
            return;
        }
*/
        int len = SQLBinary.readBinaryLength(in);

        if (len != 0)
        {
            dataValue = new byte[len];
            in.readFully(dataValue);
        }
        else
        {
            readFromStream(in);
        }
        isNull = evaluateNull();
    }

    /**
     * Read the encoded length of the value from the on-disk format.
     * 
     * @see SQLBinary
    */
    private static int readBinaryLength(ObjectInput in) throws IOException {

        int bl = in.read();
        if (bl == -1)
            throw new java.io.EOFException();
        
        byte li = (byte) bl;

        int len;
        if ((li & ((byte) 0x80)) != 0)
        {
            if (li == ((byte) 0xC0))
            {
                len = in.readInt();
             }
            else if (li == ((byte) 0xA0))
            {
                len = in.readUnsignedShort();
            }
            else
            {
                len = li & 0x1F;
            }
        }
        else
        {
            
            // old length in bits
            int v2 = in.read();
            int v3 = in.read();
            int v4 = in.read();
            if (v2 == -1 || v3 == -1 || v4 == -1)
                throw new java.io.EOFException();
            int lenInBits = (((bl & 0xff) << 24) | ((v2 & 0xff) << 16) | ((v3 & 0xff) << 8) | (v4 & 0xff));

            len = lenInBits / 8;
            if ((lenInBits % 8) != 0)
                len++;
         }
        return len;
    }

    /**
     * Read the value from an input stream. The length
     * encoded in the input stream has already been read
     * and determined to be unknown.
     */
    private void readFromStream(InputStream in) throws IOException {

        dataValue = null;    // allow gc of the old value before the new.
        byte[] tmpData = new byte[32 * 1024];

        int off = 0;
        for (;;) {

            int len = in.read(tmpData, off, tmpData.length - off);
            if (len == -1)
                break;
            off += len;

            int available = Math.max(1, in.available());
            int extraSpace = available - (tmpData.length - off);
            if (extraSpace > 0)
            {
                // need to grow the array
                int size = tmpData.length * 2;
                if (extraSpace > tmpData.length)
                    size += extraSpace;

                byte[] grow = new byte[size];
                System.arraycopy(tmpData, 0, grow, 0, off);
                tmpData = grow;
            }
        }

        dataValue = new byte[off];
        System.arraycopy(tmpData, 0, dataValue, 0, off);
        isNull = evaluateNull();
    }

    /**
     * @see com.splicemachine.db.iapi.services.io.Storable#restoreToNull
     */
    public final void restoreToNull()
    {
        dataValue = null;
        _blobValue = null;
        stream = null;
        streamValueLength = -1;
        isNull = true;
    }

    /**
        @exception StandardException thrown on error
     */
    public final boolean compare(int op,
                           DataValueDescriptor other,
                           boolean orderedNulls,
                           boolean unknownRV)
        throws StandardException
    {
        if (!orderedNulls)        // nulls are unordered
        {
            if (SanityManager.DEBUG)
            {
                int otherTypeFormatId = other.getTypeFormatId();
                if (!((StoredFormatIds.SQL_BIT_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_VARBIT_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_LONGVARBIT_ID == otherTypeFormatId)

                      || (StoredFormatIds.SQL_CHAR_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_VARCHAR_ID == otherTypeFormatId)
                      || (StoredFormatIds.SQL_LONGVARCHAR_ID == otherTypeFormatId)

                      || ((StoredFormatIds.SQL_BLOB_ID == otherTypeFormatId)
                          && (StoredFormatIds.SQL_BLOB_ID == getTypeFormatId()))
                        ))
                SanityManager.THROWASSERT(
                        "An object of type " + other.getClass().getName() +
                        ", with format id " + otherTypeFormatId +
                        ", was passed to SQLBinary.compare()");
            }
            if (this.isNull() || other.isNull())
                return unknownRV;
        }
        /* Do the comparison */
        return super.compare(op, other, orderedNulls, unknownRV);
    }

    /**
        @exception StandardException thrown on error
     */
    public final int compare(DataValueDescriptor other) throws StandardException
    {

        /* Use compare method from dominant type, negating result
         * to reflect flipping of sides.
         */
        if (typePrecedence() < other.typePrecedence())
        {
            return compare(other);
        }

        /*
        ** By convention, nulls sort High, and null == null
        */
        if (this.isNull() || other.isNull())
        {
            if (!isNull())
                return -1;
            if (!other.isNull())
                return 1;
            return 0;                            // both null
        }

        return SQLBinary.compare(getBytes(), other.getBytes());
    }

    /**
     * Shallow clone a StreamStorable without objectifying.
     * This is used to avoid unnecessary objectifying of a stream object.
     *
     *  Beetle 4896
     */
    public final DataValueDescriptor cloneHolder() {
        if (stream == null && _blobValue == null) {
            return cloneValue(false);
        } else {
            // Cast to SQLBinary to avoid having to catch StandardException.
            SQLBinary self = (SQLBinary)getNewNull();
            if (stream != null) {
                // Just reference the same stream in the cloned holder.
                self.setValue(stream, streamValueLength);
            } else if (_blobValue != null) {
                // Just reference the same BLOB value in the cloned holder.
                self.setValue(_blobValue);
            } else {
                throw new IllegalStateException("unknown BLOB value repr");
            }
            return self;
        }
    }

    /*
     * DataValueDescriptor interface
     */

    /** @see DataValueDescriptor#cloneValue */
    public DataValueDescriptor cloneValue(boolean forceMaterialization)
    {
        try
        {
            DataValueDescriptor cloneDVD = getNewNull();
            cloneDVD.setValue(getValue());
            return cloneDVD;
        }
        catch (StandardException se)
        {
            if (SanityManager.DEBUG)
                SanityManager.THROWASSERT("Unexpected exception", se);
            return null;
        }
    }

    /*
     * DataValueDescriptor interface
     */

    /*
     * StreamStorable interface :
     */
    public final InputStream returnStream()
    {
        return stream;
    }

    /**
     * Set me to the value represented by this stream.
     * The format of the stream is the on-disk format
     * described in this class's javadoc. That is the
     * length is encoded in the first few bytes of the
     * stream.
     */
    public final void setStream(InputStream newStream)
    {
        this.dataValue = null;
        _blobValue = null;
        this.stream = newStream;
        streamValueLength = -1;
        isNull = evaluateNull();
    }

    public final void loadStream() throws StandardException
    {
        getValue();
    }

    /*
     * class interface
     */

    boolean objectNull(Object o) 
    {
        if (o == null)
        {
            setToNull();
            return true;
        }
        return false;
    }

    /**
     * Set the value from the stream which is in the on-disk format.
     * @param theStream On disk format of the stream
     * @param valueLength length of the logical value in bytes, or
     *      <code>DataValueDescriptor.UNKNOWN_LOGICAL_LENGTH</code>
     */
    public final void setValue(InputStream theStream, int valueLength)
    {
        dataValue = null;
        _blobValue = null;
        stream = theStream;
        this.streamValueLength = valueLength;
        isNull = evaluateNull();
    }

    protected final void setFrom(DataValueDescriptor theValue) throws StandardException {

        if (theValue instanceof SQLBinary)
        {
            SQLBinary theValueBinary = (SQLBinary) theValue;
            dataValue = theValueBinary.dataValue;
            _blobValue = theValueBinary._blobValue;
            stream = theValueBinary.stream;
            streamValueLength = theValueBinary.streamValueLength;
        }
        else
        {
            setValue(theValue.getBytes());
        }
        isNull = evaluateNull();
    }

    /*
    ** SQL Operators
    */

    /**
     * The = operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left            The value on the left side of the =
     * @param right            The value on the right side of the =
     *                        is not.
     * @return    A SQL boolean value telling whether the two parameters are equal
     *
     * @exception StandardException        Thrown on error
     */

    public final BooleanDataValue equals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException {
        boolean isEqual;

        isEqual = !(left.isNull() || right.isNull()) && SQLBinary.compare(left.getBytes(), right.getBytes()) == 0;

        return SQLBoolean.truthValue(left,
                right,
                isEqual);
    }

    /**
     * The <> operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left            The value on the left side of the <>
     * @param right            The value on the right side of the <>
     *
     * @return    A SQL boolean value telling whether the two parameters
     * are not equal
     *
     * @exception StandardException        Thrown on error
     */

    public final BooleanDataValue notEquals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException {
        boolean isNotEqual;

        isNotEqual = !(left.isNull() || right.isNull()) && SQLBinary.compare(left.getBytes(), right.getBytes()) != 0;

        return SQLBoolean.truthValue(left,
                right,
                isNotEqual);
    }

    /**
     * The < operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left            The value on the left side of the <
     * @param right            The value on the right side of the <
     *
     * @return    A SQL boolean value telling whether the first operand is
     *            less than the second operand
     *
     * @exception StandardException        Thrown on error
     */

    public final BooleanDataValue lessThan(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException {
        boolean isLessThan;

        isLessThan = !(left.isNull() || right.isNull()) && SQLBinary.compare(left.getBytes(), right.getBytes()) < 0;

        return SQLBoolean.truthValue(left,
                right,
                isLessThan);
    }

    /**
     * The > operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left            The value on the left side of the >
     * @param right            The value on the right side of the >
     *
     * @return    A SQL boolean value telling whether the first operand is
     *            greater than the second operand
     *
     * @exception StandardException        Thrown on error
     */

    public final BooleanDataValue greaterThan(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException {
        boolean isGreaterThan = false;

        isGreaterThan = !(left.isNull() || right.isNull()) && SQLBinary.compare(left.getBytes(), right.getBytes()) > 0;

        return SQLBoolean.truthValue(left,
                right,
                isGreaterThan);
    }

    /**
     * The <= operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left            The value on the left side of the <=
     * @param right            The value on the right side of the <=
     *
     * @return    A SQL boolean value telling whether the first operand is
     *            less than or equal to the second operand
     *
     * @exception StandardException        Thrown on error
     */

    public final BooleanDataValue lessOrEquals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException {
        boolean isLessEquals = false;

        isLessEquals = !(left.isNull() || right.isNull()) && SQLBinary.compare(left.getBytes(), right.getBytes()) <= 0;

        return SQLBoolean.truthValue(left,
                right,
                isLessEquals);
    }

    /**
     * The >= operator as called from the language module, as opposed to
     * the storage module.
     *
     * @param left            The value on the left side of the >=
     * @param right            The value on the right side of the >=
     *
     * @return    A SQL boolean value telling whether the first operand is
     *            greater than or equal to the second operand
     *
     * @exception StandardException        Thrown on error
     */

    public final BooleanDataValue greaterOrEquals(DataValueDescriptor left,
                             DataValueDescriptor right)
                                throws StandardException {
        boolean isGreaterEquals = false;

        isGreaterEquals = !(left.isNull() || right.isNull()) && SQLBinary.compare(left.getBytes(), right.getBytes()) >= 0;

        return SQLBoolean.truthValue(left,
                right,
                isGreaterEquals);
    }


    /**
     *
     * This method implements the char_length function for bit.
     *
     * @param result    The result of a previous call to this method, null
     *                    if not called yet
     *
     * @return    A SQLInteger containing the length of the char value
     *
     * @exception StandardException        Thrown on error
     *
     * @see ConcatableDataValue#charLength
     */

    public final NumberDataValue charLength(NumberDataValue result)
                            throws StandardException
    {
        if (result == null)
        {
            result = new SQLInteger();
        }

        if (this.isNull())
        {
            result.setToNull();
            return result;
        }


        result.setValue(getValue().length);
        return result;
    }

    /**
     * @see BitDataValue#concatenate
     *
     * @exception StandardException        Thrown on error
     */
    public final BitDataValue concatenate(
                BitDataValue left,
                BitDataValue right,
                BitDataValue result)
        throws StandardException
    {
        if (result == null)
        {
            result = (BitDataValue) getNewNull();
        }

        if (left.isNull() || right.isNull())
        {
            result.setToNull();
            return result;
        }

        byte[] leftData = left.getBytes();
        byte[] rightData = right.getBytes();

        byte[] concatData = new byte[leftData.length + rightData.length];

        System.arraycopy(leftData, 0, concatData, 0, leftData.length);
        System.arraycopy(rightData, 0, concatData, leftData.length, rightData.length);


        result.setValue(concatData);
        return result;
    }


    /**
     * The SQL substr() function.
     *
     * @param start        Start of substr
     * @param length    Length of substr
     * @param result    The result of a previous call to this method,
     *                    null if not called yet.
     * @param maxLen    Maximum length of the result
     *
     * @return    A ConcatableDataValue containing the result of the substr()
     *
     * @exception StandardException        Thrown on error
     */
    public final ConcatableDataValue substring(
                NumberDataValue start,
                NumberDataValue length,
                ConcatableDataValue result,
                int maxLen,
                boolean isFixedLength)
        throws StandardException
    {
        int startInt;
        int lengthInt;
        BitDataValue bitResult;

        if (result == null)
        {
            if (isFixedLength)
                result = new SQLBit();
            else
                result = new SQLVarbit();
        }

        bitResult = (BitDataValue) result;

        /* The result is null if the receiver (this) is null or if the length is negative.
         * Oracle docs don't say what happens if the start position or the length is a usernull.
         * We will return null, which is the only sensible thing to do.
         * (If the user did not specify a length then length is not a user null.)
         */
        if (this.isNull() || start.isNull() || (length != null && length.isNull()))
        {
            bitResult.setToNull();
            return bitResult;
        }

        startInt = start.getInt();

        // If length is not specified, make it till end of the string
        if (length != null)
        {
            lengthInt = length.getInt();
        }
        else lengthInt = maxLen - startInt + 1;

        /* DB2 Compatibility: Added these checks to match DB2. We currently enforce these
         * limits in both modes. We could do these checks in DB2 mode only, if needed, so
         * leaving earlier code for out of range in for now, though will not be exercised
         */
        if ((startInt <= 0 || lengthInt < 0 || startInt > maxLen ||
                lengthInt > maxLen - startInt + 1))
            throw StandardException.newException(SQLState.LANG_SUBSTR_START_OR_LEN_OUT_OF_RANGE);

        if (lengthInt == 0)
        {
            bitResult.setValue(new byte[0]);
            return bitResult;
        }

        startInt--;

        byte[] substring;
        if (startInt >= getLength()) {
            if (isFixedLength) {
                substring = new byte[lengthInt];
                java.util.Arrays.fill(substring, 0, substring.length, SQLBinary.PAD);
            } else {
                substring = new byte[0];
            }
        } else if (lengthInt > getLength() - startInt) {
            if (isFixedLength) {
                substring = new byte[lengthInt];
                System.arraycopy(dataValue, startInt, substring, 0, dataValue.length - startInt);
                java.util.Arrays.fill(substring, dataValue.length - startInt, substring.length, SQLBinary.PAD);

            } else {
                substring = new byte[dataValue.length - startInt];
                System.arraycopy(dataValue, startInt, substring, 0, substring.length);
            }
        } else {
            substring = new byte[lengthInt];
            System.arraycopy(dataValue, startInt, substring, 0, substring.length);
        }

        bitResult.setValue(substring);
        return bitResult;
    }

    public ConcatableDataValue replace(
        StringDataValue fromStr,
        StringDataValue toStr,
        ConcatableDataValue result)
        throws StandardException
    {
        throw StandardException.newException(SQLState.NOT_IMPLEMENTED);
    }

    public ConcatableDataValue translate(
            StringDataValue outputTranslationTable,
            StringDataValue inputTranslationTable,
            StringDataValue pad,
            ConcatableDataValue result)
            throws StandardException
    {
        throw StandardException.newException(SQLState.NOT_IMPLEMENTED);
    }

    /**
        Host variables are rejected if their length is
        bigger than the declared length, regardless of
        if the trailing bytes are the pad character.

        @exception StandardException Variable is too big.
    */
    public final void checkHostVariable(int declaredLength) throws StandardException
    {
        // stream length checking occurs at the JDBC layer
        int variableLength = -1;
        if ( _blobValue != null ) { variableLength = -1; }
        else if (stream == null)
        {
            if (dataValue != null)
                variableLength = dataValue.length;
        }
        else
        {
            variableLength = streamValueLength;
        }

        if (variableLength != -1 && variableLength > declaredLength)
                throw StandardException.newException(SQLState.LANG_STRING_TRUNCATION, getTypeName(),
                            MessageService.getTextMessage(
                                MessageId.BINARY_DATA_HIDDEN),
                            String.valueOf(declaredLength));
    }

    /*
     * String display of value
     */

    public String toString()
    {
        if (dataValue == null)
        {
            if ((stream == null) && (_blobValue == null) )
            {
                return "NULL";
            }
            else
            {
                if (SanityManager.DEBUG)
                    SanityManager.THROWASSERT(
                        "value is null, stream or blob is not null");
                return "";
            }
        }
        else
        {
            return com.splicemachine.db.iapi.util.StringUtil.toHexString(dataValue, 0, dataValue.length);
        }
    }

    /*
     * Hash code
     */
    public final int hashCode()
    {
        try {
            if (getValue() == null)
                {
                    return 0;
                }
        }
        catch (StandardException se)
        {
            throw new RuntimeException(se);
        }

        // Hash code should ignore trailing PAD bytes.
        byte[] bytes = dataValue;
        int lastNonPadByte = bytes.length - 1;
        while (lastNonPadByte >= 0 && bytes[lastNonPadByte] == PAD) {
            lastNonPadByte--;
        }

        // Build the hash code in a way similar to String.hashCode() and
        // SQLChar.hashCode()
        int hashcode = 0;
        for (int i = 0; i <= lastNonPadByte; i++) {
            hashcode = hashcode * 31 + bytes[i];
        }

        return hashcode;
    }
    private static int compare(byte[] left, byte[] right) {

        int minLen = left.length;
        byte[] longer = right;
        if (right.length < minLen) {
            minLen = right.length;
            longer = left;
        }

        for (int i = 0; i < minLen; i++) {

            int lb = left[i] & 0xff;
            int rb = right[i] & 0xff;

            if (lb == rb)
                continue;

            return lb - rb;
        }

        // complete match on all the bytes for the smallest value.

        // if the longer value is all pad characters
        // then the values are equal.
        for (int i = minLen; i < longer.length; i++) {
            byte nb = longer[i];
            if (nb == SQLBinary.PAD)
                continue;

            // longer value is bigger.
            if (left == longer)
                return 1;
            return -1;
        }

        return 0;

    }

      /** Adding this method to ensure that super class' setInto method doesn't get called
      * that leads to the violation of JDBC spec( untyped nulls ) when batching is turned on.
      */
     public void setInto(PreparedStatement ps, int position) throws SQLException, StandardException {

                  ps.setBytes(position, getBytes());
     }

    /**
     * Gets a trace representation for debugging.
     *
     * @return a trace representation of this SQL DataType.
     */
    public final String getTraceString() throws StandardException {
        // Check if the value is SQL NULL.
        if (isNull()) {
            return "NULL";
        }

        // Check if we have a stream.
        if (hasStream()) {
            return (getTypeName() + "(" + getStream().toString() + ")");
        }

        if (getLength() < 1 << 10) {
            return (getTypeName() + "(" + getString() + ")");
        }

        return (getTypeName() + ":Length=" + getLength());
    }

    private int getBlobLength() throws StandardException
    {
        try {
            long   maxLength = Integer.MAX_VALUE;
            long   length = _blobValue.length();
            if ( length > Integer.MAX_VALUE )
            {
                throw StandardException.newException
                    ( SQLState.BLOB_TOO_LARGE_FOR_CLIENT, Long.toString( length ), Long.toString( maxLength ) );
            }

            return (int) length;
        }
        catch (SQLException se) { throw StandardException.plainWrapException( se ); }
    }

    /**
     * Truncate this value to the desired width by removing bytes at the
     * end of the byte sequence.
     *
     * @param sourceWidth the original width in bytes (only used for
     *   diagnostics, ignored if {@code warn} is {@code false})
     * @param desiredWidth the desired width in bytes
     * @param warn whether or not to generate a truncation warning
     */
    void truncate(int sourceWidth, int desiredWidth, boolean warn)
            throws StandardException {
        if (warn) {
            // SQL:2003, part 2, 6.12 <cast specification>,
            // general rule 12 says we should warn about truncation.

            DataTruncation warning = new DataTruncation(
                    -1,    // column index is unknown
                    false, // parameter
                    true,  // read
                    getLength(), desiredWidth);

            StatementContext statementContext = (StatementContext)
                ContextService.getContext(ContextId.LANG_STATEMENT);
            // In some cases, the activation will be null on the context,
            // which resulted in NPE when trying to add warning (DB-1288).
            // When this happens, we have no choice but to skip the warning.
            // We can address this later when we more deeply look into
            // error and warning propagation.
            Activation activation = statementContext.getActivation();
            if (activation != null && activation.getResultSet() != null) {
                activation.getResultSet().addWarning(warning);
            }
        }

        // Truncate to the desired width.
        byte[] shrunkData = new byte[desiredWidth];
        System.arraycopy(getValue(), 0, shrunkData, 0, desiredWidth);
        setValue(shrunkData);
    }

    @Override
    public void read(Row row, int ordinal) throws StandardException {
        if (row.isNullAt(ordinal))
            setToNull();
        else {
            isNull = false;
            dataValue = (byte[]) row.get(ordinal);
        }
    }



    @Override
    public StructField getStructField(String columnName) {
        return DataTypes.createStructField(columnName, DataTypes.BinaryType, true);
    }


    public void updateThetaSketch(UpdateSketch updateSketch) {
        updateSketch.update(dataValue);
    }

    @Override
    public void setSparkObject(Object sparkObject) throws StandardException {
        if (sparkObject == null)
            setToNull();
        else {
            dataValue = (byte[]) sparkObject; // Autobox, must be something better.
            setIsNull(false);
        }
    }

    @Override
    public Object getSparkObject() throws StandardException {
        if (isNull() || dataValue == null)
            return null;
        return dataValue;
    }

    /**
     * This method implements the locate function for binary string.
     * @param searchFrom    - The binary string to search from
     * @param start         - The position to search from in the binary string searchFrom
     * @param result        - The object to return
     *
     *
     * @return  The position in searchFrom the fist occurrence of this.value.
     *              0 is returned if searchFrom does not contain this.value.
     * @exception StandardException     Thrown on error
     */
    public NumberDataValue locate(  ConcatableDataValue searchFrom,
                                    NumberDataValue start,
                                    NumberDataValue result)
            throws StandardException
    {
        int startVal;

        if( result == null )
        {
            result = new SQLInteger();
        }

        if( start.isNull() )
        {
            startVal = 1;
        }
        else
        {
            startVal = start.getInt();
        }

        if( searchFrom.isNull() || this.isNull() )
        {
            result.setToNull();
            return result;
        }

        if( startVal < 1 )
        {
            throw StandardException.newException(
                    SQLState.LANG_INVALID_PARAMETER_FOR_SEARCH_POSITION,
                    this, searchFrom,
                    startVal);
        }

        byte[] mySearchFrom = searchFrom.getBytes();
        byte[] mySearchFor = this.getBytes();

        if(mySearchFor == null || mySearchFor.length == 0)
        {
            result.setValue(startVal);
            return result;
        }

        if (mySearchFrom == null || mySearchFrom.length == 0)
        {
            result.setValue(0);
            return result;
        }

        int index = startVal - 1;
        if (mySearchFrom.length - index < mySearchFor.length) {
            result.setValue(0);
            return result;
        }

        for (; index < mySearchFrom.length - mySearchFor.length + 1; index++) {
            int offset=0;
            for (; offset < mySearchFor.length; offset ++) {
                if (mySearchFrom[index + offset] != mySearchFor[offset])
                    break;
            }
            if (offset == mySearchFor.length) {
                result.setValue(index + 1);
                return result;
            }
        }
        //no match is found
        result.setValue(0);
        return result;
    }
}
