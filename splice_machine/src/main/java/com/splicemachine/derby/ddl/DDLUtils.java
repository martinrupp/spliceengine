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

package com.splicemachine.derby.ddl;

import com.carrotsearch.hppc.BitSet;
import com.splicemachine.EngineDriver;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.concurrent.Clock;
import com.splicemachine.db.catalog.UUID;
import com.splicemachine.db.iapi.error.PublicAPI;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.context.Context;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.io.FormatableBitSet;
import com.splicemachine.db.iapi.services.loader.ClassFactory;
import com.splicemachine.db.iapi.sql.conn.ConnectionUtil;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.depend.DependencyManager;
import com.splicemachine.db.iapi.sql.dictionary.*;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.impl.services.uuid.BasicUUID;
import com.splicemachine.db.impl.sql.catalog.DataDictionaryCache;
import com.splicemachine.db.impl.sql.compile.ColumnDefinitionNode;
import com.splicemachine.db.impl.sql.execute.ColumnInfo;
import com.splicemachine.db.impl.sql.execute.SPSProperty;
import com.splicemachine.db.impl.sql.execute.SPSPropertyRegistry;
import com.splicemachine.ddl.DDLMessage;
import com.splicemachine.derby.DerbyMessage;
import com.splicemachine.derby.impl.sql.execute.actions.ActiveTransactionReader;
import com.splicemachine.derby.impl.sql.execute.actions.DropAliasConstantOperation;
import com.splicemachine.derby.impl.store.access.SpliceTransactionManager;
import com.splicemachine.derby.jdbc.SpliceTransactionResourceImpl;
import com.splicemachine.pipeline.ErrorState;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.protobuf.ProtoUtil;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnLifecycleManager;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.si.impl.txn.LazyTxnView;
import com.splicemachine.storage.DataScan;
import com.splicemachine.stream.Stream;
import com.splicemachine.stream.StreamException;
import com.splicemachine.utils.Pair;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;

import java.io.*;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by jleach on 11/12/15.
 *
 */
public class DDLUtils {
    private static final Logger LOG = Logger.getLogger(DDLUtils.class);

    public static DDLMessage.DDLChange performMetadataChange(DDLMessage.DDLChange ddlChange) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"performMetadataChange ddlChange=%s",ddlChange);
        notifyMetadataChangeAndWait(ddlChange);
        return ddlChange;
    }

    public static String notifyMetadataChange(DDLMessage.DDLChange ddlChange) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"notifyMetadataChange ddlChange=%s",ddlChange);
        return DDLDriver.driver().ddlController().notifyMetadataChange(ddlChange);
    }

    public static void finishMetadataChange(String changeId) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"finishMetadataChange changeId=%s",changeId);
        DDLDriver.driver().ddlController().finishMetadataChange(changeId);
        /** TODO-msirek: Try to enable this code for the mem platform.
         *               It would allow more TableDescriptors
         *               to get cached because it would clear out the DDL change
         *               as soon as it's committed, which is needed because the cacheIsValid()
         *               check requires that currChangeCount be zero.
         *               Currently, enabling this code causes a performance regression.
         */
        /* DDLDriver.driver().ddlWatcher().clearFinishedChange(changeId);   */
    }


    public static void notifyMetadataChangeAndWait(DDLMessage.DDLChange ddlChange) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"notifyMetadataChangeAndWait ddlChange=%s",ddlChange);
        String changeId = notifyMetadataChange(ddlChange);
        if (LOG.isDebugEnabled())
            SpliceLogUtils.trace(LOG,"notifyMetadataChangeAndWait changeId=%s",changeId);
        DDLDriver.driver().ddlController().finishMetadataChange(changeId);
    }

    public static TxnView getLazyTransaction(long txnId) {
        //TODO -sf- could we remove this method somehow?
        SIDriver driver=SIDriver.driver();

        return new LazyTxnView(txnId, driver.getTxnSupplier(),driver.getExceptionFactory());
    }

    @FunctionalInterface
    public interface DDLAction {
        void apply(LanguageConnectionContext lcc) throws SQLException, StandardException;
    }

    private static void run(long txnId, LanguageConnectionContext lcc, DDLAction action) throws StandardException {
        try {
            if (lcc != null) {
                action.apply(lcc);
            } else {
                try (SpliceTransactionResourceImpl transactionResource = new SpliceTransactionResourceImpl()) {
                    TxnView txn = getLazyTransaction(txnId);
                    transactionResource.marshallTransaction(txn);
                    action.apply(transactionResource.getLcc());
                }
            }
        } catch (SQLException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static String outIntArray(int[] values) {
        return values==null?"null":Arrays.toString(values);
    }

    public static String outBoolArray(boolean[] values) {
        return values==null?"null":Arrays.toString(values);
    }



    public static Txn getIndexTransaction(TransactionController tc, Txn tentativeTransaction, long tableConglomId, String indexName) throws StandardException {
        final TxnView wrapperTxn = ((SpliceTransactionManager)tc).getActiveStateTxn();

        /*
         * We have an additional waiting transaction that we use to ensure that all elements
         * which commit after the demarcation point are committed BEFORE the populate part.
         */
        byte[] tableBytes = Bytes.toBytes(Long.toString(tableConglomId));
        TxnLifecycleManager tlm = SIDriver.driver().lifecycleManager();
        Txn waitTxn;
        try{
            waitTxn = tlm.chainTransaction(wrapperTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION,false,tableBytes,tentativeTransaction);
        }catch(IOException ioe){
            LOG.error("Could not create a wait transaction",ioe);
            throw Exceptions.parseException(ioe);
        }

        //get the absolute user transaction
        TxnView uTxn = wrapperTxn;
        TxnView n = uTxn.getParentTxnView();
        while(n.getTxnId()>=0){
            uTxn = n;
            n = n.getParentTxnView();
        }
        // Wait for past transactions to die
        long oldestActiveTxn;
        try {
            oldestActiveTxn = waitForConcurrentTransactions(waitTxn, uTxn,tableConglomId);
        } catch (IOException e) {
            LOG.error("Unexpected error while waiting for past transactions to complete", e);
            throw Exceptions.parseException(e);
        }
        if (oldestActiveTxn>=0) {
            throw ErrorState.DDL_ACTIVE_TRANSACTIONS.newException("CreateIndex("+indexName+")",oldestActiveTxn);
        }
        Txn indexTxn;
        try{
            /*
             * We need to make the indexTxn a child of the wrapper, so that we can be sure
             * that the write pipeline is able to see the conglomerate descriptor. However,
             * this makes the SI logic more complex during the populate phase.
             */
            indexTxn = tlm.chainTransaction(wrapperTxn, Txn.IsolationLevel.SNAPSHOT_ISOLATION, true, tableBytes,waitTxn);
        } catch (IOException e) {
            LOG.error("Couldn't commit transaction for tentative DDL operation");
            // TODO must cleanup tentative DDL change
            throw Exceptions.parseException(e);
        }
        return indexTxn;
    }


    /**
     * Waits for concurrent transactions that started before the tentative
     * change completed.
     *
     * Performs an exponential backoff until a configurable timeout triggers,
     * then returns the list of transactions still running. The caller has to
     * forbid those transactions to ever write to the tables subject to the DDL
     * change.
     *
     * @param maximum
     *            wait for all transactions started before this one. It should
     *            be the transaction created just after the tentative change
     *            committed.
     * @param userTxn the <em>user-level</em> transaction of the ddl operation. It is important
     *                that it be the user-level, otherwise some child transactions may be treated
     *                as active when they are not actually active.
     * @return list of transactions still running after timeout
     * @throws IOException
     */
    public static long waitForConcurrentTransactions(Txn maximum, TxnView userTxn,long tableConglomId) throws IOException {
        byte[] conglomBytes = Bytes.toBytes(Long.toString(tableConglomId));

        ActiveTransactionReader transactionReader = new ActiveTransactionReader(0l,maximum.getTxnId(),conglomBytes);
        SConfiguration config = SIDriver.driver().getConfiguration();
        Clock clock = SIDriver.driver().getClock();
        long waitTime = config.getDdlRefreshInterval(); //the initial time to wait
        long maxWait = config.getMaxDdlWait(); // the maximum time to wait
        long scale = 2; //the scale factor for the exponential backoff
        long timeAvailable = maxWait;
        long activeTxnId = -1l;
        do{
            try(Stream<TxnView> activeTxns = transactionReader.getActiveTransactions()){
                TxnView txn;
                while((txn = activeTxns.next())!=null){
                    if(!txn.descendsFrom(userTxn)){
                        activeTxnId = txn.getTxnId();
                    }
                }
            } catch (StreamException e) {
                throw new IOException(e.getCause());
            }
            if(activeTxnId<0) return activeTxnId;
            /*
             * It is possible for a sleep to pick up before the
             * waitTime is expired. Therefore, we measure that actual
             * time spent and use that for our time remaining period
             * instead.
             */
            long start = clock.currentTimeMillis();
            try {
                clock.sleep(waitTime,TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            long stop = clock.currentTimeMillis();
            timeAvailable-=(stop-start);
            /*
             * We want to exponentially back off, but only to the limit imposed on us. Once
             * our backoff exceeds that limit, we want to just defer to that limit directly.
             */
            waitTime = Math.min(timeAvailable,scale*waitTime);
        } while(timeAvailable>0);

        if (activeTxnId>=0) {
            LOG.warn(String.format("Running DDL statement %s. There are transaction still active: %d", "operation Running", activeTxnId));
        }
        return activeTxnId;
    }

    /**
     * Make sure that the table exists and that it isn't a system table. Otherwise, KA-BOOM
     */
    public static  void validateTableDescriptor(TableDescriptor td,String indexName, String tableName) throws StandardException {
        if (td == null)
            throw StandardException.newException(SQLState.LANG_CREATE_INDEX_NO_TABLE, indexName, tableName);
        if (td.getTableType() == TableDescriptor.SYSTEM_TABLE_TYPE)
            throw StandardException.newException(SQLState.LANG_CREATE_SYSTEM_INDEX_ATTEMPTED, indexName, tableName);
    }

    /**
     *
     * Create a table scan for old conglomerate. Make sure to create a NonSI table scan. Transaction filtering
     * will happen at client side
     * @return
     */
    public static DataScan createFullScan() {
        DataScan scan = SIDriver.driver().getOperationFactory().newDataScan(null);
        scan.startKey(SIConstants.EMPTY_BYTE_ARRAY).stopKey(SIConstants.EMPTY_BYTE_ARRAY).returnAllVersions();
        return scan;
    }

    public static int[] getMainColToIndexPosMap(int[] indexColsToMainColMap, BitSet indexedCols) {
        int[] mainColToIndexPosMap = new int[(int) indexedCols.length()];
        for (int i = 0 ; i < indexedCols.length(); ++i) {
            mainColToIndexPosMap[i] = -1;
        }
        for (int indexCol = 0; indexCol < indexColsToMainColMap.length; indexCol++) {
            int mainCol = indexColsToMainColMap[indexCol];
            mainColToIndexPosMap[mainCol - 1] = indexCol;
        }
        return mainColToIndexPosMap;
    }

    public static BitSet getIndexedCols(int[] indexColsToMainColMap) {
        BitSet indexedCols = new BitSet();
        for (int indexCol : indexColsToMainColMap) {
            indexedCols.set(indexCol - 1);
        }
        return indexedCols;
    }

    public static byte[] getIndexConglomBytes(long indexConglomerate) {
        return Bytes.toBytes(Long.toString(indexConglomerate));
    }

    public static byte[] serializeColumnInfoArray(ColumnInfo[] columnInfos) throws StandardException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeInt(columnInfos.length);
            for (int i =0; i< columnInfos.length;i++) {
                oos.writeObject(columnInfos[i]);
            }
            oos.flush();
            oos.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static ColumnInfo[] deserializeColumnInfoArray(byte[] bytes) {
        ObjectInputStream oos = null;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            ObjectInputStream is = new ObjectInputStream(bis);
            ColumnInfo[] columnInfos = new ColumnInfo[is.readInt()];
            for (int i =0; i< columnInfos.length;i++) {
                columnInfos[i] = (ColumnInfo) is.readObject();
            }
            is.close();
            return columnInfos;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void preDropForeignKey(DDLMessage.DDLChange change, DataDictionary dd) throws StandardException {
        try {
            dd.getDataDictionaryCache().constraintDescriptorListCacheRemove(ProtoUtil.getDerbyUUID(change.getTentativeFK().getConstraintUuid()));
            dd.getDataDictionaryCache().oidTdCacheRemove(ProtoUtil.getDerbyUUID(change.getTentativeFK().getFkConstraintInfo().getChildTable().getTableUuid()));
        } catch(Exception e) {
            throw StandardException.plainWrapException(e);
        }
    }

    public static void preMultipleChanges(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        run(change.getTxnId(), null, lcc -> {
            for (DDLMessage.DDLChange subChange : change.getChangeList()) {
                dispatchChangeAction(subChange, dd, dm, lcc);
            }
        });
    }

    /**
     *
     *
     * Prepare all dependents to invalidate.  (There is a chance
     * to say that they can't be invalidated.  For example, an open
     * cursor referencing a table/view that the user is attempting to
     * drop.) If no one objects, then invalidate any dependent objects.
     * We check for invalidation before we drop the table descriptor
     * since the table descriptor may be looked up as part of
     * decoding tuples in SYSDEPENDS.
     *
     *
     * @param change
     * @param dd
     * @param dm
     * @throws StandardException
     */
    public static void preDropTable(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropTable with change=%s",change);
        run(change.getTxnId(), lcc, innerLcc -> {
            TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(change.getDropTable().getTableId()), null);
            if (td == null) // Table Descriptor transaction never committed
                return;
            dm.invalidateFor(td, DependencyManager.DROP_TABLE, innerLcc);
        });
    }

    public static void preAlterStats(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preAlterStats with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            List<DerbyMessage.UUID> tdUIDs=change.getAlterStats().getTableIdList();
            for(DerbyMessage.UUID uuuid : tdUIDs){
                TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid), null);
                if(td==null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td,DependencyManager.DROP_STATISTICS,lcc);
            }
        });
    }

    public static void preDropSchema(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "preDropSchema with change=%s", change);
        run(change.getTxnId(), lcc, innerLcc -> {
            DDLMessage.DropSchema dropSchema = change.getDropSchema();
            UUID schemaId = ProtoUtil.getDerbyUUID(dropSchema.getSchemaUUID());
            SchemaDescriptor sd = dd.getSchemaDescriptor(schemaId, innerLcc.getTransactionExecute());
            if (sd == null) // Schema Descriptor transaction never committed
                return;
            dm.invalidateFor(sd, DependencyManager.DROP_SCHEMA, innerLcc);
            if (dropSchema.hasDbUUID()) {
                dd.getDataDictionaryCache().schemaCacheRemove(ProtoUtil.getDerbyUUID(dropSchema.getDbUUID()), dropSchema.getSchemaName());
            } else { // This was sent by a region server that does not support multidb yet. We invalidate the whole cache
                SpliceLogUtils.warn(LOG, "We cannot invalidate schema cache for %s without DB information. " +
                        "Invalidating the whole cache instead", dropSchema.getSchemaName());
                dd.getDataDictionaryCache().clearSchemaCache();
            }
            dd.getDataDictionaryCache().oidSchemaCacheRemove(schemaId);
        });
    }

    public static void preDropDatabase(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropDatabase with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            String dbName = change.getDropDatabase().getDbName();
            DatabaseDescriptor desc = dd.getDatabaseDescriptor(dbName, lcc.getTransactionExecute());
            if (desc == null)
                return;
            dm.invalidateFor(desc, DependencyManager.DROP_DATABASE, lcc);
            dd.getDataDictionaryCache().databaseCacheRemove(change.getDropDatabase().getDbName());
        });
    }

    public static void preUpdateSchemaOwner(DDLMessage.DDLChange change, DataDictionary dd) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preUpdateSchemaOwner with change=%s",change);

        run(change.getTxnId(), null, lcc -> {
            DDLMessage.UpdateSchemaOwner uso = change.getUpdateSchemaOwner();
            DataDictionaryCache cache = dd.getDataDictionaryCache();
            // remove corresponding schema cache entry
            if (uso.hasDbUUID()) {
                cache.schemaCacheRemove(ProtoUtil.getDerbyUUID(uso.getDbUUID()), uso.getSchemaName());
            } else {
                cache.clearSchemaCache();
            }
            cache.oidSchemaCacheRemove(ProtoUtil.getDerbyUUID(uso.getSchemaUUID()));
            // clear permission cache as it has out-of-date permission info for the schema
            cache.clearPermissionCache();
            // clear  TableDescriptor cache as it may reference the schema with an out-of-date authorization id
            cache.clearOidTdCache();
            cache.clearNameTdCache();
        });
    }

    public static void preCreateIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        preIndex(change, dd, dm, DependencyManager.CREATE_INDEX, change.getTentativeIndex().getTable().getTableUuid());
    }

    public static void preDropIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropIndex with change=%s",change);
        run(change.getTxnId(), lcc, innerLcc -> {
            TransactionController tc = innerLcc.getTransactionExecute();
            DDLMessage.DropIndex dropIndex = change.getDropIndex();
            TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(dropIndex.getTableUUID()), null);
                SchemaDescriptor sd;
            if (td != null) { // Table Descriptor transaction never committed
                dm.invalidateFor(td, DependencyManager.ALTER_TABLE, innerLcc);
                sd = td.getSchemaDescriptor();
            } else if (dropIndex.hasDbUUID()){
                sd = dd.getSchemaDescriptor(ProtoUtil.getDerbyUUID(dropIndex.getDbUUID()), dropIndex.getSchemaName(), tc, true);
            } else { // This was sent by a region server that does not support multidb yet. We don't know yet what to do but throw
                throw new IllegalStateException("preDropIndex called from a region server that does not support multidb");
            }
                ConglomerateDescriptor cd = dd.getConglomerateDescriptor(dropIndex.getIndexName(), sd, true);
            if (cd != null) {
                dm.invalidateFor(cd, DependencyManager.DROP_INDEX, innerLcc);
            }
        });
    }

    private static void preIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, int action, DerbyMessage.UUID uuid) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preIndex with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuid), null);
            if (td == null) // Table Descriptor transaction never committed
                return;
            dm.invalidateFor(td, action, lcc);
        });
    }

    public static void preRenameTable(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRenameTable with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            DerbyMessage.UUID uuuid = change.getRenameTable().getTableId();
            TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid), null);
            if (td == null) // Table Descriptor transaction never committed
                return;
            dm.invalidateFor(td, DependencyManager.RENAME, lcc);
            /* look for foreign key dependency on the table. If found any,
            use dependency manager to pass the rename action to the
            dependents. */
                ConstraintDescriptorList constraintDescriptorList=dd.getConstraintDescriptors(td);
                for(int index=0;index<constraintDescriptorList.size();index++){
                    ConstraintDescriptor constraintDescriptor=constraintDescriptorList.elementAt(index);
                    if(constraintDescriptor instanceof ReferencedKeyConstraintDescriptor)
                    dm.invalidateFor(constraintDescriptor, DependencyManager.RENAME, lcc);
            }
        });
    }

    public static void preRenameColumn(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRenameColumn with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            DerbyMessage.UUID uuuid=change.getRenameColumn().getTableId();
            TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid), null);
            if(td==null) // Table Descriptor transaction never committed
                return;
            ColumnDescriptor columnDescriptor=td.getColumnDescriptor(change.getRenameColumn().getColumnName());
            if(columnDescriptor.isAutoincrement())
                columnDescriptor.setAutoinc_create_or_modify_Start_Increment(
                        ColumnDefinitionNode.CREATE_AUTOINCREMENT);

            int columnPosition=columnDescriptor.getPosition();
            FormatableBitSet toRename=new FormatableBitSet(td.getColumnDescriptorList().size()+1);
            toRename.set(columnPosition);
            td.setReferencedColumnMap(toRename);
            dm.invalidateFor(td,DependencyManager.RENAME,lcc);

            //look for foreign key dependency on the column.
            ConstraintDescriptorList constraintDescriptorList=dd.getConstraintDescriptors(td);
            for(int index=0;index<constraintDescriptorList.size();index++){
                ConstraintDescriptor constraintDescriptor=constraintDescriptorList.elementAt(index);
                int[] referencedColumns=constraintDescriptor.getReferencedColumns();
                int numRefCols=referencedColumns.length;
                for(int j=0;j<numRefCols;j++){
                    if((referencedColumns[j]==columnPosition) &&
                            (constraintDescriptor instanceof ReferencedKeyConstraintDescriptor))
                        dm.invalidateFor(constraintDescriptor,DependencyManager.RENAME,lcc);
                }
            }
        });
    }

    public static void preRenameIndex(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRenameIndex with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            DerbyMessage.UUID uuuid=change.getRenameIndex().getTableId();
            TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid), null);
            if(td==null) // Table Descriptor transaction never committed
                return;
            dm.invalidateFor(td,DependencyManager.RENAME_INDEX, lcc);
        });
    }

    public static void preDropAlias(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropAlias with change=%s",change);
        run(change.getTxnId(), lcc, innerLcc -> {
            DDLMessage.DropAlias dropAlias=change.getDropAlias();
                AliasDescriptor ad=dd.getAliasDescriptor(dropAlias.getSchemaName(),dropAlias.getAliasName(),dropAlias.getNamespace().charAt(0), null);
            if(ad==null) // Table Descriptor transaction never committed
                return;
            DropAliasConstantOperation.invalidate(ad,dm,innerLcc);
        });
    }

    public static void preNotifyJarLoader(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preNotifyJarLoader with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            dd.invalidateAllSPSPlans(); // This will break other nodes, must do ddl
            ClassFactory cf = lcc.getLanguageConnectionFactory().getClassFactory();
            DDLMessage.NotifyJarLoader njl = change.getNotifyJarLoader();
            cf.notifyModifyJar(njl.getReload());
            if (njl.getDrop()) {
                if (!njl.hasDbUUID()) { // This was sent by a region server that does not support multidb yet. We don't know yet what to do but throw
                    throw new IllegalStateException("preNotifyJarLoader called from a region server that does not support multidb");
                }
                SchemaDescriptor sd = dd.getSchemaDescriptor(ProtoUtil.getDerbyUUID(njl.getDbUUID()), njl.getSchemaName(), null, true);
                if (sd ==null)
                    return;
                    FileInfoDescriptor fid = dd.getFileInfoDescriptor(sd, njl.getSqlName());
                if (fid==null)
                    return;
                dm.invalidateFor(fid, DependencyManager.DROP_JAR, lcc);
            }
        });
    }

    public static void postNotifyJarLoader(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preNotifyJarLoader with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            ClassFactory cf = lcc.getLanguageConnectionFactory().getClassFactory();
            cf.notifyModifyJar(true);
        });
    }

    public static void preNotifyModifyClasspath(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preNotifyModifyClasspath with change=%s",change);
        run(change.getTxnId(), null,
                lcc -> lcc.getLanguageConnectionFactory().getClassFactory().notifyModifyClasspath(change.getNotifyModifyClasspath().getClasspath())
        );
    }

    public static void preDropView(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropView with change=%s",change);
        run(change.getTxnId(), lcc, innerLcc -> {
            DDLMessage.DropView dropView=change.getDropView();

            TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(dropView.getTableId()), null);
            if(td==null) // Table Descriptor transaction never committed
                return;
            dm.invalidateFor(td,DependencyManager.DROP_VIEW,innerLcc);
        });
    }

    public static void preDropSequence(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropSequence with change=%s",change);
        run(change.getTxnId(), lcc, innerLcc -> {
            DDLMessage.DropSequence dropSequence=change.getDropSequence();
            TransactionController tc = innerLcc.getTransactionExecute();
            if (!dropSequence.hasDbUUID()){
                // This was sent by a region server that does not support multidb yet. We don't know yet what to do but throw
                throw new IllegalStateException("preDropSequence called from a region server that does not support multidb");
            }
            SchemaDescriptor sd = dd.getSchemaDescriptor(ProtoUtil.getDerbyUUID(dropSequence.getDbUUID()), dropSequence.getSchemaName(),tc,true);
            if(sd==null) // Table Descriptor transaction never committed
                return;
            SequenceDescriptor seqDesc = dd.getSequenceDescriptor(sd,dropSequence.getSequenceName());
            if (seqDesc==null)
                return;
            dm.invalidateFor(seqDesc, DependencyManager.DROP_SEQUENCE, innerLcc);
        });
    }

    public static void preCreateRole(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preCreateTrigger with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            String roleName=change.getCreateRole().getRoleName();
            dd.getDataDictionaryCache().roleCacheRemove(roleName);
        });
    }


    public static void preCreateTrigger(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preCreateTrigger with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            DerbyMessage.UUID uuuid=change.getCreateTrigger().getTableId();
            TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuuid), null);
            if(td==null)
                return;
            dm.invalidateFor(td,DependencyManager.CREATE_TRIGGER,lcc);
        });
    }

    public static void preDropTrigger(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropTrigger with change=%s",change);
        run(change.getTxnId(), lcc, innerLcc -> {
            DerbyMessage.UUID tableuuid=change.getDropTrigger().getTableId();
            DerbyMessage.UUID triggeruuid=change.getDropTrigger().getTriggerId();
            SPSDescriptor spsd=dd.getSPSDescriptor(ProtoUtil.getDerbyUUID(change.getDropTrigger().getSpsDescriptorUUID()));

            TableDescriptor td=dd.getTableDescriptor(ProtoUtil.getDerbyUUID(tableuuid), null);
            TriggerDescriptor triggerDescriptor=dd.getTriggerDescriptor(ProtoUtil.getDerbyUUID(triggeruuid));
            if(td!=null)
                dm.invalidateFor(td,DependencyManager.DROP_TRIGGER,innerLcc);
            if(triggerDescriptor!=null){
                dm.invalidateFor(triggerDescriptor,DependencyManager.DROP_TRIGGER,innerLcc);
//                dm.clearDependencies(innerLcc, triggerDescriptor);
                if(triggerDescriptor.getWhenClauseId()!=null){
                    SPSDescriptor whereDescriptor=dd.getSPSDescriptor(triggerDescriptor.getWhenClauseId());
                    if(whereDescriptor!=null){
                        dm.invalidateFor(whereDescriptor,DependencyManager.DROP_TRIGGER,innerLcc);
//                        dm.clearDependencies(innerLcc, whereDescriptor);
                    }
                }
            }
            if(spsd!=null){
                dm.invalidateFor(spsd,DependencyManager.DROP_TRIGGER,innerLcc);
                //               dm.clearDependencies(innerLcc, spsd);
            }
            // Remove all TECs from trigger stack. They will need to be rebuilt.
            innerLcc.popAllTriggerExecutionContexts();
        });
    }

    public static void preAlterTable(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException  {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preAlterTable with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            for (DerbyMessage.UUID uuid : change.getAlterTable().getTableIdList()) {
                TableDescriptor td = dd.getTableDescriptor(ProtoUtil.getDerbyUUID(uuid), null);
                if (td == null) // Table Descriptor transaction never committed
                    return;
                dm.invalidateFor(td, DependencyManager.ALTER_TABLE, lcc);
            }
        });
    }

    public static void preDropRole(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preDropRole with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            DDLMessage.DropRole dropRole = change.getDropRole();
            String roleName = dropRole.getRoleName();
            if (!dropRole.hasDbUUID()) {
                // This was sent by a region server that does not support multidb yet. We don't know yet what to do but throw
                throw new IllegalStateException("preDropRole called from a region server that does not support multidb");
            }
            UUID dbId = ProtoUtil.getDerbyUUID(dropRole.getDbUUID());
            RoleClosureIterator rci = dd.createRoleClosureIterator (lcc.getTransactionCompile(), roleName, false, dbId);

            String role;
            while ((role = rci.next()) != null) {
                RoleGrantDescriptor r = dd.getRoleDefinitionDescriptor(role, dbId);
                if (r != null) {
                    dm.invalidateFor(r, DependencyManager.REVOKE_ROLE, lcc);
                }
            }

            dd.getDataDictionaryCache().roleCacheRemove(dropRole.getRoleName());
            // role grant cache may have entries of this role being granted to others, so need to invalidate
            dd.getDataDictionaryCache().clearRoleGrantCache();
            // permission cache may have permission entries related to this role, so need to invalidate
            dd.getDataDictionaryCache().clearPermissionCache();
        });
    }

    public static void preTruncateTable(DDLMessage.DDLChange change,
                                        DataDictionary dd,
                                        DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preTruncateTable with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            BasicUUID uuid = ProtoUtil.getDerbyUUID(change.getTruncateTable().getTableId());
            TableDescriptor td = dd.getTableDescriptor(uuid, null);
            dm.invalidateFor(td, DependencyManager.TRUNCATE_TABLE, lcc);

        });
    }

    public static void preRevokePrivilege(DDLMessage.DDLChange change,
                                          DataDictionary dd,
                                          DependencyManager dm) throws StandardException{
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRevokePrivilege with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            DDLMessage.RevokePrivilege revokePrivilege = change.getRevokePrivilege();
            DDLMessage.RevokePrivilege.Type type = revokePrivilege.getType();
            DDLMessage.RevokePrivilege.OpType op = revokePrivilege.getOp();
            boolean isGrant = (op == DDLMessage.RevokePrivilege.OpType.GRANT_OP);
            if (type == DDLMessage.RevokePrivilege.Type.REVOKE_TABLE_PRIVILEGE) {
                preRevokeTablePrivilege(revokePrivilege.getRevokeTablePrivilege(), dd, dm, lcc, isGrant);
            } else if (type == DDLMessage.RevokePrivilege.Type.REVOKE_COLUMN_PRIVILEGE) {
                preRevokeColumnPrivilege(revokePrivilege.getRevokeColumnPrivilege(), dd, dm, lcc, isGrant);
            } else if (type == DDLMessage.RevokePrivilege.Type.REVOKE_ROUTINE_PRIVILEGE) {
                preRevokeRoutinePrivilege(revokePrivilege.getRevokeRoutinePrivilege(), dd, dm, lcc, isGrant);
            } else if (type == DDLMessage.RevokePrivilege.Type.REVOKE_SCHEMA_PRIVILEGE) {
                preRevokeSchemaPrivilege(revokePrivilege.getRevokeSchemaPrivilege(), dd, dm, lcc, isGrant);
            } else if (type == DDLMessage.RevokePrivilege.Type.REVOKE_GENERIC_PRIVILEGE) {
                preRevokeGenericPrivilege(revokePrivilege.getRevokeGenericPrivilege(), dd, dm, lcc, isGrant);
            }
        });
    }

    private static void preRevokeTablePrivilege(DDLMessage.RevokeTablePrivilege revokeTablePrivilege,
                                                DataDictionary dd,
                                                DependencyManager dm,
                                                LanguageConnectionContext lcc,
                                                boolean isGrant) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeTablePrivilege.getTableId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeTablePrivilege.getPermObjectId());
        TableDescriptor td = dd.getTableDescriptor(uuid, null);

        TablePermsDescriptor tablePermsDesc =
            new TablePermsDescriptor(
                dd,
                revokeTablePrivilege.getGrantee(),
                revokeTablePrivilege.getGrantor(),
                uuid,
                revokeTablePrivilege.getSelectPerm(),
                revokeTablePrivilege.getDeletePerm(),
                revokeTablePrivilege.getInsertPerm(),
                revokeTablePrivilege.getUpdatePerm(),
                revokeTablePrivilege.getReferencesPerm(),
                revokeTablePrivilege.getTriggerPerm());
        tablePermsDesc.setUUID(objectId);
        if (!isGrant) {
            dm.invalidateFor(tablePermsDesc, DependencyManager.REVOKE_PRIVILEGE, lcc);
            dm.invalidateFor(td, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
        }
        dd.getDataDictionaryCache().permissionCacheRemove(tablePermsDesc);
    }

    private static void preRevokeSchemaPrivilege(DDLMessage.RevokeSchemaPrivilege revokeSchemaPrivilege,
                                                DataDictionary dd,
                                                DependencyManager dm,
                                                LanguageConnectionContext lcc,
                                                boolean isGrant) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeSchemaPrivilege.getSchemaId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeSchemaPrivilege.getPermObjectId());
        SchemaDescriptor sd = dd.getSchemaDescriptor(uuid, null);

        SchemaPermsDescriptor schemaPermsDesc =
                new SchemaPermsDescriptor(
                        dd,
                        revokeSchemaPrivilege.getGrantee(),
                        revokeSchemaPrivilege.getGrantor(),
                        uuid,
                        revokeSchemaPrivilege.getSelectPerm(),
                        revokeSchemaPrivilege.getDeletePerm(),
                        revokeSchemaPrivilege.getInsertPerm(),
                        revokeSchemaPrivilege.getUpdatePerm(),
                        revokeSchemaPrivilege.getReferencesPerm(),
                        revokeSchemaPrivilege.getTriggerPerm(),
                        revokeSchemaPrivilege.getModifyPerm(),
                        revokeSchemaPrivilege.getAccessPerm());
        schemaPermsDesc.setUUID(objectId);
        if (!isGrant) {
            dm.invalidateFor(schemaPermsDesc, DependencyManager.REVOKE_PRIVILEGE, lcc);
            dm.invalidateFor(sd, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
        }
        dd.getDataDictionaryCache().permissionCacheRemove(schemaPermsDesc);
    }

    private static void preRevokeColumnPrivilege(DDLMessage.RevokeColumnPrivilege revokeColumnPrivilege,
                                                 DataDictionary dd,
                                                 DependencyManager dm,
                                                 LanguageConnectionContext lcc,
                                                 boolean isGrant) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeColumnPrivilege.getTableId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeColumnPrivilege.getPermObjectId());
        TableDescriptor td = dd.getTableDescriptor(uuid, null);
        ColPermsDescriptor colPermsDescriptor =
            new ColPermsDescriptor(
                dd,
                revokeColumnPrivilege.getGrantee(),
                revokeColumnPrivilege.getGrantor(),
                uuid,
                revokeColumnPrivilege.getType(),
                revokeColumnPrivilege.hasColumns()?new FormatableBitSet(revokeColumnPrivilege.getColumns().toByteArray()):null);
        colPermsDescriptor.setUUID(objectId);

        if (!isGrant) {
            // only revoke statements need to invalidate the dependent objects
            dm.invalidateFor(colPermsDescriptor, DependencyManager.REVOKE_PRIVILEGE, lcc);
            dm.invalidateFor(td, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
        }

        // both grant and revoke column permissions need to trigger cache invalidation
        dd.getDataDictionaryCache().permissionCacheRemove(colPermsDescriptor);
    }

    private static void preRevokeRoutinePrivilege(DDLMessage.RevokeRoutinePrivilege revokeRoutinePrivilege,
                                                  DataDictionary dd,
                                                  DependencyManager dm,
                                                  LanguageConnectionContext lcc,
                                                  boolean isGrant) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeRoutinePrivilege.getRountineId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeRoutinePrivilege.getPermObjectId());
        RoutinePermsDescriptor routinePermsDescriptor =
            new RoutinePermsDescriptor(
                dd,
                revokeRoutinePrivilege.getGrantee(),
                revokeRoutinePrivilege.getGrantor(),
                uuid, null);
        routinePermsDescriptor.setUUID(objectId);

        if (!isGrant) {
            dm.invalidateFor(routinePermsDescriptor, DependencyManager.REVOKE_PRIVILEGE_RESTRICT, lcc);

            AliasDescriptor aliasDescriptor = dd.getAliasDescriptor(objectId, null);
            if (aliasDescriptor != null) {
                dm.invalidateFor(aliasDescriptor, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
            }
        }
        dd.getDataDictionaryCache().permissionCacheRemove(routinePermsDescriptor);
    }

    private static void preRevokeGenericPrivilege(DDLMessage.RevokeGenericPrivilege revokeGenericPrivilege,
                                                  DataDictionary dd,
                                                  DependencyManager dm,
                                                  LanguageConnectionContext lcc,
                                                  boolean isGrant) throws StandardException{

        BasicUUID uuid = ProtoUtil.getDerbyUUID(revokeGenericPrivilege.getId());
        BasicUUID objectId = ProtoUtil.getDerbyUUID(revokeGenericPrivilege.getPermObjectId());
        PermDescriptor permDescriptor =
            new PermDescriptor(
                dd,
                uuid,
                revokeGenericPrivilege.getObjectType(),
                objectId,
                revokeGenericPrivilege.getPermission(),
                revokeGenericPrivilege.getGrantor(),
                revokeGenericPrivilege.getGrantee(),
                revokeGenericPrivilege.getGrantable());
        if (!isGrant) {
            int invalidationType = revokeGenericPrivilege.getRestrict() ?
                    DependencyManager.REVOKE_PRIVILEGE_RESTRICT : DependencyManager.REVOKE_PRIVILEGE;

            dm.invalidateFor(permDescriptor, invalidationType, lcc);

            PrivilegedSQLObject privilegedSQLObject = null;
            if (revokeGenericPrivilege.getObjectType().compareToIgnoreCase("SEQUENCE") == 0) {
                privilegedSQLObject = dd.getSequenceDescriptor(objectId);
            } else {
                privilegedSQLObject = dd.getAliasDescriptor(objectId, null);
            }
            if (privilegedSQLObject != null) {
                dm.invalidateFor(privilegedSQLObject, invalidationType, lcc);
            }
        }
        dd.getDataDictionaryCache().permissionCacheRemove(permDescriptor);
    }

    public static void preGrantRevokeRole(DDLMessage.DDLChange change, DataDictionary dd) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preGrantRevokeRole with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            //remove corresponding defaultRole entry
            dd.getDataDictionaryCache().defaultRoleCacheRemove(change.getGrantRevokeRole().getGranteeName());

            // remove role grant cache
            String roleName = change.getGrantRevokeRole().getRoleName();
            String granteeName = change.getGrantRevokeRole().getGranteeName();
            dd.getDataDictionaryCache().roleGrantCacheRemove(new Pair<>(roleName, granteeName));
        });
    }

    public static void preSetDatabaseProperty(DDLMessage.DDLChange change, DataDictionary dd) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preSetDatabaseProperty with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            //remove corresponding property from the data dictionary cache.
            dd.getDataDictionaryCache().propertyCacheRemove(change.getSetDatabaseProperty().getPropertyName());
            // invalidate dependents if it is an SPS property
            invalidateSpsProperty(change.getSetDatabaseProperty().getPropertyName());
        });
    }

    /**
     * If they key corresponds to an SPS property, it will invalidate all dependent objects on it using the dependency
     * manager.
     * @param key the property key
     */
    public static void invalidateSpsProperty(final String key) throws SQLException {
        try {
            SPSProperty p = SPSPropertyRegistry.forName(key);
            if(p == null) {
                return; // property is not an SPSProperty.
            }
            LanguageConnectionContext lcc = ConnectionUtil.getCurrentLCC();
            lcc.getDataDictionary().getDependencyManager().invalidateFor(p, DependencyManager.INTERNAL_RECOMPILE_REQUEST, lcc);
            // check invalidating remote as well.
        } catch (StandardException se) {
            throw PublicAPI.wrapStandardException(se);
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    public static void preUpdateSystemProcedures(DDLMessage.DDLChange change, DataDictionary dd) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preUpdateSystemProcedures with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            //clear alias cache
            DataDictionaryCache dc = dd.getDataDictionaryCache();
            dc.clearAliasCache();
            dc.clearStatementCache();
            dc.clearPermissionCache();
            dc.clearSpsNameCache();
            dc.clearStoredPreparedStatementCache();
        });
    }

    public static void preLeaveRestore(DDLMessage.DDLChange change, DataDictionary dd) throws StandardException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"preRestore with change=%s",change);
        run(change.getTxnId(), null, lcc -> {
            SIDriver.driver().getTxnSupplier().invalidate();
            SIDriver.driver().getIgnoreTxnSupplier().refresh();
            dd.getDataDictionaryCache().clearAll();
            SIDriver.driver().lifecycleManager().leaveRestoreMode();
            Collection<Context> allContexts = ContextService.getService().getAllContexts(LanguageConnectionContext.CONTEXT_ID);
            allContexts = ContextService.getService().getAllContexts(LanguageConnectionContext.CONTEXT_ID);
            for (Context context : allContexts) {
                ((LanguageConnectionContext) context).leaveRestoreMode();
            }
        });
    }

    public static void dispatchChangeAction(DDLMessage.DDLChange change, DataDictionary dd, DependencyManager dm, LanguageConnectionContext lcc) throws StandardException {
        switch(change.getDdlChangeType()){
            case MULTIPLE_CHANGES:
                preMultipleChanges(change, dd, dm);
                break;
            case CREATE_INDEX:
                preCreateIndex(change,dd,dm);
                break;
            case DROP_INDEX:
                preDropIndex(change,dd,dm, lcc);
                break;
            case DROP_SEQUENCE:
                preDropSequence(change,dd,dm, lcc);
                break;
            case CHANGE_PK:
            case ADD_CHECK:
            case ADD_NOT_NULL:
            case ADD_COLUMN:
            case ADD_PRIMARY_KEY:
            case ADD_UNIQUE_CONSTRAINT:
            case DROP_COLUMN:
            case DROP_CONSTRAINT:
            case DROP_PRIMARY_KEY:
            case DICTIONARY_UPDATE:
            case CREATE_TABLE:
            case CREATE_SCHEMA:
                break;
            case DROP_TABLE:
                preDropTable(change,dd,dm, lcc);
                break;
            case DROP_VIEW:
                preDropView(change,dd,dm, lcc);
                break;
            case ALTER_TABLE:
                preAlterTable(change,dd,dm);
                break;
            case RENAME_TABLE:
                preRenameTable(change,dd,dm);
                break;
            case CREATE_TRIGGER:
                preCreateTrigger(change,dd,dm);
                break;
            case CREATE_ROLE:
                preCreateRole(change,dd,dm);
                break;
            case DROP_TRIGGER:
                preDropTrigger(change,dd,dm, lcc);
                break;
            case DROP_ALIAS:
                preDropAlias(change,dd,dm, lcc);
                break;
            case RENAME_INDEX:
                preRenameIndex(change,dd,dm);
                break;
            case RENAME_COLUMN:
                preRenameColumn(change,dd,dm);
                break;
            case DROP_SCHEMA:
                preDropSchema(change,dd,dm, lcc);
                break;
            case UPDATE_SCHEMA_OWNER:
                preUpdateSchemaOwner(change,dd);
                break;
            case DROP_ROLE:
                preDropRole(change,dd,dm);
                break;
            case TRUNCATE_TABLE:
                preTruncateTable(change,dd,dm);
                break;
            case REVOKE_PRIVILEGE:
                preRevokePrivilege(change,dd,dm);
                break;
            case ALTER_STATS:
                preAlterStats(change,dd,dm);
                break;
            case ENTER_RESTORE_MODE:
                SIDriver.driver().lifecycleManager().enterRestoreMode();
                Collection<Context> allContexts = ContextService.getService().getAllContexts(LanguageConnectionContext.CONTEXT_ID);
                for (Context context : allContexts) {
                    ((LanguageConnectionContext) context).enterRestoreMode();
                }
                break;
            case SET_REPLICATION_ROLE:
                String role = change.getSetReplicationRole().getRole();
                SIDriver.driver().lifecycleManager().setReplicationRole(role);
                allContexts=ContextService.getFactory().getAllContexts(LanguageConnectionContext.CONTEXT_ID);
                for(Context context : allContexts){
                    ((LanguageConnectionContext) context).setReplicationRole(role);
                }
                SpliceLogUtils.info(LOG,"set replication role to %s", role);
                break;
            case ROLLING_UPGRADE:
                DDLMessage.RollingUpgrade.OperationType type = change.getRollingUpgrade().getType();
                if (type == DDLMessage.RollingUpgrade.OperationType.BEGIN) {
                    SIDriver.driver().setRollingUpgrade(true);
                }
                else if (type == DDLMessage.RollingUpgrade.OperationType.END) {
                    SIDriver.driver().setRollingUpgrade(false);
                }
                break;
            case NOTIFY_JAR_LOADER:
                preNotifyJarLoader(change,dd,dm);
                break;
            case NOTIFY_MODIFY_CLASSPATH:
                preNotifyModifyClasspath(change,dd,dm);
                break;
            case REFRESH_ENTRPRISE_FEATURES:
                EngineDriver.driver().refreshEnterpriseFeatures();
                break;
            case GRANT_REVOKE_ROLE:
                preGrantRevokeRole(change, dd);
                break;
            case SET_DATABASE_PROPERTY:
                preSetDatabaseProperty(change, dd);
                break;
            case UPDATE_SYSTEM_PROCEDURES:
                preUpdateSystemProcedures(change, dd);
                break;
            case CREATE_ALIAS:
            case CREATE_VIEW:
                break;
            case LEAVE_RESTORE_MODE:
                preLeaveRestore(change, dd);
                break;
            case ADD_FOREIGN_KEY: // fallthrough, this is necessary since the parent of the foreign key now has one extra child!
            case DROP_FOREIGN_KEY:
                preDropForeignKey(change, dd);
                break;
            case DROP_DATABASE:
                DDLUtils.preDropDatabase(change,dd, dm);
                break;
            default:
                break;
        }

    }
}
