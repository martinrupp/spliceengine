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

import com.google.protobuf.ExtensionRegistry;
import com.splicemachine.db.catalog.types.TypeMessage;
import com.splicemachine.db.iapi.db.DatabaseContext;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.cache.ClassSize;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.i18n.LocaleFinder;
import com.splicemachine.db.iapi.services.io.ArrayInputStream;
import com.splicemachine.db.iapi.services.io.ArrayUtil;
import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.compile.CompilerContext;
import com.splicemachine.db.iapi.types.DataValueFactoryImpl.Format;
import com.splicemachine.db.iapi.util.ReuseFactory;
import com.splicemachine.db.iapi.util.StringUtil;
import com.splicemachine.db.shared.common.sql.Utils;
import org.apache.datasketches.theta.UpdateSketch;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.joda.time.DateTime;
import org.joda.time.Days;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.GregorianCalendar;


/**
 * This contains an instance of a SQL Timestamp object.
 * <p>
 * SQLTimestamp is stored in 3 ints - an encoded date, an encoded time and
 *  nanoseconds
 * encodedDate = 0 indicates a null WSCTimestamp
 *
 * SQLTimestamp is similar to SQLTimestamp, but it does conserves space by not keeping a GregorianCalendar object
 *
 * PERFORMANCE OPTIMIZATION:
 * We only instantiate the value field when required due to the overhead of the
 * Date methods.
 * Thus, use isNull() instead of "value == null" and
 * getTimestamp() instead of using value directly.
 */

public final class SQLTimestamp extends DataType implements DateTimeDataValue {

    public static final int MAX_FRACTION_DIGITS = 6; // Only microsecond resolution on conversion to/from strings
    static final int FRACTION_TO_NANO = 1000; // 10**(9 - MAX_FRACTION_DIGITS)

    static final int ONE_BILLION = 1000000000;

    private static final int MICROS_TO_SECOND = 1000000;
    // edges for our internal Timestamp in microseconds
    // from ~21 Sep 1677 00:12:44 GMT to ~11 Apr 2262 23:47:16 GMT
    // This is only applicable to version 2.0 tables, that use the
    // TimestampV2DescriptorSerializer.  Version 3.0 and above supports
    // the full range of timestamps, year 0001 - 9999.
    public static final long MAX_V2_TIMESTAMP = Long.MAX_VALUE / MICROS_TO_SECOND - 1;
    public static final long MIN_V2_TIMESTAMP = Long.MIN_VALUE / MICROS_TO_SECOND + 1;

    public static final String defaultTimestampFormatString = "yyyy-MM-dd HH:mm:ss";

    private static boolean skipDBContext = false;
    private DateTimeFormatter formatter = null;

    public static void setSkipDBContext(boolean value) { skipDBContext = value; }

    private int encodedDate;
    private int encodedTime;
    private int nanos;

    private int stringFormat = -1;

    private String timestampFormat;
    /*
    ** DataValueDescriptor interface
    ** (mostly implemented in DataType)
    */

    private static final int BASE_MEMORY_USAGE = ClassSize.estimateBaseFromCatalog( SQLTimestamp.class);

    private final Calendar calendar = null;

    @Override
    public void setStringFormat(int format) {
        stringFormat = format;
    }

    public void setTimestampFormat(String format) {
        timestampFormat = format;
        formatter = null; // recreate on demand
    }

    // Check for a version 2.0 timestamp out of bounds.
    public static long checkV2Bounds(Timestamp timestamp) throws StandardException{
        long millis = timestamp.getTime();
        if (millis > MAX_V2_TIMESTAMP || millis < MIN_V2_TIMESTAMP) {
            throw StandardException.newException(SQLState.LANG_DATE_TIME_ARITHMETIC_OVERFLOW, timestamp.toString());
        }

        return millis;
    }

    /**
     * Convert an out-of-bounds timestamp to the minimum or maximum timestamp.
     * <p>
     * @return If timestamp is less than the minimum timestamp, the minimum timestamp is returned.
     *         If timestamp is greater than the maximum timestamp, the maximum timestamp is returned.
     *         If timestamp is in bounds, it is returned unaltered.
     * @notes  The current minimum timestamp is appr. 1677-09-21-00.12.44.000000.
     *         The current maximum timestamp is appr. 2262-04-11-23.47.16.999999.
     *         Due to time zone settings or other influences, the actual maximum
     *         or minimum timestamp may differ slightly.
     */
    public static Timestamp convertTimeStamp(Timestamp timestamp) throws StandardException {
        if (timestamp == null)
            return null;

        long millis = timestamp.getTime();
        if (millis > MAX_V2_TIMESTAMP)
            timestamp = new Timestamp(MAX_V2_TIMESTAMP);

        if (millis < MIN_V2_TIMESTAMP)
            timestamp = new Timestamp(MIN_V2_TIMESTAMP);

        return timestamp;
    }

    public int estimateMemoryUsage()
    {
        return BASE_MEMORY_USAGE;
    } // end of estimateMemoryUsage

    public static int getFormatLength(String format) throws IllegalArgumentException {
        return Utils.getTimestampFormatLength(format);
    }

    /**
     * see also https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html
     */
    private String format(Timestamp timestamp, String timeStampFormat) {
        if(timeStampFormat == null) timeStampFormat = CompilerContext.DEFAULT_TIMESTAMP_FORMAT;
        if(formatter == null ) {
            formatter = DateTimeFormatter.ofPattern(timeStampFormat).withZone(ZoneId.systemDefault());
        }
        return formatter.format(timestamp.toLocalDateTime());
    }


    public String getString() throws StandardException {
        if(stringFormat >= 0) { // similar to DB2, we don't support custom formats for Timestamp (DB-10461)
            throw StandardException.newException(SQLState.LANG_FORMAT_EXCEPTION, "timestamp");
        }
        if (!isNull()) {
            Timestamp timestamp = getTimestamp(calendar);
            assert timestamp != null;
            return format(timestamp, timestampFormat);
        } else {
            return null;
        }
    }

    /**
        getDate returns the date portion of the timestamp
        Time is set to 00:00:00.0
        Since Date is a JDBC object we use the JDBC definition
        for the time portion.  See JDBC API Tutorial, 47.3.12.

        @exception StandardException thrown on failure
     */
    public Date getDate( Calendar cal) throws StandardException
    {
        if (isNull())
            return null;

        if( cal == null)
            cal = SQLDate.GREGORIAN_CALENDAR.get();
        
        // Will clear all the other fields to zero
        // that we require at zero, specifically
        // HOUR_OF_DAY, MINUTE, SECOND, MILLISECOND
        cal.clear();
        
        SQLDate.setDateInCalendar(cal, encodedDate);

        return new Date(cal.getTimeInMillis());
    }

    /**
        getTime returns the time portion of the timestamp
        Date is set to 1970-01-01
        Since Time is a JDBC object we use the JDBC definition
        for the date portion.  See JDBC API Tutorial, 47.3.12.
        @exception StandardException thrown on failure
     */
    public Time getTime( Calendar cal) throws StandardException
    {
        if (isNull())
            return null;
        
        // Derby's SQL TIMESTAMP type supports resolution
        // to nano-seconds so ensure the Time object
        // maintains that since it has milli-second
        // resolutiuon.
        return SQLTime.getTime(cal, encodedTime, nanos);
	}

	public Object getObject()
	{
		return getTimestamp((Calendar) null);
	}
		
	/* get storage length */
	public int getLength()
	{
		return 12;
	}

	/* this is for DataType's error generator */
	public String getTypeName()
	{
		return "TIMESTAMP";
	}

	/*
	 * Storable interface, implies Externalizable, TypedFormat
	 */

	/**
		Return my format identifier.

		@see com.splicemachine.db.iapi.services.io.TypedFormat#getTypeFormatId
	*/
	public int getTypeFormatId() {
		return StoredFormatIds.SQL_TIMESTAMP_ID;
	}

	@Override
	protected void writeExternalOld(ObjectOutput out) throws IOException {

		out.writeBoolean(isNull);
		/*
		 ** Timestamp is written out 3 ints, encoded date, encoded time, and
		 ** nanoseconds
		 */
		out.writeInt(encodedDate);
		out.writeInt(encodedTime);
		out.writeInt(nanos);
	}

	@Override
	public TypeMessage.DataValueDescriptor toProtobuf() throws IOException {
		TypeMessage.SQLTimestamp.Builder builder = TypeMessage.SQLTimestamp.newBuilder();
		builder.setIsNull(isNull);
		if (!isNull) {
			builder.setEncodedDate(encodedDate);
			builder.setEncodedTime(encodedTime);
			builder.setNanos(nanos);
		}
		TypeMessage.DataValueDescriptor dvd =
				TypeMessage.DataValueDescriptor.newBuilder()
						.setType(TypeMessage.DataValueDescriptor.Type.SQLTimestamp)
						.setExtension(TypeMessage.SQLTimestamp.sqlTimestamp, builder.build())
						.build();
		return dvd;
	}

	@Override
	protected void readExternalNew(ObjectInput in) throws IOException {
		byte[] bs = ArrayUtil.readByteArray(in);
		ExtensionRegistry extensionRegistry = ProtobufUtils.getExtensionRegistry();
		TypeMessage.DataValueDescriptor dvd = TypeMessage.DataValueDescriptor.parseFrom(bs, extensionRegistry);
		TypeMessage.SQLTimestamp timestamp = dvd.getExtension(TypeMessage.SQLTimestamp.sqlTimestamp);
		init(timestamp);
	}

	private void init(TypeMessage.SQLTimestamp timestamp) {
		isNull = timestamp.getIsNull();
		if (!isNull) {
			int date = timestamp.getEncodedDate();
			int time = timestamp.getEncodedTime();
			int nanos = timestamp.getNanos();
			setValue(date, time, nanos);
		}
	}

	@Override
	protected void readExternalOld(ObjectInput in) throws IOException
	{
		int date;
		int time;
		int nanos;
		isNull = in.readBoolean();
		date = in.readInt();
		time = in.readInt();
		nanos = in.readInt();
		setValue(date, time, nanos);
	}

	public void readExternalFromArray(ArrayInputStream in) throws IOException
	{
		int date;
		int time;
		int nanos;
		isNull = in.readBoolean();
		date = in.readInt();
		time = in.readInt();
		nanos = in.readInt();
		setValue(date, time,nanos);
	}

	/*
	 * DataValueDescriptor interface
	 */

	/** @see DataValueDescriptor#cloneValue */
	public DataValueDescriptor cloneValue(boolean forceMaterialization)
	{
		// Call constructor with all of our info
		if (isNull)
			return new SQLTimestamp();
		return new SQLTimestamp(encodedDate, encodedTime, nanos);
	}

	/**
	 * @see DataValueDescriptor#getNewNull
	 */
	public DataValueDescriptor getNewNull()
	{
		return new SQLTimestamp();
	}
	/**
	 * @see com.splicemachine.db.iapi.services.io.Storable#restoreToNull
	 *
	 */
	public void restoreToNull()
	{
		// clear numeric representation
		encodedDate = 0;
		encodedTime = 0;
		nanos = 0;
		isNull = true;

	}

	/*
	 * DataValueDescriptor interface
	 */

	/** 
	 * @see DataValueDescriptor#setValueFromResultSet 
	 *
	 * @exception SQLException		Thrown on error
	 */
	public void setValueFromResultSet(ResultSet resultSet, int colNumber,
									  boolean isNullable)
		throws SQLException, StandardException
	{
			setValue(resultSet.getTimestamp(colNumber), (Calendar) null);
	}

	@SuppressFBWarnings(value="RV_NEGATING_RESULT_OF_COMPARETO", justification="intended")
	public int compare(DataValueDescriptor other)
		throws StandardException
	{
		/* Use compare method from dominant type, negating result
		 * to reflect flipping of sides.
		 */
		if (typePrecedence() < other.typePrecedence())
		{
			return - (other.compare(this));
		}

		boolean thisNull, otherNull;

		thisNull = this.isNull();
		otherNull = other.isNull();

		/*
		 * thisNull otherNull	return
		 *	T		T		 	0	(this == other)
		 *	F		T		 	-1 	(this < other)
		 *	T		F		 	1	(this > other)
		 */
		if (thisNull || otherNull)
		{
			if (!thisNull)		// otherNull must be true
				return -1;
			if (!otherNull)		// thisNull must be true
				return 1;
			return 0;
		}

		/*
			Neither are null compare them 
		 */

		int comparison;
		/* get the comparison date values */
		int otherEncodedDate = 0;
		int otherEncodedTime = 0;
		int otherNanos = 0;

		/* if the argument is another SQLTimestamp, look up the value
		 */
		if (other instanceof SQLTimestamp)
		{
			SQLTimestamp st = (SQLTimestamp)other;
			otherEncodedDate= st.encodedDate;
			otherEncodedTime= st.encodedTime;
			otherNanos= st.nanos;
		}
		else 
		{
			/* O.K. have to do it the hard way and calculate the numeric value
			 * from the value
			 */
			Calendar cal = SQLDate.GREGORIAN_CALENDAR.get();
			Timestamp otherts = other.getTimestamp(cal);
			int[] otherDateTime = SQLTimestamp.computeEncodedDateTime(otherts,cal);
			otherEncodedDate = otherDateTime[0];
			otherEncodedTime = otherDateTime[1];
			otherNanos = otherts.getNanos();
		}
		if (encodedDate < otherEncodedDate)
			comparison = -1;
		else if (encodedDate > otherEncodedDate)
			comparison = 1;
		else if (encodedTime < otherEncodedTime)
			comparison = -1;
		else if (encodedTime > otherEncodedTime)
			comparison = 1;
		else if (nanos < otherNanos)
			comparison = -1;
		else if (nanos > otherNanos)
			comparison = 1;
		else
			comparison = 0;

		return comparison;
	}

	/**
		@exception StandardException thrown on error
	 */
	public boolean compare(int op,
						   DataValueDescriptor other,
						   boolean orderedNulls,
						   boolean unknownRV)
		throws StandardException
	{
		if (!orderedNulls)		// nulls are unordered
		{
			if (this.isNull() || ((DataValueDescriptor)other).isNull())
				return unknownRV;
		}

		/* Do the comparison */
		return super.compare(op, other, orderedNulls, unknownRV);
	}

	/*
	** Class interface
	*/

	/*
	** Constructors
	*/

	/** no-arg constructor required by Formattable */
	public SQLTimestamp() { }


	public SQLTimestamp(Timestamp value) throws StandardException
	{
		setValue(value, (Calendar) null);
	}

    public SQLTimestamp(org.joda.time.DateTime value) throws StandardException
    {
        setValue(value);
    }

    SQLTimestamp(int encodedDate, int encodedTime, int nanos) {

        setValue(encodedDate, encodedTime, nanos);
    }

    public SQLTimestamp( DataValueDescriptor date, DataValueDescriptor time) throws StandardException
    {
        Calendar cal = null;
        int encodedDateLocal;
        int encodedTimeLocal;
        if( date == null || date.isNull()
            || time == null || time.isNull())
            return;
        if( date instanceof SQLDate)
        {
            SQLDate sqlDate = (SQLDate) date;
            encodedDateLocal = sqlDate.getEncodedDate();
        }
        else
        {
            cal = SQLDate.GREGORIAN_CALENDAR.get();
            encodedDateLocal = computeEncodedDate( date.getDate( cal), cal);
        }
        if( time instanceof SQLTime)
        {
            SQLTime sqlTime = (SQLTime) time;
            encodedTimeLocal = sqlTime.getEncodedTime();
        }
        else
        {
            if( cal == null)
                cal = SQLDate.GREGORIAN_CALENDAR.get();
            encodedTimeLocal = computeEncodedTime( time.getTime( cal), cal);
        }
        setValue(encodedDateLocal, encodedTimeLocal);
    }

    public SQLTimestamp(TypeMessage.SQLTimestamp sqlTimestamp) {
		init(sqlTimestamp);
	}
    /**
     * Construct a timestamp from a string. The allowed formats are:
     *<ol>
     *<li>JDBC escape: yyyy-mm-dd hh:mm:ss[.fffff]
     *<li>IBM: yyyy-mm-dd-hh.mm.ss[.nnnnnn]
     *</ol>
     * The format is specified by a parameter to the constructor. Leading zeroes may be omitted from the month, day,
     * and hour part of the timestamp. The microsecond part may be omitted or truncated.
     */
    public SQLTimestamp( String timestampStr, boolean isJDBCEscape, LocaleFinder localeFinder)
        throws StandardException
    {
        parseTimestamp( timestampStr, isJDBCEscape,localeFinder, calendar);
    }

    /**
     * Construct a timestamp from a string. The allowed formats are:
     *<ol>
     *<li>JDBC escape: yyyy-mm-dd hh:mm:ss[.fffff]
     *<li>IBM: yyyy-mm-dd-hh.mm.ss[.nnnnnn]
     *</ol>
     * The format is specified by a parameter to the constructor. Leading zeroes may be omitted from the month, day,
     * and hour part of the timestamp. The microsecond part may be omitted or truncated.
     */
    public SQLTimestamp( String timestampStr, boolean isJDBCEscape, LocaleFinder localeFinder, Calendar cal)
        throws StandardException
    {
        parseTimestamp( timestampStr, isJDBCEscape, localeFinder, cal);
    }

    static final char DATE_SEPARATOR = '-';
    private static final char[] DATE_SEPARATORS = { DATE_SEPARATOR};
    private static final char IBM_DATE_TIME_SEPARATOR = '-';
    private static final char ODBC_DATE_TIME_SEPARATOR = ' ';
    private static final char ISO_DATE_TIME_SEPARATOR = 'T'; // ISO separator
    private static final char ISO_TIME_TERMINATOR = 'Z'; // ISO terminator (Zulu)
    private static final char[] DATE_TIME_SEPARATORS = {IBM_DATE_TIME_SEPARATOR, ODBC_DATE_TIME_SEPARATOR, ISO_DATE_TIME_SEPARATOR};
    private static final char[] DATE_TIME_SEPARATORS_OR_END
    = {IBM_DATE_TIME_SEPARATOR, ODBC_DATE_TIME_SEPARATOR, ISO_DATE_TIME_SEPARATOR, ISO_TIME_TERMINATOR, (char) 0};
    private static final char IBM_TIME_SEPARATOR = '.';
    private static final char ODBC_TIME_SEPARATOR = ':';
    private static final char[] TIME_SEPARATORS = {IBM_TIME_SEPARATOR, ODBC_TIME_SEPARATOR};
    private static final char[] TIME_SEPARATORS_OR_END = {IBM_TIME_SEPARATOR, ODBC_TIME_SEPARATOR, (char) 0};
    private static final char[] END_OF_STRING = {ISO_TIME_TERMINATOR, (char) 0};

    private void parseTimestamp( String timestampStr, boolean isJDBCEscape, LocaleFinder localeFinder, Calendar cal)
        throws StandardException
    {
        StandardException thrownSE = null;
        DateTimeParser parser = new DateTimeParser( timestampStr);
        try
        {
            int[] dateTimeNano = parseDateOrTimestamp( parser, false);
            setValue(dateTimeNano[0], dateTimeNano[1], dateTimeNano[2]);
            return;
        }
        catch( StandardException se)
        {
            thrownSE = se;
        }
        // see if it is a localized timestamp
        try
        {
            timestampStr = StringUtil.trimTrailing( timestampStr);
            int[] dateAndTime = parseLocalTimestamp( timestampStr, localeFinder, cal);
            setValue(dateAndTime[0], dateAndTime[1]);
            return;
        }
        catch( ParseException | StandardException ignored){}
        if( thrownSE != null)
            throw thrownSE;
        throw StandardException.newException( SQLState.LANG_DATE_SYNTAX_EXCEPTION);
    } // end of parseTimestamp

    /**
     * Parse a localized timestamp.
     *
     * @param str the timestamp string, with trailing blanks removed.
     * @param localeFinder
     *
     * @return a {encodedDate, encodedTime} array.
     *
     * @exception ParseException If the string is not a valid timestamp.
     */
    static int[] parseLocalTimestamp( String str, LocaleFinder localeFinder, Calendar cal)
        throws StandardException, ParseException
    {
        DateFormat timestampFormat = null;
        if(localeFinder == null)
            timestampFormat = DateFormat.getDateTimeInstance();
        else if( cal == null)
            timestampFormat = localeFinder.getTimestampFormat();
        else
            timestampFormat = (DateFormat) localeFinder.getTimestampFormat().clone();
        if( cal == null)
            cal = SQLDate.GREGORIAN_CALENDAR.get();
        else
            timestampFormat.setCalendar( cal);
        java.util.Date date = timestampFormat.parse( str);

        return computeEncodedDateTime(date, cal);
    } // end of parseLocalTimestamp

    /**
     * Parse a timestamp or a date. DB2 allows timestamps to be used as dates or times. So
     * date('2004-04-15-16.15.32') is valid, as is date('2004-04-15').
     *
     * This method does not handle localized timestamps.
     *
     * @param parser a DateTimeParser initialized with a string.
     * @param timeRequired If true then an error will be thrown if the time is missing. If false then the time may
     *                     be omitted.
     *
     * @return {encodedDate, encodedTime, nanosecond} array.
     *
     * @exception StandardException if the syntax is incorrect for an IBM standard timestamp.
     */
    static int[] parseDateOrTimestamp( DateTimeParser parser, boolean timeRequired)
        throws StandardException
    {
        int year = parser.parseInt( 4, false, DATE_SEPARATORS, false);
        int month = parser.parseInt( 2, true, DATE_SEPARATORS, false);
        int day = parser.parseInt( 2, true, timeRequired ? DATE_TIME_SEPARATORS : DATE_TIME_SEPARATORS_OR_END, false);
        int hour = 0;
        int minute = 0;
        int second = 0;
        int nano = 0;
        if( parser.getCurrentSeparator() != 0)
        {
            char timeSeparator = ((parser.getCurrentSeparator() == ODBC_DATE_TIME_SEPARATOR) || (parser.getCurrentSeparator() == ISO_DATE_TIME_SEPARATOR))
                    ? ODBC_TIME_SEPARATOR : IBM_TIME_SEPARATOR;
            hour = parser.parseInt( 2, true, TIME_SEPARATORS, false);
            if( timeSeparator == parser.getCurrentSeparator())
            {
                minute = parser.parseInt( 2, false, TIME_SEPARATORS, false);
                if( timeSeparator == parser.getCurrentSeparator())
                {
                    second = parser.parseInt( 2, false, TIME_SEPARATORS_OR_END, false);
                    if( parser.getCurrentSeparator() == '.')
                        nano = parser.parseInt( MAX_FRACTION_DIGITS, true, END_OF_STRING, true)*FRACTION_TO_NANO;
                }
            }
        }
        parser.checkEnd();
        return new int[] { SQLDate.computeEncodedDate( year, month, day),
                           SQLTime.computeEncodedTime( hour,minute,second),
                           nano};
    } // end of parseDateOrTimestamp

    /**
     * Set the value from a correctly typed Timestamp object.
     * @throws StandardException
     */
    void setObject(Object theValue) throws StandardException
    {
        if (theValue instanceof DateTime)
            setValue((DateTime) theValue);
        else
            setValue((Timestamp) theValue);
    }

    protected void setFrom(DataValueDescriptor theValue) throws StandardException {

        if (theValue instanceof SQLTimestamp) {
            restoreToNull();
            SQLTimestamp tvst = (SQLTimestamp) theValue;
            setValue(tvst.encodedDate, tvst.encodedTime, tvst.nanos);
        }
        else
        {
            Calendar cal = SQLDate.GREGORIAN_CALENDAR.get();
            setValue(theValue.getTimestamp( cal), cal);
        }
    }

    /**
        @see DateTimeDataValue#setValue
        When converting from a date to a timestamp, time is set to 00:00:00.0

     */
    public void setValue(Date value, Calendar cal) throws StandardException
    {
        restoreToNull();
        if( value != null)
        {
            if( cal == null)
                cal = SQLDate.GREGORIAN_CALENDAR.get();
            setValue(computeEncodedDate(value, cal));
        }
        /* encodedTime and nanos are already set to zero by restoreToNull() */
    }

    /**
        @see DateTimeDataValue#setValue

     */
    public void setValue(Timestamp value, Calendar cal)
        throws StandardException
    {
        restoreToNull();
        setNumericTimestamp(value, cal);
    }

    public void setValue(DateTime value)
            throws StandardException
    {
        restoreToNull();
        setNumericTimestamp(value);
    }

    public void setValue(String theValue) throws StandardException {
        setValue(theValue,null);
    }

    public void setValue(int encodedDateArg)
    {
        restoreToNull();
        encodedDate = encodedDateArg;
        encodedTime = 0;
        nanos = 0;
        isNull = evaluateNull();
    }

    public void setValue(int encodedDateArg, int encodedTimeArg)
    {
        restoreToNull();
        encodedDate = encodedDateArg;
        encodedTime = encodedTimeArg;
        nanos = 0;
        isNull = evaluateNull();
    }

    public void setValue(int encodedDateArg, int encodedTimeArg, int nanosArg)
    {
        restoreToNull();
        encodedDate = encodedDateArg;
        encodedTime = encodedTimeArg;
        nanos = nanosArg;
        isNull = evaluateNull();
    }

    /*
    ** SQL Operators
    */

    NumberDataValue nullValueInt() {
        return new SQLInteger();
    }

    NumberDataValue nullValueDouble() {
        return new SQLDouble();
    }

    SQLVarchar nullValueVarchar() {
        return new SQLVarchar();
    }

    /**
     * @see DateTimeDataValue#getYear
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getYear(NumberDataValue result)
                            throws StandardException
    {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getYear(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getQuarter
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getQuarter(NumberDataValue result)
        throws StandardException
    {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getQuarter(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getMonth
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getMonth(NumberDataValue result)
                            throws StandardException
    {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getMonth(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getMonthName
     *
     * @exception StandardException  Thrown on error
     */
    public StringDataValue getMonthName(StringDataValue result)
        throws StandardException {
        if (isNull()) {
            return new SQLVarchar();
        } else {
            return SQLDate.setSource(SQLDate.getMonthName(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getWeek
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getWeek(NumberDataValue result)
        throws StandardException {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getWeek(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getWeekDay
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getWeekDay(NumberDataValue result)
        throws StandardException {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getWeekDay(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getUSWeekDay
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getUSWeekDay(NumberDataValue result)
            throws StandardException {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getUSWeekDay(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getWeekDayName
     *
     * @exception StandardException  Thrown on error
     */
    public StringDataValue getWeekDayName(StringDataValue result)
        throws StandardException {
        if (isNull()) {
            return new SQLVarchar();
        } else {
            return SQLDate.setSource(SQLDate.getWeekDayName(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getDayOfYear
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getDayOfYear(NumberDataValue result)
        throws StandardException {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getDayOfYear(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getDate
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getDate(NumberDataValue result)
                            throws StandardException
    {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getDay(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getDays
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getDays(NumberDataValue result)
            throws StandardException
    {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLDate.getDays(encodedDate), result);
        }
    }

    /**
     * @see DateTimeDataValue#getHours
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getHours(NumberDataValue result)
                            throws StandardException
    {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLTime.getHour(encodedTime), result);
        }
    }

    /**
     * @see DateTimeDataValue#getMinutes
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getMinutes(NumberDataValue result)
                            throws StandardException
    {
        if (isNull()) {
            return nullValueInt();
        } else {
            return SQLDate.setSource(SQLTime.getMinute(encodedTime), result);
        }
    }

    /**
     * @see DateTimeDataValue#getSecondsAndFractionOfSecondAsDouble
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getSecondsAndFractionOfSecondAsDouble(NumberDataValue source)
                            throws StandardException
    {
        if (SanityManager.DEBUG)
        {
            SanityManager.ASSERT(source == null || source.isDoubleType(),
        "getSecondsAndFractionOfSecondAsDouble for a timestamp was given a source other than a SQLDouble");
        }
        NumberDataValue result;

        if (isNull()) {
            return nullValueDouble();
        }

        if (source != null)
            result = source;
        else
            result = new SQLDouble();

        result.setValue((double)(SQLTime.getSecond(encodedTime))
                + ((double)nanos)/1.0e9);

        return result;
    }

    /**
     * @see DateTimeDataValue#getSecondsAsInt
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getSecondsAsInt(NumberDataValue source)
            throws StandardException
    {
        if (SanityManager.DEBUG)
        {
            SanityManager.ASSERT(source == null || source.getTypeName().equals(TypeId.INTEGER_NAME),
                    "getSecondsAsInt for a timestamp was given a source other than a SQLInt");
        }
        NumberDataValue result;

        if (isNull()) {
            return nullValueInt();
        }

        if (source != null)
            result = source;
        else
            result = new SQLInteger();

        result.setValue(SQLTime.getSecond(encodedTime));

        return result;
    }

    /**
     * @see DateTimeDataValue#getSecondsAndFractionOfSecondAsDecimal
     *
     * @exception StandardException  Thrown on error
     */
    public NumberDataValue getSecondsAndFractionOfSecondAsDecimal(NumberDataValue source)
            throws StandardException
    {
        if (SanityManager.DEBUG)
        {
            SanityManager.ASSERT(source == null || source.getTypeName().equals(TypeId.DECIMAL_NAME),
                    "getSecondsAndFractionOfSecondAsDecimal for a timestamp was given a source other than a SQLDecimal");
        }
        NumberDataValue result;

        if (isNull()) {
            return new SQLDecimal(null, 11, 9);
        }

        if (source != null)
            result = source;
        else
            result = new SQLDecimal(null, 11, 9);

        result.setValue(SQLTime.getSecond(encodedTime) + ((double)nanos)/1.0e9);

        return result;
    }

    /*
    ** String display of value
    */

    public String toString()
    {
        if (isNull())
        {
            return "NULL";
        }
        else
        {
            try {
                return getString();
            } catch (StandardException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /*
     * Hash code
     */
    public int hashCode()
    {
        if (isNull())
        {
            return 0;
        }

        return  encodedDate + encodedTime + nanos; //since 0 is null

    }

    /** @see DataValueDescriptor#typePrecedence */
    public int typePrecedence()
    {
        return TypeId.TIMESTAMP_PRECEDENCE;
    }

    /**
     * Check if the value is null.  encodedDate value of 0 is null
     *
     * @return Whether or not value is logically null.
     */
    private boolean evaluateNull()
    {
        return (encodedDate == 0);
    }

    /**
     * Get the value field.  We instantiate the field
     * on demand.
     *
     * @return The value field.
     */
    public Timestamp getTimestamp(java.util.Calendar cal)
    {
        if (isNull())
            return null;

        Timestamp t = null;

        if (cal == null){
            int year = SQLDate.getYear(encodedDate);
            if (year < SQLDate.JODA_CRUSH_YEAR) {
                t = computeGregorianCalendarTimestamp(year);
            } else {
                try {
                    t = new Timestamp(SQLDate.getYear(encodedDate) - 1900,
                                      SQLDate.getMonth(encodedDate) - 1,
                                      SQLDate.getDay(encodedDate),
                                      SQLTime.getHour(encodedTime),
                                      SQLTime.getMinute(encodedTime),
                                      SQLTime.getSecond(encodedTime),
                                      nanos);
                } catch (Exception e) {
                    t = computeGregorianCalendarTimestamp(year);
                }
            }
        }else{
            setCalendar(cal);
            t = new Timestamp(cal.getTimeInMillis());
        }

        t.setNanos(nanos);
        return t;
    }

    private Timestamp computeGregorianCalendarTimestamp(int year) {
        GregorianCalendar c = SQLDate.GREGORIAN_CALENDAR.get();
        c.clear();
        c.set(year, SQLDate.getMonth(encodedDate) - 1, SQLDate.getDay(encodedDate), SQLTime.getHour(encodedTime), SQLTime.getMinute(encodedTime), SQLTime.getSecond(encodedTime));
        // c.setTimeZone(...); if necessary
        return new Timestamp(c.getTimeInMillis());
    }

    public DateTime getDateTime() {
        return createDateTime();
    }

    /**
     * year - the year, from 1 to 9999 (a Derby restriction)
     * monthOfYear - the month of the year, from 1 to 12
     * dayOfMonth - the day of the month, from 1 to 31
     * hourOfDay - the hour of the day, from 0 to 23
     * minuteOfHour - the minute of the hour, from 0 to 59
     * secondOfMinute - the second of the minute, from 0 to 59
     * millisOfSecond - the millisecond of the second, from 0 to 999
     *
     * @return joda DateTime representation
     */
    private DateTime createDateTime(){
        return new DateTime(SQLDate.getYear(encodedDate),
                            SQLDate.getMonth(encodedDate),
                            SQLDate.getDay(encodedDate),
                            SQLTime.getHour(encodedTime),
                            SQLTime.getMinute(encodedTime),
                            SQLTime.getSecond(encodedTime),
                            nanos/1000000);
    }

    private void setCalendar(Calendar cal)
    {
        cal.clear();

        SQLDate.setDateInCalendar(cal, encodedDate);

        SQLTime.setTimeInCalendar(cal, encodedTime);

        cal.set(Calendar.MILLISECOND, 0);
    } // end of setCalendar

    /**
     * Set the encoded values for the timestamp
     *
     */
    private void setNumericTimestamp(Timestamp value, Calendar cal) throws StandardException
    {
        if (SanityManager.DEBUG)
        {
            SanityManager.ASSERT(isNull(), "setNumericTimestamp called when already set");
        }
        if (value != null)
        {
            if( cal == null) {
                cal = SQLDate.GREGORIAN_CALENDAR.get();
            }
            int[] encodedDateTime = computeEncodedDateTime(value, cal);
            setValue(encodedDateTime[0], encodedDateTime[1], value.getNanos());

        }
        /* encoded date should already be 0 for null */
    }


    private void setNumericTimestamp(DateTime dt) throws StandardException
    {
        if (SanityManager.DEBUG)
        {
            SanityManager.ASSERT(isNull(), "setNumericTimestamp called when already set");
        }
        if (dt != null)
        {
            int nanosLocal;
            nanosLocal = (int)((dt.getMillis()%1000) * 1000000);
            if (nanosLocal < 0) {
                nanosLocal = 1000000000 + nanosLocal;
            }
            setValue(computeEncodedDate(dt), computeEncodedTime(dt), nanosLocal);
        }
        /* encoded date should already be 0 for null */
    }
    /**
        computeEncodedDate sets the date in a Calendar object
        and then uses the SQLDate function to compute an encoded date
        The encoded date is
            year << 16 + month << 8 + date
        @param value the value to convert
        @return   the encodedDate

     */
    private static int computeEncodedDate(java.util.Date value, Calendar currentCal) throws StandardException
    {
        if (value == null)
            return 0;

        currentCal.setTime(value);
        return SQLDate.computeEncodedDate(currentCal);
    }

    private static int computeEncodedDate(DateTime value) throws StandardException
    {
        if (value == null)
            return 0;

        return SQLDate.computeEncodedDate(value.getYear(), value.getMonthOfYear(), value.getDayOfMonth());
    }
    /**
        computeEncodedTime extracts the hour, minute and seconds from
        a java.util.Date value and encodes them as
            hour << 16 + minute << 8 + second
        using the SQLTime function for encoding the data
        @param value the value to convert
        @return   the encodedTime

     */
    private static int computeEncodedTime(java.util.Date value, Calendar currentCal) throws StandardException
    {
        currentCal.setTime(value);
        return SQLTime.computeEncodedTime(currentCal);
    }

    private static int computeEncodedTime(DateTime value) throws StandardException
    {
        return SQLTime.computeEncodedTime(value.getHourOfDay(), value.getMinuteOfHour(), value.getSecondOfMinute());
    }

    /**
     computeEncodedDateTime combines the computation of computeEncodedDate and computeEncodedTime
     which returns the results of the two function as a 2-element array
     @param value the value to convert
     @return   the int array of encodedDate and encodedTime
     */
    private static int[] computeEncodedDateTime(java.util.Date value, Calendar currentCal) throws StandardException
    {
        if (value == null)
            return new int[]{0,0};

        currentCal.setTime(value);
        return new int[]{SQLDate.computeEncodedDate(currentCal),SQLTime.computeEncodedTime(currentCal)};
    }

    public void setInto(PreparedStatement ps, int position) throws SQLException, StandardException {

                  ps.setTimestamp(position, getTimestamp(calendar));
     }

    /**
     * Compute the SQL timestamp function.
     *
     * @exception StandardException
     */
    public static DateTimeDataValue computeTimestampFunction( DataValueDescriptor operand,
                                                              DataValueFactory dvf) throws StandardException
    {
        try
        {
            if( operand.isNull())
                return new SQLTimestamp();
            if( operand instanceof SQLTimestamp)
                return (SQLTimestamp) operand.cloneValue(false);

            String str = operand.getString();
            if( str.length() == 14)
            {
                int year = parseDateTimeInteger( str, 0, 4);
                int month = parseDateTimeInteger( str, 4, 2);
                int day = parseDateTimeInteger( str, 6, 2);
                int hour = parseDateTimeInteger( str, 8, 2);
                int minute = parseDateTimeInteger( str, 10, 2);
                int second = parseDateTimeInteger( str, 12, 2);
                return new SQLTimestamp( SQLDate.computeEncodedDate( year, month, day),
                                         SQLTime.computeEncodedTime( hour,minute,second),
                                         0);
            }
            // else use the standard cast
            return dvf.getTimestampValue( str, false);
        }
        catch( StandardException se)
        {
            if( SQLState.LANG_DATE_SYNTAX_EXCEPTION.startsWith( se.getSQLState()))
                throw StandardException.newException( SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
                                                      operand.getString(), "timestamp");
            throw se;
        }
    } // end of computeTimestampFunction

    static int parseDateTimeInteger( String str, int start, int ndigits) throws StandardException
    {
        int end = start + ndigits;
        int retVal = 0;
        for( int i = start; i < end; i++)
        {
            char c = str.charAt( i);
            if( !Character.isDigit( c))
                throw StandardException.newException( SQLState.LANG_DATE_SYNTAX_EXCEPTION);
            retVal = 10*retVal + Character.digit( c, 10);
        }
        return retVal;
    } // end of parseDateTimeInteger

    /**
     * Add a number of intervals to a datetime value. Implements the JDBC escape TIMESTAMPADD function.
     *
     * @param intervalType One of FRAC_SECOND_INTERVAL, SECOND_INTERVAL, MINUTE_INTERVAL, HOUR_INTERVAL,
     *                     DAY_INTERVAL, WEEK_INTERVAL, MONTH_INTERVAL, QUARTER_INTERVAL, or YEAR_INTERVAL
     * @param count The number of intervals to add
     * @param currentDate Used to convert time to timestamp
     * @param resultHolder If non-null a DateTimeDataValue that can be used to hold the result. If null then
     *                     generate a new holder
     *
     * @return startTime + intervalCount intervals, as a timestamp
     *
     * @exception StandardException
     */
    public DateTimeDataValue timestampAdd( int intervalType,
                                           NumberDataValue count,
                                           java.sql.Date currentDate,
                                           DateTimeDataValue resultHolder)
        throws StandardException
    {
        if( resultHolder == null)
            resultHolder = new SQLTimestamp();
        SQLTimestamp tsResult = (SQLTimestamp) resultHolder;
        if( isNull() || count.isNull())
        {
            tsResult.restoreToNull();
            return resultHolder;
        }
        tsResult.setFrom( this);
        int intervalCount = count.getInt();

        switch( intervalType)
        {
        case FRAC_SECOND_INTERVAL:
            // The interval is nanoseconds. Do the computation in long to avoid overflow.
            long nanos = this.nanos + intervalCount;
            if( nanos >= 0 && nanos < ONE_BILLION)
                tsResult.nanos = (int) nanos;
            else
            {
                int secondsInc = (int)(nanos/ONE_BILLION);
                if( nanos >= 0)
                    tsResult.nanos = (int) (nanos % ONE_BILLION);
                else
                {
                    secondsInc--;
                    nanos -= secondsInc * (long)ONE_BILLION; // 0 <= nanos < ONE_BILLION
                    tsResult.nanos = (int) nanos;
                }
                addInternal( Calendar.SECOND, secondsInc, tsResult);
            }
            break;

        case SECOND_INTERVAL:
            addInternal( Calendar.SECOND, intervalCount, tsResult);
            break;

        case MINUTE_INTERVAL:
            addInternal( Calendar.MINUTE, intervalCount, tsResult);
            break;

        case HOUR_INTERVAL:
            addInternal( Calendar.HOUR, intervalCount, tsResult);
            break;

        case DAY_INTERVAL:
            addInternal( Calendar.DATE, intervalCount, tsResult);
            break;

        case WEEK_INTERVAL:
            addInternal( Calendar.DATE, intervalCount*7, tsResult);
            break;

        case MONTH_INTERVAL:
            addInternal( Calendar.MONTH, intervalCount, tsResult);
            break;

        case QUARTER_INTERVAL:
            addInternal( Calendar.MONTH, intervalCount*3, tsResult);
            break;

        case YEAR_INTERVAL:
            addInternal( Calendar.YEAR, intervalCount, tsResult);
            break;

        default:
            throw StandardException.newException( SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
                                                  ReuseFactory.getInteger( intervalType),
                                                  "TIMESTAMPADD");
        }
        return tsResult;
    } // end of timestampAdd

    private void addInternal( int calIntervalType, int count, SQLTimestamp tsResult) throws StandardException
    {
        Calendar cal = SQLDate.GREGORIAN_CALENDAR.get();
        setCalendar( cal);
        try
        {
            cal.add( calIntervalType, count);
            tsResult.setValue(SQLDate.computeEncodedDate(cal),
                              SQLTime.computeEncodedTime(cal),
                              tsResult.getNanos());
        }
        catch( StandardException se)
        {
            String state = se.getSQLState();
            if( state != null && !state.isEmpty() && SQLState.LANG_DATE_RANGE_EXCEPTION.startsWith( state))
            {
                throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "TIMESTAMP");
            }
            throw se;
        }
    } // end of addInternal


    @Override
    public DateTimeDataValue plus(DateTimeDataValue leftOperand, NumberDataValue daysToAdd, DateTimeDataValue resultHolder) throws StandardException {
        if( resultHolder == null)
            resultHolder = new SQLTimestamp();
        if( leftOperand.isNull() || daysToAdd.isNull())
        {
            resultHolder.restoreToNull();
            return resultHolder;
        }
        DateTime dateAdd = new DateTime(leftOperand.getDateTime()).plusDays(daysToAdd.getInt());
        resultHolder.setValue(dateAdd);
        // take care of nanos (DateTime only has millis)
        if(leftOperand instanceof SQLTimestamp && resultHolder instanceof SQLTimestamp)
            ((SQLTimestamp) resultHolder).nanos = ((SQLTimestamp) leftOperand).nanos;
        return resultHolder;
    }

    @Override
    public DateTimeDataValue minus(DateTimeDataValue leftOperand, NumberDataValue daysToSubtract, DateTimeDataValue resultHolder) throws StandardException {
        if( resultHolder == null)
            resultHolder = new SQLTimestamp();
        if(leftOperand.isNull() || daysToSubtract.isNull()) {
            resultHolder.restoreToNull();
            return resultHolder;
        }
        DateTime diff = leftOperand.getDateTime().minusDays(daysToSubtract.getInt());
        resultHolder.setValue(diff);
        // take care of nanos (DateTime only has millis)
        if(leftOperand instanceof SQLTimestamp && resultHolder instanceof SQLTimestamp)
            ((SQLTimestamp) resultHolder).nanos = ((SQLTimestamp) leftOperand).nanos;
        return resultHolder;
    }

    @Override
    public NumberDataValue minus(DateTimeDataValue leftOperand, DateTimeDataValue rightOperand, NumberDataValue resultHolder) throws StandardException {
        if( resultHolder == null)
            resultHolder = new SQLInteger();
        if(leftOperand.isNull() || rightOperand.isNull()) {
            resultHolder.restoreToNull();
            return resultHolder;
        }
        DateTime thatDate = rightOperand.getDateTime();
        Days diff = Days.daysBetween(thatDate, leftOperand.getDateTime());
        resultHolder.setValue(diff.getDays());
        return resultHolder;
    }

    @Override
    public void setValue(String theValue,Calendar cal) throws StandardException{
        restoreToNull();

        if (theValue != null)
        {
            DatabaseContext databaseContext = skipDBContext ? null : (DatabaseContext) ContextService.getContext(DatabaseContext.CONTEXT_ID);
            parseTimestamp( theValue,
                    false,
                    (databaseContext == null) ? null : databaseContext.getDatabase(),
                    cal);
        }
        /* restoreToNull will have already set the encoded date to 0 (null value) */

    }

    /**
     * Finds the difference between two datetime values as a number of intervals. Implements the JDBC
     * TIMESTAMPDIFF escape function.
     *
     * @param intervalType One of FRAC_SECOND_INTERVAL, SECOND_INTERVAL, MINUTE_INTERVAL, HOUR_INTERVAL,
     *                     DAY_INTERVAL, WEEK_INTERVAL, MONTH_INTERVAL, QUARTER_INTERVAL, or YEAR_INTERVAL
     * @param time1
     * @param currentDate Used to convert time to timestamp
     * @param resultHolder If non-null a NumberDataValue that can be used to hold the result. If null then
     *                     generate a new holder
     *
     * @return the number of intervals by which this datetime is greater than time1
     *
     * @exception StandardException
     */
    public NumberDataValue timestampDiff( int intervalType,
                                          DateTimeDataValue time1,
                                          java.sql.Date currentDate,
                                          NumberDataValue resultHolder)
        throws StandardException
    {
        if( resultHolder == null)
            resultHolder = new SQLLongint();

       if( isNull() || time1.isNull())
        {
            resultHolder.setToNull();
            return resultHolder;
        }

        SQLTimestamp ts1 = promote( time1, currentDate);

        /* Years, months, and quarters are difficult because their lengths are not constant.
         * The other intervals are relatively easy (because we ignore leap seconds).
         */
        Calendar cal = SQLDate.GREGORIAN_CALENDAR.get();
        setCalendar( cal);
        long thisInSeconds = cal.getTime().getTime()/1000;
        ts1.setCalendar( cal);
        long ts1InSeconds = cal.getTime().getTime()/1000;
        long secondsDiff = thisInSeconds - ts1InSeconds;
        int nanosDiff = nanos - ts1.nanos;
        // Normalize secondsDiff and nanosDiff so that they are both <= 0 or both >= 0.
        if( nanosDiff < 0 && secondsDiff > 0)
        {
            secondsDiff--;
            nanosDiff += ONE_BILLION;
        }
        else if( nanosDiff > 0 && secondsDiff < 0)
        {
            secondsDiff++;
            nanosDiff -= ONE_BILLION;
        }
        long ldiff = 0;

        switch( intervalType)
        {
        case FRAC_SECOND_INTERVAL:
            ldiff = secondsDiff*ONE_BILLION + nanosDiff;
            break;

        case SECOND_INTERVAL:
            ldiff = secondsDiff;
            break;

        case MINUTE_INTERVAL:
            ldiff = secondsDiff/60;
            break;

        case HOUR_INTERVAL:
            ldiff = secondsDiff/(60*60);
            break;

        case DAY_INTERVAL:
            ldiff = secondsDiff/(24*60*60);
            break;

        case WEEK_INTERVAL:
            ldiff = secondsDiff/(7*24*60*60);
            break;

        case QUARTER_INTERVAL:
        case MONTH_INTERVAL:
            // Make a conservative guess and increment until we overshoot.
            if( Math.abs( secondsDiff) > 366*24*60*60) // Certainly more than a year
                ldiff = 12*(secondsDiff/(366*24*60*60));
            else
                ldiff = secondsDiff/(31*24*60*60);
            if( secondsDiff >= 0)
            {
                if (ldiff >= Integer.MAX_VALUE)
                    throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER");
                // cal holds the time for time1
                cal.add( Calendar.MONTH, (int) (ldiff + 1));
                for(;;)
                {
                    if( cal.getTime().getTime()/1000 > thisInSeconds)
                        break;
                    cal.add( Calendar.MONTH, 1);
                    ldiff++;
                }
            }
            else
            {
                if (ldiff <= Integer.MIN_VALUE)
                    throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER");
                // cal holds the time for time1
                cal.add( Calendar.MONTH, (int) (ldiff - 1));
                for(;;)
                {
                    if( cal.getTime().getTime()/1000 < thisInSeconds)
                        break;
                    cal.add( Calendar.MONTH, -1);
                    ldiff--;
                }
            }
            if( intervalType == QUARTER_INTERVAL)
                ldiff = ldiff/3;
            break;

        case YEAR_INTERVAL:
            // Make a conservative guess and increment until we overshoot.
            ldiff = secondsDiff/(366*24*60*60);
            if( secondsDiff >= 0)
            {
                if (ldiff >= Integer.MAX_VALUE)
                    throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER");
                // cal holds the time for time1
                cal.add( Calendar.YEAR, (int) (ldiff + 1));
                for(;;)
                {
                    if( cal.getTime().getTime()/1000 > thisInSeconds)
                        break;
                    cal.add( Calendar.YEAR, 1);
                    ldiff++;
                }
            }
            else
            {
                if (ldiff <= Integer.MIN_VALUE)
                    throw StandardException.newException(SQLState.LANG_OUTSIDE_RANGE_FOR_DATATYPE, "INTEGER");
                // cal holds the time for time1
                cal.add( Calendar.YEAR, (int) (ldiff - 1));
                for(;;)
                {
                    if( cal.getTime().getTime()/1000 < thisInSeconds)
                        break;
                    cal.add( Calendar.YEAR, -1);
                    ldiff--;
                }
            }
            break;

        default:
            throw StandardException.newException( SQLState.LANG_INVALID_FUNCTION_ARGUMENT,
                                                  ReuseFactory.getInteger( intervalType),
                                                  "TIMESTAMPDIFF");
        }
        resultHolder.setValue(ldiff);
        return resultHolder;
    } // end of timestampDiff

    /**
     * Promotes a DateTimeDataValue to a timestamp.
     *
     *
     * @return the corresponding timestamp, using the current date if datetime is a time,
     *         or time 00:00:00 if datetime is a date.
     *
     * @exception StandardException
     */
    static SQLTimestamp promote( DateTimeDataValue dateTime, java.sql.Date currentDate) throws StandardException
    {
        if( dateTime instanceof SQLTimestamp)
            return (SQLTimestamp) dateTime;
        else if( dateTime instanceof SQLTime)
            return new SQLTimestamp( SQLDate.computeEncodedDate( currentDate, (Calendar) null),
                                    ((SQLTime) dateTime).getEncodedTime(),
                                    0 /* nanoseconds */);
        else if( dateTime instanceof SQLDate)
            return new SQLTimestamp( ((SQLDate) dateTime).getEncodedDate(), 0, 0);
        else
            return new SQLTimestamp( dateTime.getTimestamp( SQLDate.GREGORIAN_CALENDAR.get()));
    } // end of promote
    
    public Format getFormat() {
        return Format.TIMESTAMP;
    }

    @Override
    public void read(Row row, int ordinal) throws StandardException {
        if (row.isNullAt(ordinal))
            setToNull();
        else {
            Timestamp ts = row.getTimestamp(ordinal);
            setNumericTimestamp(ts,null);
            isNull = false;
        }
    }

    @Override
    public StructField getStructField(String columnName) {
        return DataTypes.createStructField(columnName, DataTypes.TimestampType, true);
    }


    @Override
    public void updateThetaSketch(UpdateSketch updateSketch) {
        updateSketch.update(new int[]{encodedDate,encodedTime,nanos});
    }

    @Override
    public void setSparkObject(Object sparkObject) throws StandardException {
        if (sparkObject == null)
            setToNull();
        else {
            setValue((Timestamp) sparkObject);
            setIsNull(false);
        }
    }

    public int getNanos() { return nanos; }
    public int getEncodedDate() { return encodedDate; }
    public int getEncodedTime() { return encodedTime; }
}
