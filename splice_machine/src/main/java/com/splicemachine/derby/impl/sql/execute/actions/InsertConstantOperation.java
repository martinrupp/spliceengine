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

package com.splicemachine.derby.impl.sql.execute.actions;

import com.google.protobuf.ByteString;
import com.splicemachine.db.catalog.types.TypeMessage;
import com.splicemachine.db.iapi.services.io.ArrayUtil;
import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.db.iapi.sql.dictionary.IndexRowGenerator;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.splicemachine.db.iapi.types.HBaseRowLocation;
import com.splicemachine.db.iapi.types.ProtobufUtils;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.db.impl.sql.CatalogMessage;
import com.splicemachine.db.impl.sql.execute.FKInfo;
import com.splicemachine.db.impl.sql.execute.TriggerInfo;
import com.splicemachine.db.catalog.UUID;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import splice.com.google.common.collect.Lists;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 *	This class  describes compiled constants that are passed into
 *	InsertResultSets.
 *
 */
public class InsertConstantOperation extends WriteCursorConstantOperation {
	/********************************************************
	**
	**	This class implements Formatable. But it is NOT used
 	**	across either major or minor releases.  It is only
	** 	written persistently in stored prepared statements, 
	**	not in the replication stage.  SO, IT IS OK TO CHANGE
	**	ITS read/writeExternal.
	**
	********************************************************/
	/* Which (0-based) columns are indexed */
	boolean[]	indexedCols;

	/* These variables are needed to support Autoincrement-- after an insert
	 * we need to remember the last autoincrement value inserted into the 
	 * table and the user could do a search based on schema,table,columnname
	 */
	private String schemaName;
	private String tableName;
	private String columnNames[];

	/**
	 * An array of row location objects (0 based), one for each
	 * column in the table. If the column is an 
	 * autoincrement table then the array points to
	 * the row location of the column in SYSCOLUMNS.
	 * if not, then it contains null.
	 */
	protected RowLocation[] autoincRowLocation;
	private long[] autoincIncrement;
	
	// CONSTRUCTORS

	/**
	 * Public niladic constructor. Needed for Formatable interface to work.
	 *
	 */
    public	InsertConstantOperation() { 
    	super(); 
    }

	/**
	 *	Make the ConstantAction for an INSERT statement.
	 *
	 *  @param conglomId	Conglomerate ID.
	 *	@param heapSCOCI	StaticCompiledOpenConglomInfo for heap.
	 *  @param irgs			Index descriptors
	 *  @param indexCIDS	Conglomerate IDs of indices
	 *	@param indexSCOCIs	StaticCompiledOpenConglomInfos for indexes.
	 *  @param indexNames   Names of indices on this table for error reporting.
	 *  @param deferred		True means process as a deferred insert.
	 *  @param targetProperties	Properties on the target table.
	 *	@param targetUUID	UUID of target table
	 *	@param lockMode		The lockMode to use on the target table
	 *	@param fkInfo		Array of structures containing foreign key info, 
	 *						if any (may be null)
	 *	@param triggerInfo	Array of structures containing trigger info, 
	 *						if any (may be null)
     *  @param streamStorableHeapColIds Null for non rep. (0 based)
	 *  @param indexedCols	boolean[] of which (0-based) columns are indexed.
	 *  @param singleRowSource		Whether or not source is a single row source
	 *  @param autoincRowLocation Array of rowlocations of autoincrement values
	 * 							  in SYSCOLUMNS for each ai column.
	 */
	@SuppressFBWarnings(value = "EI_EXPOSE_REP2",justification = "Intentional")
	public	InsertConstantOperation(TableDescriptor tableDescriptor,
								long				conglomId,
								StaticCompiledOpenConglomInfo heapSCOCI,
                                int[] pkColumns,
								IndexRowGenerator[]	irgs,
								long[]				indexCIDS,
								StaticCompiledOpenConglomInfo[] indexSCOCIs,
								String[]			indexNames,
								boolean				deferred,
								Properties			targetProperties,
								UUID				targetUUID,
								int 				lockMode,
								FKInfo[]			fkInfo,
								TriggerInfo			triggerInfo,
								int[]               streamStorableHeapColIds,
								boolean[]			indexedCols,
								boolean				singleRowSource,
								RowLocation[]		autoincRowLocation) {
		super(conglomId, heapSCOCI, pkColumns, irgs, indexCIDS,  indexSCOCIs, indexNames,
			  deferred, targetProperties, targetUUID, lockMode, fkInfo,	 triggerInfo,
				null, null, null, streamStorableHeapColIds, singleRowSource );
		this.indexedCols = indexedCols;
		this.autoincRowLocation = autoincRowLocation;
		this.schemaName = tableDescriptor.getSchemaName();
		this.tableName  = tableDescriptor.getName();
		this.columnNames = tableDescriptor.getColumnNamesArray();
		this.autoincIncrement = tableDescriptor.getAutoincIncrementArray();
		this.indexNames = indexNames;
	}

	public	InsertConstantOperation(
			CatalogMessage.WriteCursorConstantOperation writeCursorConstantOperation) throws IOException{
		init(writeCursorConstantOperation);
	}

	// INTERFACE METHODS

	// Formatable methods
	@Override
	protected void readExternalOld (ObjectInput in) throws IOException, ClassNotFoundException {
		Object[] objectArray = null;
		super.readExternalOld(in);
		indexedCols = ArrayUtil.readBooleanArray(in);

		// RESOLVEAUTOINCREMENT: this is the new stuff-- probably version!!
		objectArray = ArrayUtil.readObjectArray(in);
		
		if (objectArray != null){
			// is there a better way to do cast the whole array?
			autoincRowLocation = new RowLocation[objectArray.length];
			for (int i = 0; i < objectArray.length; i++)
				autoincRowLocation[i] = (RowLocation)objectArray[i];
		}
		
		schemaName = (String)in.readObject();
		tableName  = (String)in.readObject();
		objectArray = ArrayUtil.readObjectArray(in);
		if (objectArray != null) {
			// is there a better way to do cast the whole array?
			columnNames = new String[objectArray.length];
			for (int i = 0; i < objectArray.length; i++)
				columnNames[i] = (String)objectArray[i];
		}
		autoincIncrement = ArrayUtil.readLongArray(in);
	}

	@Override
	protected void init(CatalogMessage.WriteCursorConstantOperation writeCursorConstantOperation) throws IOException{
		super.init(writeCursorConstantOperation);
		CatalogMessage.InsertConstantOperation insertConstantOperation =
				writeCursorConstantOperation.getExtension(CatalogMessage.InsertConstantOperation.insertConstantOperation);
		int length = insertConstantOperation.getIndexColsCount();
		indexedCols = new boolean[length];
		for (int i = 0; i < length; ++i) {
			indexedCols[i] = insertConstantOperation.getIndexCols(i);
		}
		if (insertConstantOperation.hasAutoincRowLocation()) {

			byte[] ba = insertConstantOperation.getAutoincRowLocation().toByteArray();
			try (ByteArrayInputStream bis = new ByteArrayInputStream(ba);
				 ObjectInputStream ois = new ObjectInputStream(bis)) {
				int n = ArrayUtil.readArrayLength(ois);
				autoincRowLocation = new RowLocation[n];
				ArrayUtil.readArrayItems(ois, autoincRowLocation);
			}
			catch (ClassNotFoundException e) {
				throw new IOException(e);
			}
		}
		schemaName = insertConstantOperation.hasSchemaName() ? insertConstantOperation.getSchemaName() : null;
		tableName = insertConstantOperation.hasTableName() ? insertConstantOperation.getTableName() : null;
		length = insertConstantOperation.getColumnNamesCount();
		if (length > 0) {
			columnNames = new String[length];
			for (int i = 0; i < length; ++i) {
				columnNames[i] = insertConstantOperation.getColumnNames(i);
			}
		}
		length = insertConstantOperation.getAutoincIncrementCount();
		autoincIncrement = new long[length];
		for (int i = 0; i < length; ++i) {
			autoincIncrement[i] = insertConstantOperation.getAutoincIncrement(i);
		}
	}

	/**
	 * Write this object to a stream of stored objects.
	 *
	 * @param out write bytes here.
	 *
	 * @exception IOException		thrown on error
	 */
	@Override
	protected void writeExternalOld( ObjectOutput out ) throws IOException {
		super.writeExternalOld(out);
		ArrayUtil.writeBooleanArray(out, indexedCols);
		ArrayUtil.writeArray(out, autoincRowLocation);
		out.writeObject(schemaName);
		out.writeObject(tableName);
		ArrayUtil.writeArray(out, columnNames);
		ArrayUtil.writeLongArray(out, autoincIncrement);
	}

	@Override
	public  CatalogMessage.WriteCursorConstantOperation.Builder toProtobufBuilder() throws IOException {
		CatalogMessage.WriteCursorConstantOperation.Builder builder = super.toProtobufBuilder();

		CatalogMessage.InsertConstantOperation.Builder insertConstantOperationBuilder =
				CatalogMessage.InsertConstantOperation.newBuilder();
		if (indexedCols != null) {
			for (int i = 0; i < indexedCols.length; ++i) {
				insertConstantOperationBuilder.addIndexCols(indexedCols[i]);
			}
		}

		if (autoincRowLocation != null) {
			try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
				 ObjectOutputStream os = new ObjectOutputStream(bos)) {
				ArrayUtil.writeArray(os, autoincRowLocation);
				os.flush();
				byte[] bs = bos.toByteArray();
				insertConstantOperationBuilder.setAutoincRowLocation(ByteString.copyFrom(bs));
			}
		}
		if (schemaName != null) {
			insertConstantOperationBuilder.setSchemaName(schemaName);
		}
		if (tableName != null) {
			insertConstantOperationBuilder.setTableName(tableName);
		}

		if (columnNames != null) {
			insertConstantOperationBuilder.addAllColumnNames(Arrays.asList(columnNames));
		}

		if (autoincIncrement != null) {
			for (int i = 0; i < autoincIncrement.length; ++i) {
				insertConstantOperationBuilder.addAutoincIncrement(autoincIncrement[i]);
			}
		}

		builder.setType(CatalogMessage.WriteCursorConstantOperation.Type.InsertConstantOperation)
				.setExtension(CatalogMessage.InsertConstantOperation.insertConstantOperation,
						insertConstantOperationBuilder.build());

		return builder;
	}

		/**
          *	Gets the name of the schema that the table is in
          *
          *	@return	schema name
          */
	public String getSchemaName() { 
		return schemaName; 
	}

	/**
	  *	Gets the name of the table being inserted into
	  *
	  *	@return	name of table being inserted into
	  */
	public String getTableName() { 
		return tableName; 
	}

	/**
	 * gets the name of the desired column in the taget table.
	 * 
	 * @param 	i	the column number
	 */
	public String getColumnName(int i) { 
		return columnNames[i]; 
	}

	/**
	 * gets the increment value for a column.
	 *
	 * @param 	i 	the column number
	 */
	public long   getAutoincIncrement(int i) { 
		return autoincIncrement[i]; 
	}

	/**
	 * Does the target table has autoincrement columns.
	 *
	 * @return 	True if the table has ai columns
	 */
	public boolean hasAutoincrement() {
		return (autoincRowLocation != null);
	}

	/**
	 * gets the row location 
	 */
	@SuppressFBWarnings(value = "EI_EXPOSE_REP",justification = "Intentional")
	public RowLocation[] getAutoincRowLocation() {
		return autoincRowLocation;
	}
	
	/**
	 * Get the formatID which corresponds to this class.
	 *
	 *	@return	the formatID of this class
	 */
	public	int	getTypeFormatId()	{ 
		return StoredFormatIds.INSERT_CONSTANT_ACTION_V01_ID; 
	}
}

