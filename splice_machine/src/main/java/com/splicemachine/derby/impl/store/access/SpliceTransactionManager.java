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

package com.splicemachine.derby.impl.store.access;

import com.splicemachine.access.api.PartitionAdmin;
import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.daemon.Serviceable;
import com.splicemachine.db.iapi.services.io.FormatableBitSet;
import com.splicemachine.db.iapi.services.io.Storable;
import com.splicemachine.db.iapi.services.locks.CompatibilitySpace;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.dictionary.*;
import com.splicemachine.db.iapi.store.access.*;
import com.splicemachine.db.iapi.store.access.conglomerate.*;
import com.splicemachine.db.iapi.store.raw.Transaction;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.impl.store.access.conglomerate.ConglomerateUtil;
import com.splicemachine.derby.ddl.DDLUtils;
import com.splicemachine.derby.impl.stats.StoreCostControllerImpl;
import com.splicemachine.derby.utils.ConglomerateUtils;
import com.splicemachine.pipeline.Exceptions;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.si.impl.txn.ReadOnlyTxn;
import com.splicemachine.utils.SpliceLogUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.splicemachine.db.shared.common.reference.SQLState.LANG_INTERNAL_ERROR;

public class SpliceTransactionManager implements XATransactionController,
        TransactionManager {
    private static Logger LOG = Logger
            .getLogger(SpliceTransactionManager.class);
    /**
     * The corresponding raw store transaction.
     **/
    protected Transaction rawtran;

    /**
     * The access manager this transaction is under.
     **/
    protected SpliceAccessManager accessmanager;

    /**
     * The context this transaction is being managed by.
     **/
    protected SpliceTransactionManagerContext context;

    /**
     * The parent transaction if this is a nested user transaction.
     **/
    protected SpliceTransactionManager parent_tran;

    /**
     * The context manager if this is a nested transaction.
     */
    private ContextManager contextManager;

    // XXX (nat) management of the controllers is still embryonic.
    // XXX (nat) would be nice if sort controllers were like conglom controllers
    private List<ScanController> scanControllers;
    private List<ConglomerateController> conglomerateControllers;

    /**
     * Where to look for temporary conglomerates.
     **/
    protected HashMap<Long, Conglomerate> tempCongloms;

    /**
     * Set by alter table to indicate that the conglomerate cache needs to be
     * invalidated if a transaction aborting error is encountered, cleared after
     * cleanup.
     */
    private boolean alterTableCallMade = false;

    /**
     * The ID of the current ddl change coordination.
     */
    private List<String> currentDDLChanges;

    /**************************************************************************
     * Constructors for This class:
     **************************************************************************
     */

    private void init(SpliceAccessManager myaccessmanager,
                      Transaction theRawTran, SpliceTransactionManager parent_tran) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("SpliceAccessManager " + myaccessmanager);
            LOG.trace("Transaction " + theRawTran);
        }

        this.rawtran = theRawTran;
        this.parent_tran = parent_tran;
        accessmanager = myaccessmanager;
        scanControllers =new CopyOnWriteArrayList<>();
        conglomerateControllers =new CopyOnWriteArrayList<>();
        if (parent_tran != null) {
            // allow nested transactions to see temporary conglomerates which
            // were created in the parent transaction. This is necessary for
            // language which compiling plans in nested transactions against
            // user temporaries created in parent transactions.

            tempCongloms = parent_tran.tempCongloms;
        } else {
            tempCongloms = null; // allocated on demand
        }
    }

    protected SpliceTransactionManager(SpliceAccessManager myaccessmanager,
                                       Transaction theRawTran, SpliceTransactionManager parent_transaction) {
        if (LOG.isTraceEnabled())
            LOG.trace("init");
        init(myaccessmanager, theRawTran, parent_transaction);
    }

    protected SpliceTransactionManager(SpliceAccessManager myaccessmanager, Transaction theRawTran,
                                       SpliceTransactionManager parent_transaction, ContextManager cm) {
        contextManager = cm;
        init(myaccessmanager, theRawTran, parent_transaction);
    }
    /**************************************************************************
     * Private/Protected methods of This class:
     **************************************************************************
     */

    // XXX (nat) currently closes all controllers.
    protected void closeControllers(boolean closeHeldControllers)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("closeControllers");

        if (!scanControllers.isEmpty()) {
            // loop from end to beginning, removing scans which are not held.
            for (int i = scanControllers.size() - 1; i >= 0; i--) {
                ScanManager sc = (ScanManager) scanControllers.get(i);
                if(sc!=null)
                    sc.closeForEndTransaction(closeHeldControllers);
            }

            if (closeHeldControllers) {
                // just to make sure everything has been closed and removed.
                scanControllers.clear();
            }
        }

        if (!conglomerateControllers.isEmpty()) {
            // loop from end to beginning, removing scans which are not held.

            if (closeHeldControllers) {
                if (SanityManager.DEBUG) {
                    SanityManager.ASSERT(scanControllers.isEmpty());
                }
                // just to make sure everything has been closed and removed.
                conglomerateControllers.clear();
            }
        }

    }

    private int determine_lock_level(int requested_lock_level) {
        if (LOG.isTraceEnabled())
            LOG.trace("determine_lock_level");
        int ret_lock_level;

        if ((accessmanager.getSystemLockLevel() == TransactionController.MODE_TABLE)
                || (requested_lock_level == TransactionController.MODE_TABLE)) {
            ret_lock_level = TransactionController.MODE_TABLE;
        } else {
            ret_lock_level = TransactionController.MODE_RECORD;

        }
        return (ret_lock_level);
    }

    private Conglomerate findExistingConglomerate(long conglomId) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("findExistingConglomerate " + conglomId);
        Conglomerate conglom = null;

        if (conglomId < 0) {
            if (tempCongloms != null)
                conglom = tempCongloms.get(conglomId);
        } else {
            conglom = accessmanager.conglomCacheFind(this, conglomId);
        }

        if (conglom == null) {
            throw StandardException.newException(
                    SQLState.STORE_CONGLOMERATE_DOES_NOT_EXIST, conglomId);
        } else {
            return (conglom);
        }
    }

    public Conglomerate findConglomerate(long conglomId)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("findConglomerate " + conglomId);
        Conglomerate conglom = null;

        if (conglomId >= 0) {
            conglom = accessmanager.conglomCacheFind(this, conglomId);
        } else {
            if (tempCongloms != null)
                conglom = tempCongloms.get(conglomId);
        }

        return (conglom);
    }

    void setContext(SpliceTransactionManagerContext rtc) {
        if (LOG.isTraceEnabled())
            LOG.trace("setContext " + rtc);
        context = rtc;
    }

    private ConglomerateController openConglomerate(Conglomerate conglom,
                                                    boolean hold, int open_mode, int lock_level, int isolation_level,
                                                    StaticCompiledOpenConglomInfo static_info,
                                                    DynamicCompiledOpenConglomInfo dynamic_info)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openConglomerate " + conglom);
            SanityManager.ASSERT(conglom != null);

        if (lock_level != MODE_RECORD && lock_level != MODE_TABLE) {
            SanityManager.THROWASSERT("Bad lock level to openConglomerate:"
                    + lock_level);
        }

        // Get a conglomerate controller.
        @SuppressWarnings("ConstantConditions") ConglomerateController cc = conglom.open(this, rawtran, hold,
                open_mode, determine_lock_level(lock_level),
                static_info, dynamic_info);

        // Keep track of it so we can release on close.
        conglomerateControllers.add(cc);

        return cc;
    }

    private ScanController openScan(Conglomerate conglom, boolean hold,
                                    int open_mode, int lock_level, int isolation_level,
                                    FormatableBitSet scanColumnList,
                                    DataValueDescriptor[] startKeyValue, int startSearchOperator,
                                    Qualifier qualifier[][], DataValueDescriptor[] stopKeyValue,
                                    int stopSearchOperator, StaticCompiledOpenConglomInfo static_info,
                                    DynamicCompiledOpenConglomInfo dynamic_info)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openScan " + conglom);

        if (SanityManager.DEBUG) {
            if ((open_mode & ~(TransactionController.OPENMODE_FORUPDATE
                    | TransactionController.OPENMODE_USE_UPDATE_LOCKS
                    | TransactionController.OPENMODE_FOR_LOCK_ONLY
                    | TransactionController.OPENMODE_LOCK_NOWAIT | TransactionController.OPENMODE_SECONDARY_LOCKED)) != 0) {
                SanityManager.THROWASSERT("Bad open mode to openScan:"
                        + Integer.toHexString(open_mode));
            }

            if (!((lock_level == MODE_RECORD | lock_level == MODE_TABLE))) {
                SanityManager.THROWASSERT("Bad lock level to openScan:"
                        + lock_level);
            }
        }

        // Get a scan controller.
        ScanManager sm = conglom.openScan(this, rawtran, hold, open_mode,
                determine_lock_level(lock_level),
                isolation_level, scanColumnList, startKeyValue,
                startSearchOperator, qualifier, stopKeyValue,
                stopSearchOperator, static_info, dynamic_info);

        // Keep track of it so we can release on close.
        scanControllers.add(sm);

        return (sm);
    }

    /**
     * Invalidate the conglomerate cache, if necessary. If an alter table call
     * has been made then invalidate the cache.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    protected void invalidateConglomerateCache() throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("invalidateConglomerateCache ");
        /* JL TODO
        if (alterTableCallMade) {
            accessmanager.conglomCacheInvalidate();
            alterTableCallMade = false;
        }
        */
    }

    /**************************************************************************
     * Public Methods of TransactionController interface:
     **************************************************************************
     */

    /**
     * Add a column to a conglomerate. The conglomerate must not be open in the
     * current transaction. This also means that there must not be any active
     * scans on it.
     *
     * The column can only be added at the spot just after the current set of
     * columns.
     *
     * The template_column must be nullable.
     *
     * After this call has been made, all fetches of this column from rows that
     * existed in the table prior to this call will return "null".
     *
     * @param conglomId
     *            The identifier of the conglomerate to drop.
     * @param column_id
     *            The column number to add this column at.
     * @param template_column
     *            An instance of the column to be added to table.
     * @param collation_id
     *            collation id of the added column.
     * @exception StandardException
     *                Only some types of conglomerates can support adding a
     *                column, for instance "heap" conglomerates support adding a
     *                column while "btree" conglomerates do not. If the column
     *                can not be added an exception will be thrown.
     **/
    public void addColumnToConglomerate(long conglomId, int column_id,
                                        Storable template_column, int collation_id)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("addColumnToConglomerate conglomID " + conglomId
                    + ", column_id" + column_id + ", template_column "
                    + template_column);
        boolean is_temporary = (conglomId < 0);

        Conglomerate conglom = findConglomerate(conglomId);
        if (conglom == null) {
            throw StandardException.newException(
                    SQLState.AM_NO_SUCH_CONGLOMERATE_DROP, conglomId);
        }

        // Get exclusive lock on the table being altered.
        ConglomerateController cc = conglom
                .open(this,
                        rawtran,
                        false,
                        OPENMODE_FORUPDATE,
                        MODE_TABLE,
                        null,
                        null);

        conglom.addColumn(this, column_id, template_column, collation_id);

        // remove the old entry in the Conglomerate directory, and add the
        // new one.
        if (is_temporary) {
            // remove old entry in the Conglomerate directory, and add new one
            if (tempCongloms != null){
                tempCongloms.remove(conglomId);
                tempCongloms.put(conglomId, conglom);
            }
        } else {
            alterTableCallMade = true;

            // have access manager update the conglom to this new one.
            accessmanager.conglomCacheUpdateEntry(conglomId, conglom);
        }

        cc.close();
    }

    /**
     * Return static information about the conglomerate to be included in a a
     * compiled plan.
     * <p>
     * The static info would be valid until any ddl was executed on the
     * conglomid, and would be up to the caller to throw away when that
     * happened. This ties in with what language already does for other
     * invalidation of static info. The type of info in this would be
     * containerid and array of format id's from which templates can be created.
     * The info in this object is read only and can be shared among as many
     * threads as necessary.
     * <p>
     *
     * @return The static compiled information.
     *
     * @param conglomId
     *            The identifier of the conglomerate to open.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public StaticCompiledOpenConglomInfo getStaticCompiledConglomInfo(
            long conglomId) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("getStaticCompiledConglomInfo conglomID " + conglomId);
        return (findExistingConglomerate(conglomId)
                .getStaticCompiledConglomInfo(this, conglomId));
    }

    /**
     * Return dynamic information about the conglomerate to be dynamically
     * reused in repeated execution of a statement.
     * <p>
     * The dynamic info is a set of variables to be used in a given
     * ScanController or ConglomerateController. It can only be used in one
     * controller at a time. It is up to the caller to insure the correct thread
     * access to this info. The type of info in this is a scratch template for
     * btree traversal, other scratch variables for qualifier evaluation, ...
     * <p>
     *
     * @return The dynamic information.
     *
     * @param conglomId
     *            The identifier of the conglomerate to open.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public DynamicCompiledOpenConglomInfo getDynamicCompiledConglomInfo(
            long conglomId) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("getDynamicCompiledConglomInfo conglomID " + conglomId);
        return (findExistingConglomerate(conglomId)
                .getDynamicCompiledConglomInfo());
    }

    /**
     * Report on the number of open conglomerates in the transaction.
     * <p>
     * There are 4 types of open "conglomerates" that can be tracked, those
     * opened by each of the following: openConglomerate(), openScan(),
     * openSort(), and openSortScan(). This routine can be used to either report
     * on the number of all opens, or may be used to track one particular type
     * of open.
     *
     * This routine is expected to be used for debugging only. An implementation
     * may only track this info under SanityManager.DEBUG mode. If the
     * implementation does not track the info it will return -1 (so code using
     * this call to verify that no congloms are open should check for return <=
     * 0 rather than == 0).
     *
     * The return value depends on the "which_to_count" parameter as follows:
     * OPEN_CONGLOMERATE - return # of openConglomerate() calls not close()'d.
     * OPEN_SCAN - return # of openScan() calls not close()'d.
     * OPEN_CREATED_SORTS - return # of sorts created (createSort()) in current
     * xact. There is currently no way to get rid of these sorts before end of
     * transaction. OPEN_SORT - return # of openSort() calls not close()'d.
     * OPEN_TOTAL - return total # of all above calls not close()'d. - note an
     * implementation may return -1 if it does not track the above information.
     *
     * @return The nunber of open's of a type indicated by "which_to_count"
     *         parameter.
     *
     * @param which_to_count
     *            Which kind of open to report on.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    @SuppressFBWarnings(value = "SF_SWITCH_NO_DEFAULT",justification = "Intentional")
    public int countOpens(int which_to_count) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("countOpens " + which_to_count);
        int ret_val = -1;

        switch (which_to_count) {
            case OPEN_CONGLOMERATE:
                ret_val = conglomerateControllers.size();
                break;
            case OPEN_SCAN:
                ret_val = scanControllers.size();
                break;
            case OPEN_TOTAL:
                ret_val = conglomerateControllers.size() + scanControllers.size();
                break;
        }

        return (ret_val);
    }

    @Override
    public void markConglomerateDropped(long conglomerateId) throws StandardException {
        ConglomerateUtils.markConglomerateDropped(conglomerateId,
                ((SpliceTransaction)rawtran).getTxn());
    }

    /**
     * Create a new conglomerate.
     * <p>
     *
     * @see TransactionController#createConglomerate
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    @Override
    public long createConglomerate(boolean isExternal, String implementation,
                                   DataValueDescriptor[] template, ColumnOrdering[] columnOrder, int[] keyFormatIds,
                                   int[] collationIds, Properties properties, int temporaryFlag, Conglomerate.Priority priority)
                throws StandardException {
        Conglomerate conglomerate = createConglomerateInternal(isExternal, implementation, template, columnOrder, keyFormatIds, collationIds, properties,
                temporaryFlag, null, priority);
        conglomerate.awaitCreation();
        return conglomerate.getContainerid();
    }

    @Override
    public Conglomerate createConglomerateAsync(boolean isExternal, String implementation,
                                                DataValueDescriptor[] template, ColumnOrdering[] columnOrder, int[] keyFormatIds,
                                                int[] collationIds, Properties properties, int temporaryFlag, byte[][] splitKeys,
                                                Conglomerate.Priority priority)
            throws StandardException {
        return createConglomerateInternal(isExternal, implementation, template, columnOrder, keyFormatIds, collationIds, properties,
                temporaryFlag, splitKeys, priority);
    }

    private Conglomerate createConglomerateInternal(boolean isExternal, String implementation,
                                                    DataValueDescriptor[] template, ColumnOrdering[] columnOrder,
                                                    int[] keyFormatIds, int[] collationIds, Properties properties,
                                                    int temporaryFlag, byte[][] splitKeys, Conglomerate.Priority priority)
            throws StandardException {
        // Find the appropriate factory for the desired implementation.
        MethodFactory mfactory;
        mfactory = accessmanager.findMethodFactoryByImpl(implementation);
        if (mfactory == null || !(mfactory instanceof ConglomerateFactory)) {
            throw StandardException.newException(
                    SQLState.AM_NO_SUCH_CONGLOMERATE_TYPE, implementation);
        }
        ConglomerateFactory cfactory = (ConglomerateFactory) mfactory;

        // Create the conglomerate
        // RESOLVE (mikem) - eventually segmentid's will be passed into here
        // in the properties. For now just use 0.]
        long conglomid = accessmanager.getNextConglomId(cfactory
                .getConglomerateFactoryId());;

        // call the factory to actually create the conglomerate.
        Conglomerate conglom = cfactory.createConglomerate(isExternal,this,
                conglomid, template, columnOrder, keyFormatIds, collationIds, properties,
                temporaryFlag, splitKeys, priority);
        long conglomId = conglom.getContainerid();
        if ((temporaryFlag & TransactionController.IS_TEMPORARY) == TransactionController.IS_TEMPORARY) {
            if (tempCongloms == null)
                tempCongloms =new HashMap<>();
            tempCongloms.put(conglomId, conglom);
        } else {
            accessmanager.conglomCacheAddEntry(conglomId, conglom);
        }
        return conglom;
    }
    /**
     * Create a conglomerate and populate it with rows from rowSource.
     *
     * @see TransactionController#createAndLoadConglomerate
     * @exception StandardException
     *                Standard Derby Error Policy
     */
    @Override
    public long createAndLoadConglomerate(boolean isExternal, String implementation,
                                          DataValueDescriptor[] template, ColumnOrdering[] columnOrder,
                                          int[] keyFormatIds, int[] collationIds, Properties properties,
                                          int temporaryFlag, RowLocationRetRowSource rowSource, long[] rowCount)
            throws StandardException {

        return (recreateAndLoadConglomerate(isExternal,implementation, true, template,
                columnOrder, keyFormatIds, collationIds, properties, temporaryFlag, 0 /*
																		 * unused
																		 * if
																		 * recreate_ifempty
																		 * is
																		 * true
																		 */,
                rowSource, rowCount));
    }

    /**
     * recreate a conglomerate and populate it with rows from rowSource.
     *
     * @see TransactionController#createAndLoadConglomerate
     * @exception StandardException
     *                Standard Derby Error Policy
     */
    public long recreateAndLoadConglomerate(boolean isExternal, String implementation,
                                            boolean recreate_ifempty, DataValueDescriptor[] template,
                                            ColumnOrdering[] columnOrder, int[] keyFormatIds, int[] collationIds,
                                            Properties properties, int temporaryFlag, long orig_conglomId,
                                            RowLocationRetRowSource rowSource, long[] rowCount)
            throws StandardException
    {

        // RESOLVE: this create the conglom LOGGED, this is slower than
        // necessary although still correct.
        long conglomId = createConglomerate(isExternal,implementation, template,
                columnOrder, keyFormatIds, collationIds, properties, temporaryFlag, Conglomerate.Priority.NORMAL);

        long rows_loaded = loadConglomerate(conglomId, true, // conglom is being
                // created
                rowSource);

        if (rowCount != null)
            rowCount[0] = rows_loaded;

        if (!recreate_ifempty && (rows_loaded == 0)) {
            dropConglomerate(conglomId);

            conglomId = orig_conglomId;
        }

        return conglomId;
    }

    /**
     * Return a string with debug information about opened congloms/scans/sorts.
     * <p>
     * Return a string with debugging information about current opened
     * congloms/scans/sorts which have not been close()'d. Calls to this routine
     * are only valid under code which is conditional on SanityManager.DEBUG.
     * <p>
     *
     * @return String with debugging information.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public String debugOpened() throws StandardException {


        if (SanityManager.DEBUG) {
            StringBuilder sb = new StringBuilder();
            for (ScanController sc : scanControllers) {
                sb = sb.append("open scan controller: ").append(sc).append("\n");
            }

            for (ConglomerateController cc : conglomerateControllers) {
                sb = sb.append("open conglomerate controller: ").append(cc).append("\n");
            }

            if (tempCongloms != null) {
                for(Map.Entry<Long,Conglomerate> entry:tempCongloms.entrySet()){
                    long conglomId = entry.getKey();
                    Conglomerate c = entry.getValue();
                    sb = sb.append("temp conglomerate id = ").append(conglomId).append(":").append(c);
                }
            }
            return sb.toString();

        }else return "";
    }

    public boolean conglomerateExists(long conglomId) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("conglomerateExists " + conglomId);

        Conglomerate conglom = findConglomerate(conglomId);
        return conglom != null;
    }

    public void dropConglomerate(long conglomId) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("dropConglomerate " + conglomId);

        Conglomerate conglom = findExistingConglomerate(conglomId);

        if (conglom.isTemporary()) {
            conglom.drop(this);
            if (tempCongloms != null)
                tempCongloms.remove(conglomId);
        } else {
            accessmanager.conglomCacheRemoveEntry(conglomId);
        }
    }

    /**
     * A superset of properties that "users" can specify.
     * <p>
     * A superset of properties that "users" (ie. from sql) can specify. Store
     * may implement other properties which should not be specified by users.
     * Layers above access may implement properties which are not known at all
     * to Access.
     * <p>
     * This list is a superset, as some properties may not be implemented by
     * certain types of conglomerates. For instant an in-memory store may not
     * implement a pageSize property. Or some conglomerates may not support
     * pre-allocation.
     * <p>
     * This interface is meant to be used by the SQL parser to do validation of
     * properties passsed to the create table statement, and also by the various
     * user interfaces which present table information back to the user.
     * <p>
     * Currently this routine returns the following list:
     * derby.storage.initialPages derby.storage.minimumRecordSize
     * derby.storage.pageReservedSpace derby.storage.pageSize
     *
     * @return The superset of properties that "users" can specify.
     *
     **/
    public Properties getUserCreateConglomPropList() {
        if (LOG.isTraceEnabled())
            LOG.trace("getUserCreateConglomPropList ");

        return ConglomerateUtil.createUserRawStorePropertySet(null);
    }

    /**
     * Reveals whether the transaction has ever read or written data.
     *
     * @return true If the transaction has never read or written data.
     *
     **/
    public boolean isIdle() {
        if (LOG.isTraceEnabled())
            LOG.trace("isIdle ");
        return rawtran.isIdle();
    }

    /**
     * Reveals whether the transaction is a global or local transaction.
     *
     * @return true If the transaction was either started by
     *         AccessFactory.startXATransaction() or was morphed to a global
     *         transaction by calling
     *         AccessFactory.createXATransactionFromLocalTransaction().
     *
     * @see AccessFactory#startXATransaction
     * @see TransactionController#createXATransactionFromLocalTransaction
     *
     **/
    public boolean isGlobal() {
        if (LOG.isTraceEnabled())
            LOG.trace("isGlobal ");
        return (rawtran.getGlobalId() != null);
    }

    /**
     * Reveals whether the transaction is currently pristine.
     *
     * @return true If the transaction is Pristine.
     *
     * @see TransactionController#isPristine
     **/
    public boolean isPristine() {
        if (LOG.isTraceEnabled())
            LOG.trace("isPristine ");
        return rawtran.isPristine();
    }

    /**
     * Convert a local transaction to a global transaction.
     * <p>
     * Get a transaction controller with which to manipulate data within the
     * access manager. Tbis controller allows one to manipulate a global XA
     * conforming transaction.
     * <p>
     * Must only be called a previous local transaction was created and exists
     * in the context. Can only be called if the current transaction is in the
     * idle state. Upon return from this call the old tc will be unusable, and
     * all references to it should be dropped (it will have been implicitly
     * destroy()'d by this call.
     * <p>
     * The (format_id, global_id, branch_id) triplet is meant to come exactly
     * from a javax.transaction.xa.Xid. We don't use Xid so that the system can
     * be delivered on a non-1.2 vm system and not require the javax classes in
     * the path.
     *
     * @param format_id
     *            the format id part of the Xid - ie. Xid.getFormatId().
     * @param global_id
     *            the global transaction identifier part of XID - ie.
     *            Xid.getGlobalTransactionId().
     * @param branch_id
     *            The branch qualifier of the Xid - ie. Xid.getBranchQaulifier()
     *
     * @exception StandardException
     *                Standard exception policy.
     * @see TransactionController
     **/
    public/* XATransactionController */Object createXATransactionFromLocalTransaction(
            int format_id, byte[] global_id, byte[] branch_id)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("createXATransactionFromLocalTransaction ");
        getRawStoreXact().createXATransactionFromLocalTransaction(format_id,
                global_id, branch_id);

        return this;
    }

    /**
     * Bulk load into the conglomerate. Rows being loaded into the conglomerate
     * are not logged.
     *
     * @param conglomId
     *            The conglomerate Id.
     * @param createConglom
     *            If true, the conglomerate is being created in the same
     *            operation as the loadConglomerate. The enables further
     *            optimization as recovery does not require page allocation to
     *            be logged.
     * @param rowSource
     *            Where the rows come from.
     * @return true The number of rows loaded.
     * @exception StandardException
     *                Standard Derby Error Policy
     */
    public long loadConglomerate(long conglomId, boolean createConglom,
                                 RowLocationRetRowSource rowSource) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("loadConglomerate conglomId " + conglomId
                    + ", rowSource " + rowSource);
        // Find the conglomerate.
        Conglomerate conglom = findExistingConglomerate(conglomId);

        // Load up the conglomerate with rows from the rowSource.
        // Don't need to keep track of the conglomerate controller because load
        // automatically closes it when it finished.
        return (conglom.load(this, createConglom, rowSource));
    }

    public ConglomerateController openCompiledConglomerate(boolean hold,
                                                           int open_mode, int lock_level, int isolation_level,
                                                           StaticCompiledOpenConglomInfo static_info,
                                                           DynamicCompiledOpenConglomInfo dynamic_info)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openCompiledConglomerate static_info " + static_info
                    + ", dynamic_info  " + dynamic_info);
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(static_info != null);
            SanityManager.ASSERT(dynamic_info != null);
        }

        // in the current implementation, only Conglomerate's are passed around
        // as StaticCompiledOpenConglomInfo.

        //noinspection ConstantConditions
        return openConglomerate((Conglomerate) static_info.getConglom(), hold,
                open_mode, lock_level, isolation_level, static_info,
                dynamic_info);
    }

    public ConglomerateController openConglomerate(long conglomId,
                                                   boolean hold, int open_mode, int lock_level, int isolation_level)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openConglomerate conglomId " + conglomId);

        return (openConglomerate(findExistingConglomerate(conglomId), hold,
                open_mode, lock_level, isolation_level, null, null));
    }

    public long findConglomid(long container_id) throws StandardException {
        return (container_id);
    }

    public long findContainerid(long conglom_id) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("findContainerid conglomId " + conglom_id);

        return (conglom_id);
    }

    public GroupFetchScanController openGroupFetchScan(long conglomId,
                                                       boolean hold, int open_mode, int lock_level, int isolation_level,
                                                       FormatableBitSet scanColumnList,
                                                       DataValueDescriptor[] startKeyValue, int startSearchOperator,
                                                       Qualifier qualifier[][], DataValueDescriptor[] stopKeyValue,
                                                       int stopSearchOperator) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openGroupFetchScan conglomId " + conglomId);

        if (SanityManager.DEBUG) {
            if ((open_mode & ~(TransactionController.OPENMODE_FORUPDATE
                    | TransactionController.OPENMODE_FOR_LOCK_ONLY | TransactionController.OPENMODE_SECONDARY_LOCKED)) != 0)
                SanityManager.THROWASSERT("Bad open mode to openScan:"
                        + Integer.toHexString(open_mode));

            if (!(lock_level == MODE_RECORD | lock_level == MODE_TABLE))
                SanityManager.THROWASSERT("Bad lock level to openScan:"
                        + lock_level);
        }

        // Find the conglomerate.
        Conglomerate conglom = findExistingConglomerate(conglomId);

        // Get a scan controller.
        ScanManager sm = conglom.openScan(this, rawtran, hold, open_mode,
                determine_lock_level(lock_level),
                isolation_level, scanColumnList, startKeyValue,
                startSearchOperator, qualifier, stopKeyValue,
                stopSearchOperator, null,
                null);

        // Keep track of it so we can release on close.
        scanControllers.add(sm);

        return (sm);
    }

    /**
     * Purge all committed deleted rows from the conglomerate.
     * <p>
     * This call will purge committed deleted rows from the conglomerate, that
     * space will be available for future inserts into the conglomerate.
     * <p>
     *
     * @param conglomId
     *            Id of the conglomerate to purge.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public void purgeConglomerate(long conglomId) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("purgeConglomerate conglomId " + conglomId);
        findExistingConglomerate(conglomId).purgeConglomerate(this, rawtran);
    }

    /**
     * Return free space from the conglomerate back to the OS.
     * <p>
     * Returns free space from the conglomerate back to the OS. Currently only
     * the sequential free pages at the "end" of the conglomerate can be
     * returned to the OS.
     * <p>
     *
     * @param conglomId
     *            Id of the conglomerate to purge.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public void compressConglomerate(long conglomId) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("compressConglomerate conglomId " + conglomId);
        findExistingConglomerate(conglomId).compressConglomerate(this, rawtran);
    }

    public ScanController openScan(long conglomId, boolean hold, int open_mode,
                                   int lock_level, int isolation_level,
                                   FormatableBitSet scanColumnList,
                                   DataValueDescriptor[] startKeyValue, int startSearchOperator,
                                   Qualifier qualifier[][], DataValueDescriptor[] stopKeyValue,
                                   int stopSearchOperator) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openScan conglomId " + conglomId);
        return (openScan(findExistingConglomerate(conglomId), hold, open_mode,
                lock_level, isolation_level, scanColumnList, startKeyValue,
                startSearchOperator, qualifier, stopKeyValue,
                stopSearchOperator, null,
                null));
    }

    public ScanController openCompiledScan(boolean hold, int open_mode,
                                           int lock_level, int isolation_level,
                                           FormatableBitSet scanColumnList,
                                           DataValueDescriptor[] startKeyValue, int startSearchOperator,
                                           Qualifier qualifier[][], DataValueDescriptor[] stopKeyValue,
                                           int stopSearchOperator, StaticCompiledOpenConglomInfo static_info,
                                           DynamicCompiledOpenConglomInfo dynamic_info)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openCompiledScan static_info " + static_info);
        // in the current implementation, only Conglomerate's are passed around
        // as StaticCompiledOpenConglomInfo.

        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(static_info != null);
            SanityManager.ASSERT(dynamic_info != null);
            SanityManager.ASSERT(static_info != null);
            SanityManager.ASSERT(dynamic_info != null);
        }

        //noinspection ConstantConditions
        return openScan(((Conglomerate) static_info.getConglom()), hold,
                open_mode, lock_level, isolation_level, scanColumnList,
                startKeyValue, startSearchOperator, qualifier, stopKeyValue,
                stopSearchOperator, static_info, dynamic_info);
    }

    /**
     * Return an open StoreCostController for the given conglomid.
     * <p>
     * Return an open StoreCostController which can be used to ask about the
     * estimated row counts and costs of ScanController and
     * ConglomerateController operations, on the given conglomerate.
     * <p>
     *
     * @return The open StoreCostController.
     *
     * @param cd  The descriptor of the conglomerate to open.
     *
     * @param useDb2CompatibleVarchars
     * @exception StandardException Standard exception policy.
     *
     * @see StoreCostController
     **/
    @Override
    public StoreCostController openStoreCost(TableDescriptor td, ConglomerateDescriptor cd, boolean skipDictionaryStats, long defaultRowCount, int requestedSplits, boolean useDb2CompatibleVarchars) throws StandardException {
        List<PartitionStatisticsDescriptor> tablePartitionStatistics = new ArrayList<>();
        List<PartitionStatisticsDescriptor> exprIndexPartitionStatistics = new ArrayList<>();
        if (!skipDictionaryStats) {
            DataDictionary dd = cd.getDataDictionary();
            tablePartitionStatistics = dd.getPartitionStatistics(td.getBaseConglomerateDescriptor().getConglomerateNumber(), this);

            IndexRowGenerator irg = cd.getIndexDescriptor();
            if (irg != null && irg.isOnExpression()) {
                exprIndexPartitionStatistics = dd.getPartitionStatistics(cd.getConglomerateNumber(), this);
            }
        }
        return new StoreCostControllerImpl(td, cd, tablePartitionStatistics, exprIndexPartitionStatistics, skipDictionaryStats?defaultRowCount:0, requestedSplits, useDb2CompatibleVarchars);
    }
     /**
     * @see TransactionController#getProperty
     * @exception StandardException
     *                Standard exception policy.
     **/
    @Override
    public Serializable getProperty(String key) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("getProperty key " + key);
        return (accessmanager.getTransactionalProperties().getProperty(this,
                key));
    }

    /**
     * @see TransactionController#getPropertyDefault
     * @exception StandardException
     *                Standard exception policy.
     **/
    public Serializable getPropertyDefault(String key) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("getPropertyDefault key " + key);
        return (accessmanager.getTransactionalProperties().getPropertyDefault(
                this, key));
    }

    /**
     * @see TransactionController#setProperty
     * @exception StandardException
     *                Standard exception policy.
     **/
    public void setProperty(String key, Serializable value,
                            boolean dbOnlyProperty) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("setProperty key " + key);
        accessmanager.getTransactionalProperties().setProperty(this, key,
                value, dbOnlyProperty);
    }

    /**
     * @see TransactionController#setProperty
     * @exception StandardException
     *                Standard exception policy.
     **/
    public void setPropertyDefault(String key, Serializable value)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("setPropertyDefault key " + key);
        accessmanager.getTransactionalProperties().setPropertyDefault(this,
                key, value);
    }

    /**
     * @see TransactionController#propertyDefaultIsVisible
     * @exception StandardException
     *                Standard exception policy.
     **/
    public boolean propertyDefaultIsVisible(String key)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("propertyDefaultIsVisible key " + key);
        return accessmanager.getTransactionalProperties()
                .propertyDefaultIsVisible(this, key);
    }

    /**
     * @see TransactionController#getProperties
     * @exception StandardException
     *                Standard exception policy.
     **/
    public Properties getProperties() throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("getProperties");
        return accessmanager.getTransactionalProperties().getProperties(this);
    }

    /**
     * Return an open SortCostController.
     * <p>
     * Return an open SortCostController which can be used to ask about the
     * estimated costs of SortController() operations.
     * <p>
     *
     * @return The open StoreCostController.
     *
     * @exception StandardException
     *                Standard exception policy.
     *
     * @see StoreCostController
     **/
    public SortCostController openSortCostController(Properties implParameters) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("openSortCostController " + implParameters);

        // Get the implementation type from the parameters.
        // RESOLVE (mikem) need to figure out how to select sort implementation.
        String implementation = AccessFactoryGlobals.SORT_EXTERNAL;

        // Find the appropriate factory for the desired implementation.
        MethodFactory mfactory;
        mfactory = accessmanager.findMethodFactoryByImpl(implementation);
        if (mfactory == null || !(mfactory instanceof SortFactory)) {
            throw (StandardException.newException(SQLState.AM_NO_FACTORY_FOR_IMPLEMENTATION, implementation));
        }
        SortFactory sfactory = (SortFactory) mfactory;

        // open sort cost controller
        return (sfactory.openSortCostController());
    }

    public BaseSpliceTransaction getRawTransaction() {
        return (BaseSpliceTransaction)rawtran;
    }

    public void commit() throws StandardException {
        this.closeControllers(false /* don't close held controllers */);
        if(rawtran!=null){
            if(LOG.isDebugEnabled())
                LOG.debug("commit transaction contextId=="
                        + ((SpliceTransaction) rawtran).getContextId());
            if (LOG.isTraceEnabled())
                LOG.trace("commit transaction contextId=="
                        + ((SpliceTransaction) rawtran).getContextId());
    	    if (LOG.isDebugEnabled())
    	        SpliceLogUtils.debug(LOG, "Before commit: txn=%s, nestedTxnStack=\n%s", getRawTransaction(), getNestedTransactionStackString());
            rawtran.commit();
    	    if (LOG.isDebugEnabled())
    	        SpliceLogUtils.debug(LOG, "After commit: txn=%s, nestedTxnStack=\n%s", getRawTransaction(), getNestedTransactionStackString());
        }

        alterTableCallMade = false;
    }

    public void commitNoSync(int commitflag)
            throws StandardException {
        LOG.debug("commitNoSync ");
        if (LOG.isTraceEnabled())
            LOG.trace("commitNoSync ");
	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "Before commitNoSync: txn=%s, commitFlag=%s, nestedTxnStack=\n%s", getRawTransaction(), commitflag, getNestedTransactionStackString());
        this.closeControllers(false /* don't close held controllers */);
        rawtran.commitNoSync(commitflag);
	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "After commitNoSync: txn=%s, nestedTxnStack=\n%s", getRawTransaction(), getNestedTransactionStackString());
	    return;
    }

    public void abort() throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("abort ");
	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "Before abort: txn=%s, nestedTxnStack=\n%s", getRawTransaction(), getNestedTransactionStackString());

        /*
        if (alterTableCallMade) {
            accessmanager.conglomCacheInvalidate();
            alterTableCallMade = false;
        }
        */
        this.closeControllers(true /* close all controllers */);
        rawtran.abort();

	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "After abort: txn=%s, nestedTxnStack=\n%s", getRawTransaction(), getNestedTransactionStackString());
    }

    /**
     * Get the context manager that the transaction was created with.
     * <p>
     *
     * @return The context manager that the transaction was created with.
     * **/
    private ContextManager getContextManager() {
        if (LOG.isTraceEnabled())
            LOG.trace("getContextManager ");
        return (context.getContextManager());
    }

    public int setSavePoint(String name, Object kindOfSavepoint) throws StandardException {
        assert rawtran instanceof SpliceTransaction : "Programmer error: cannot set a save point on a Transaction View";
	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "Before setSavePoint: name=%s, parentTxn=%s, nestedTxnStack=\n%s", name, getRawTransaction(), getNestedTransactionStackString());
        int numSavePoints = rawtran.setSavePoint(name, kindOfSavepoint);
	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "After setSavePoint: name=%s, numSavePoints=%s, nestedTxnStack=\n%s", name, numSavePoints, getNestedTransactionStackString());
        return numSavePoints;
    }

    public int releaseSavePoint(String name, Object kindOfSavepoint) throws StandardException {
        assert rawtran instanceof SpliceTransaction : "Programmer error: cannot release a save point on a Transaction View";
	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "Before releaseSavePoint: name=%s, parentTxn=%s, nestedTxnStack=\n%s", name, getRawTransaction(), getNestedTransactionStackString());
	    int numSavePoints = rawtran.releaseSavePoint(name, kindOfSavepoint);
	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "After releaseSavePoint: name=%s, numSavePoints=%s, nestedTxnStack=\n%s", name, numSavePoints, getNestedTransactionStackString());
	    return numSavePoints;
    }

    public int rollbackToSavePoint(String name, boolean close_controllers, Object kindOfSavepoint) throws StandardException {
        assert rawtran instanceof SpliceTransaction : "Programmer error: cannot rollback a save point on a Transaction View";
	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "Before rollbackSavePoint: name=%s, parentTxn=%s, nestedTxnStack=\n%s", name, getRawTransaction(), getNestedTransactionStackString());
        if (close_controllers)
            this.closeControllers(true /* close all controllers */);
        int numSavePoints = rawtran.rollbackToSavePoint(name, kindOfSavepoint);
	    if (LOG.isDebugEnabled())
	        SpliceLogUtils.debug(LOG, "After rollbackSavePoint: name=%s, numSavePoints=%s, nestedTxnStack=\n%s", name, numSavePoints, getNestedTransactionStackString());
        return numSavePoints;
    }

    public void destroy() {
        if (LOG.isTraceEnabled())
            LOG.trace("destroy ");
        try {
            this.closeControllers(true /* close all controllers */);

            // If there's a transaction, abort it.
            if (rawtran != null) {
                rawtran.destroy();
                rawtran = null;
            }

            // If there's a context, pop it.
            if (context != null) {
                context.popMe();
                context = null;
            }

            // If there is a context manager, reset and remove it
            if (contextManager != null) {
                ContextService contextService = ContextService.getService();
                contextService.resetCurrentContextManager(contextManager);
                contextService.removeContextManager(contextManager);
                contextManager = null;
            }

            accessmanager = null;
            tempCongloms = null;
        } catch (StandardException e) {
            // XXX (nat) really need to figure out what to do
            // if there's an exception while aborting.
            rawtran = null;
            context = null;
            accessmanager = null;
            tempCongloms = null;
        }
    }

    @Override
    public void elevate(String tableName) throws StandardException {
        assert rawtran instanceof SpliceTransaction: "Programmer error: cannot elevate a transaction view!";
        assert tableName !=null : "Programmer error: cannot elevate a transaction without specifying a label";
	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "Before elevate: txn=%s, tableName=%s, nestedTxnStack=\n%s", getRawTransaction(), tableName, getNestedTransactionStackString());
        ((SpliceTransaction)rawtran).elevate(Bytes.toBytes(tableName));
	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "After elevate: txn=%s, nestedTxnStack=\n%s", getRawTransaction(), getNestedTransactionStackString());
    }

    public boolean anyoneBlocked() {
        if (LOG.isTraceEnabled())
            LOG.trace("anyoneBlocked ");
        return rawtran.anyoneBlocked();
    }

    @Override
    public boolean isElevated(){
        BaseSpliceTransaction rawTransaction=getRawTransaction();
        return rawTransaction.allowsWrites();
    }

    @Override
    public String getCatalogVersion(String conglomerate) throws StandardException {
        SIDriver driver=SIDriver.driver();
        PartitionFactory tableFactory=driver.getTableFactory();
        try (PartitionAdmin admin = tableFactory.getAdmin()) {
            return admin.getCatalogVersion(conglomerate);
        }
        catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    @Override
    public void setCatalogVersion(String conglomerate, String version) throws StandardException {
        SIDriver driver=SIDriver.driver();
        PartitionFactory tableFactory=driver.getTableFactory();
        try (PartitionAdmin admin = tableFactory.getAdmin()) {
            admin.setCatalogVersion(conglomerate, version);
        }
        catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    @Override
    public void truncate(String conglomerateNumber) throws StandardException {
        SIDriver driver=SIDriver.driver();
        PartitionFactory tableFactory=driver.getTableFactory();
        try (PartitionAdmin admin = tableFactory.getAdmin()) {
            admin.truncate(conglomerateNumber);
        }
        catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    @Override
    public void snapshot(String snapshotName, String tableName) throws StandardException {
        SIDriver driver=SIDriver.driver();
        PartitionFactory tableFactory=driver.getTableFactory();
        try (PartitionAdmin admin = tableFactory.getAdmin()) {
            admin.snapshot(snapshotName, tableName);
        }
        catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    @Override
    public Set<String> listSnapshots() throws StandardException {
        SIDriver driver=SIDriver.driver();
        PartitionFactory tableFactory=driver.getTableFactory();
        try (PartitionAdmin admin = tableFactory.getAdmin()) {
            return admin.listSnapshots();
        }
        catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    @Override
    public void cloneSnapshot(String snapshotName, String tableName) throws StandardException {
        SIDriver driver=SIDriver.driver();
        PartitionFactory tableFactory=driver.getTableFactory();
        try (PartitionAdmin admin = tableFactory.getAdmin()) {
            if (admin.tableExists(tableName)) {
                admin.deleteTable(tableName);
            }
            admin.cloneSnapshot(snapshotName, tableName);
        }
        catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    @Override
    public void deleteSnapshot(String snapshotName) throws StandardException {
        SIDriver driver=SIDriver.driver();
        PartitionFactory tableFactory=driver.getTableFactory();
        try (PartitionAdmin admin = tableFactory.getAdmin()) {
            admin.deleteSnapshot(snapshotName);
        }
        catch (IOException e) {
            throw StandardException.plainWrapException(e);
        }
    }
    /**************************************************************************
     * Public Methods implementing the XATransactionController interface.
     **************************************************************************
     */

    /**
     * This method is called to commit the current XA global transaction.
     * <p>
     * RESOLVE - how do we map to the "right" XAExceptions.
     * <p>
     *
     * @param onePhase
     *            If true, the resource manager should use a one-phase commit
     *            protocol to commit the work done on behalf of current xid.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public void xa_commit(boolean onePhase) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("xa_commit ");
        rawtran.xa_commit(onePhase);
    }

    /**
     * This method is called to ask the resource manager to prepare for a
     * transaction commit of the transaction specified in xid.
     * <p>
     *
     * @return A value indicating the resource manager's vote on the the outcome
     *         of the transaction. The possible values are: XA_RDONLY or XA_OK.
     *         If the resource manager wants to roll back the transaction, it
     *         should do so by throwing an appropriate XAException in the
     *         prepare method.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public int xa_prepare() throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("xa_prepare ");
        return (rawtran.xa_prepare());
    }

    /**
     * rollback the current global transaction.
     * <p>
     * The given transaction is roll'ed back and it's history is not maintained
     * in the transaction table or long term log.
     * <p>
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public void xa_rollback() throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("xa_rollback ");
        rawtran.xa_rollback();
    }

    /**************************************************************************
     * Public Methods of ParentTransactionManager interface:
     **************************************************************************
     */

    /**
     * Add to the list of post commit work.
     * <p>
     * Add to the list of post commit work that may be processed after this
     * transaction commits. If this transaction aborts, then the post commit
     * work list will be thrown away. No post commit work will be taken out on a
     * rollback to save point.
     * <p>
     * This routine simply delegates the work to the Rawstore transaction.
     *
     * @param work
     *            The post commit work to do.
     *
     **/
    public void addPostCommitWork(Serviceable work) {
        if (LOG.isTraceEnabled())
            LOG.trace("addPostCommitWork " + work);
        rawtran.addPostCommitWork(work);
    }

    /**
     * Check to see if a database has been upgraded to the required level in
     * order to use a store feature.
     *
     * @param requiredMajorVersion
     *            required database Engine major version
     * @param requiredMinorVersion
     *            required database Engine minor version
     * @param feature
     *            Non-null to throw an exception, null to return the state of
     *            the version match.
     *
     * @return <code> true </code> if the database has been upgraded to the
     *         required level, <code> false </code> otherwise.
     *
     * @exception StandardException
     *                if the database is not at the require version when
     *                <code>feature</code> feature is not <code> null </code>.
     */
    public boolean checkVersion(int requiredMajorVersion,
                                int requiredMinorVersion, String feature) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("checkVersion ");
        return (accessmanager.getRawStore().checkVersion(requiredMajorVersion,
                requiredMinorVersion, feature));
    }

    /**
     * The ConglomerateController.close() method has been called on
     * "conglom_control".
     * <p>
     * Take whatever cleanup action is appropriate to a closed
     * conglomerateController. It is likely this routine will remove references
     * to the ConglomerateController object that it was maintaining for cleanup
     * purposes.
     *
     **/
    public void closeMe(ConglomerateController conglom_control) {
        if (LOG.isTraceEnabled())
            LOG.trace("closeMe " + conglom_control);

        conglomerateControllers.remove(conglom_control);
    }

    /**
     * The ScanController.close() method has been called on "scan".
     * <p>
     * Take whatever cleanup action is appropriate to a closed scan. It is
     * likely this routine will remove references to the scan object that it was
     * maintaining for cleanup purposes.
     *
     **/
    public void closeMe(ScanController scan) {
        if (LOG.isTraceEnabled())
            LOG.trace("closeMe " + scan);
        scanControllers.remove(scan);
    }

    /**
     * Get reference to access factory which started this transaction.
     * <p>
     *
     * @return The AccessFactory which started this transaction.
     *
     **/
    public AccessFactory getAccessManager() {
        if (LOG.isTraceEnabled())
            LOG.trace("getAccessManager ");
        return (accessmanager);
    }

    /**
     * Get an Internal transaction.
     * <p>
     *     Derby has a distinction between a user-level transaction and an "Internal"
     *     transaction. An internal transaction is something that performs physical
     *     work (i.e. bulk loading data), but that doesn't necessarily write each
     *     row to their WAL.
     *
     *     This is useful for Derby, but for Splice it's meaningless--there is no
     *     distinction between user-level and system-level transactions for any purpose.
     *     As a result, this method is somewhat useless in practice. It's kept
     *     for compatibility with Derby's interface, but is essentially the same
     *     operation as getting a new user-level transaction
     * <p>
     * @return The new internal transaction.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    public TransactionManager getInternalTransaction() throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("getInternalTransaction ");
        // Get the context manager.
        ContextManager cm = getContextManager();

        // Allocate a new transaction no matter what.

        // Create a transaction, make a context for it, and push the context.
        // Note this puts the raw store transaction context
        // above the access context, which is required for
        // error handling assumptions to be correct.

        //TODO -sf- this behavior might not be 100% correct w.r.t error handling, although I think it is, but it probably won't be called anyway
        return (TransactionManager)accessmanager.getTransaction(cm);
    }

    @SuppressFBWarnings(value="DLS_DEAD_LOCAL_STORE",justification="Potential side effect results in dead store")
    public TransactionController startIndependentInternalTransaction(boolean readOnly) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("startIndependentInternalTransaction ");
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "Before startIndependentInternalTransaction: parentTxn=%s, readOnly=%b", getRawTransaction(), readOnly);
        // Get the context manager.
        ContextManager cm = getContextManager();
        Transaction global = accessmanager.getRawStore().startGlobalTransaction(cm,1,new byte[0],new byte[0]);
        if(!readOnly)
            ((SpliceTransaction)global).elevate(Bytes.toBytes("unknown")); //TODO -sf- replace this with an actual name

        SpliceTransactionManager rt = new SpliceTransactionManager(
                accessmanager, global, this);

        //this actually does some work, so don't remove it
        @SuppressWarnings("UnusedDeclaration") SpliceTransactionManagerContext rtc = new SpliceTransactionManagerContext(
                cm, AccessFactoryGlobals.RAMXACT_CHILD_CONTEXT_ID, rt, true /* abortAll */);

        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG, "After startNestedUserTransaction: childTxn=%s, nestedTxnStack=\n%s", global, rt.getNestedTransactionStackString());

        return (rt);
    }

    /**
     * Get an nested user transaction.
     * <p>
     * A nested user can be used exactly as any other TransactionController,
     * except as follows. For this discussion let the parent transaction be the
     * transaction used to make the getNestedUserTransaction(), and let the
     * child transaction be the transaction returned by the
     * getNestedUserTransaction() call.
     * <p>
     * The nesting is limited to one level deep. An exception will be thrown if
     * a subsequent getNestedUserTransaction() is called on the child
     * transaction.
     * <p>
     * The locks in the child transaction will be compatible with the locks of
     * the parent transaction.
     * <p>
     * A commit in the child transaction will release locks associated with the
     * child transaction only, work can continue in the parent transaction at
     * this point.
     * <p>
     * Any abort of the child transaction will result in an abort of both the
     * child transaction and parent transaction.
     * <p>
     * A TransactionController.destroy() call should be made on the child
     * transaction once all child work is done, and the caller wishes to
     * continue work in the parent transaction.
     * <p>
     * Nested internal transactions are meant to be used to implement system
     * work necessary to commit as part of implementing a user's request, but
     * where holding the lock for the duration of the user transaction is not
     * acceptable. 2 examples of this are system catalog read locks accumulated
     * while compiling a plan, and auto-increment.
     * <p>
     *
     * @param readOnly
     *            Is transaction readonly? Only 1 non-read only nested
     *            transaction is allowed per transaction.
     *
     * @param flush_log_on_xact_end
     *            By default should the transaction commit and abort be synced
     *            to the log. Normal usage should pick true, unless there is
     *            specific performance need and usage works correctly if a
     *            commit can be lost on system crash.
     *
     * @return The new nested user transaction.
     *
     * @exception StandardException
     *                Standard exception policy.
     **/
    @SuppressFBWarnings(value = "DLS_DEAD_LOCAL_STORE",justification = "Potential side effect")
    public TransactionController startNestedUserTransaction(boolean readOnly,
                                                            boolean flush_log_on_xact_end) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("startNestedUserTransaction ");
	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "Before startNestedUserTransaction: parentTxn=%s, readOnly=%b, nestedTxnStack=\n%s", getRawTransaction(), readOnly, getNestedTransactionStackString());
        // Get the context manager.
        ContextService contextService = ContextService.getService();
        ContextManager cm = contextService.newContextManager(getContextManager());
        contextService.setCurrentContextManager(cm);

        if(rawtran instanceof PastTransaction) {
            SpliceTransactionManager rt = new SpliceTransactionManager(accessmanager, ((PastTransaction)rawtran).getClone(), this);

            //this actually does some work, so don't remove it
            @SuppressWarnings("UnusedDeclaration") SpliceTransactionManagerContext rtc = new SpliceTransactionManagerContext(
                    cm, AccessFactoryGlobals.RAMXACT_CHILD_CONTEXT_ID, rt, true /* abortAll */);
            return rt;
        }

        // Allocate a new transaction no matter what.

        // Create a transaction, make a context for it, and push the context.
        // Note this puts the raw store transaction context
        // above the access context, which is required for
        // error handling assumptions to be correct.
        //
        // Note that the nested transaction inherits the compatibility space
        // from "this", thus the new transaction shares the compatibility space
        // of the current transaction.

        Transaction childTxn;
        if(rawtran instanceof SpliceTransaction)
            childTxn=getChildTransaction(readOnly, cm, (SpliceTransaction)rawtran, null, false, false);
        else
            childTxn = getChildTransactionFromView(readOnly, cm, (SpliceTransactionView)rawtran, null, false, false);

        if(!readOnly)
            ((SpliceTransaction)childTxn).elevate(Bytes.toBytes("unknown")); //TODO -sf- replace this with an actual name

        SpliceTransactionManager rt = new SpliceTransactionManager(accessmanager, childTxn, this, cm);

        //this actually does some work, so don't remove it
        @SuppressWarnings("UnusedDeclaration") SpliceTransactionManagerContext rtc = new SpliceTransactionManagerContext(
                cm, AccessFactoryGlobals.RAMXACT_CHILD_CONTEXT_ID, rt, true /* abortAll */);

	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "After startNestedUserTransaction: childTxn=%s, nestedTxnStack=\n%s", childTxn, rt.getNestedTransactionStackString());

        return (rt);
    }

    public TransactionController startNestedInternalTransaction(boolean readOnly,
                                                                byte[] destinationTable,
                                                                boolean inMemoryTxn) throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("startNestedInternalTransaction ");
	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "Before startNestedInternalTransaction: parentTxn=%s, readOnly=%b, nestedTxnStack=\n%s", getRawTransaction(), readOnly, getNestedTransactionStackString());
        // Get the context manager.
        ContextManager cm = ContextService.getCurrentContextManager();

        if(rawtran instanceof PastTransaction) {
            if (!readOnly)
                throw StandardException.newException(LANG_INTERNAL_ERROR,
                        "Attempting to elevate transaction in time travel query.");
            SpliceTransactionManager rt = new SpliceTransactionManager(accessmanager, ((PastTransaction)rawtran).getClone(), this);

            //this actually does some work, so don't remove it
            new SpliceTransactionManagerContext(
                    cm, AccessFactoryGlobals.RAMXACT_CHILD_CONTEXT_ID, rt, true /* abortAll */);
            return rt;
        }

        // Force the current transaction at the top of the txnStack to be the parent transaction.
        SpliceTransactionManager rt = new SpliceTransactionManager(accessmanager, rawtran, this, cm);

        Transaction childTxn;
        if(rawtran instanceof SpliceTransaction)
            childTxn=rt.getChildTransaction(readOnly, cm,
                                            (SpliceTransaction)rawtran, destinationTable, inMemoryTxn, true);
        else
            childTxn = rt.getChildTransactionFromView(readOnly, cm,
                                                      (SpliceTransactionView)rawtran, destinationTable, inMemoryTxn, true);

        // The new transaction manager should include the child transaction so that
        // a subsequent child transaction uses this child as its parent, therefore
        // we have to create a new one.  Make it "internal" so it will error out
        // if it tries to commit transactions or work with savepoints.
        rt = new SpliceInternalTransactionManager(accessmanager, childTxn, this, cm);
        SpliceTransactionManagerContext spliceTransactionManagerContext =
            (SpliceTransactionManagerContext)cm.getContext(AccessFactoryGlobals.RAMXACT_CONTEXT_ID);
        rt.setContext(spliceTransactionManagerContext);

	    if (LOG.isDebugEnabled())
	    	SpliceLogUtils.debug(LOG, "After startNestedInternalTransaction: childTxn=%s, nestedTxnStack=\n%s", childTxn, rt.getNestedTransactionStackString());

        return (rt);
    }

    private Transaction getChildTransaction(boolean readOnly,ContextManager cm,SpliceTransaction spliceTxn,
                                            byte[] destinationTable, boolean inMemoryTxn,
			                                boolean skipTransactionContextPush) throws StandardException{
        String txnName;
        Txn txn = spliceTxn.getActiveStateTxn();
        if(!readOnly){
            String tableName = destinationTable != null ? Bytes.toString(destinationTable) : "unknown";
            if(parent_tran!=null) {
                parent_tran.elevate(txn, tableName);
            }
            txnName = AccessFactoryGlobals.NESTED_UPDATE_USER_TRANS;
            elevate(tableName);
            txn = ((SpliceTransaction)rawtran).getTxn();
        }else
            txnName = AccessFactoryGlobals.NESTED_READONLY_USER_TRANS;

        return accessmanager.getRawStore().startNestedTransaction(getLockSpace(),cm,txnName,txn,destinationTable,inMemoryTxn,skipTransactionContextPush);
    }

    private Transaction getChildTransactionFromView(boolean readOnly,ContextManager cm,SpliceTransactionView spliceTxn,
                                                    byte[] destinationTable, boolean inMemoryTxn,
                                                    boolean skipTransactionContextPush) throws StandardException{
        String txnName;
        TxnView txn = spliceTxn.getActiveStateTxn();
        if(!readOnly){
            if(!txn.allowsWrites())
                throw StandardException.newException(SQLState.XACT_INTERNAL_TRANSACTION_EXCEPTION,"Unable to create a writable child of a read only view");
            txnName = AccessFactoryGlobals.NESTED_UPDATE_USER_TRANS;
            txn = ((BaseSpliceTransaction)rawtran).getTxnInformation();
        }else
            txnName = AccessFactoryGlobals.NESTED_READONLY_USER_TRANS;

        return accessmanager.getRawStore().startNestedTransaction(getLockSpace(),cm,txnName,txn,destinationTable,inMemoryTxn,skipTransactionContextPush);
    }

    private void elevate(Txn knownChild,String tableName) throws StandardException{
        /*
         * Elevate this transaction, then set the active state transaction as the parent on the known child
         */
        elevate(tableName);
        if(knownChild instanceof ReadOnlyTxn)
            ((ReadOnlyTxn)knownChild).parentWritable(getActiveStateTxn());
    }

    /**
     * Get the Transaction from the Transaction manager.
     * <p>
     * Access methods often need direct access to the "Transaction" - ie. the
     * raw store transaction, so give access to it.
     *
     * @return The raw store transaction.
     *
     **/
    public Transaction getRawStoreXact() {
        if (LOG.isTraceEnabled())
            LOG.trace("getRawStoreXact ");
        return (rawtran);
    }

    public FileResource getFileHandler() {
        return rawtran.getFileHandler();
    }

    /**
     * Return an object that when used as the compatibility space,
     * <strong>and</strong> the object returned when calling
     * <code>getOwner()</code> on that object is used as group for a lock
     * request, guarantees that the lock will be removed on a commit or an
     * abort.
     */
    public CompatibilitySpace getLockSpace() {
        if (LOG.isTraceEnabled())
            LOG.trace("getLockSpace ");
        if(rawtran!=null)
            return rawtran.getCompatibilitySpace();
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     *
     * For now, this only works if the transaction has its own compatibility
     * space. If it has inherited the compatibility space from its parent, the
     * request will be ignored (or cause a failure in debug builds).
     */
    public void setNoLockWait(boolean noWait) {
        if (LOG.isTraceEnabled())
            LOG.trace("setNoLockWait ");
        rawtran.setNoLockWait(noWait);
    }

    /**
     * Get string id of the transaction.
     * <p>
     * This transaction "name" will be the same id which is returned in the
     * TransactionInfo information, used by the lock and transaction vti's to
     * identify transactions.
     * <p>
     * Although implementation specific, the transaction id is usually a number
     * which is bumped every time a commit or abort is issued.
     * <p>
     * For now return the toString() method, which does what we want. Later if
     * that is not good enough we can add public raw tran interfaces to get
     * exactly what we want.
     *
     * @return The a string which identifies the transaction.
     **/
    public String getTransactionIdString() {
        if (LOG.isTraceEnabled())
            LOG.trace("getTransactionIdString ");
        if(rawtran!=null)
            return (rawtran.toString());
        return "";
    }

    /**
     * Get string id of the transaction that would be when the Transaction is IN
     * active state.
     **/
    public String getActiveStateTxIdString() {
        if (LOG.isTraceEnabled())
            LOG.trace("getActiveStateTxIdString ");
        if(rawtran!=null)
            return (rawtran.getActiveStateTxIdString());
        return "";
    }

    @Override
    public void prepareDataDictionaryChange(String currentDDLChangeId) throws StandardException {
        if (currentDDLChanges==null)
            currentDDLChanges = new LinkedList<>();
        currentDDLChanges.add(currentDDLChangeId);
    }

    @Override
    public void commitDataDictionaryChange() throws StandardException {
        if (currentDDLChanges == null)
            return;
        try {
            for (String currentDDLChange : currentDDLChanges) {
                DDLUtils.finishMetadataChange(currentDDLChange);
            }
        } finally {
            currentDDLChanges.clear();
            currentDDLChanges = null;
        }
    }

    @Override
    public String toString() {
        return "rawtran = " + rawtran;
    }

    /**
     * Return a string depicting the nested transaction stack with the current transaction being at the bottom and its ancestors above it.
     * @return string depicting the nested transaction stack
     */
    private String getNestedTransactionStackString() {
    	SpliceTransactionManager currentTxnMgr = parent_tran;
    	Stack<SpliceTransactionManager> txnStack = new Stack<SpliceTransactionManager>();
    	while (currentTxnMgr != null) {
    		txnStack.push(currentTxnMgr);
    		currentTxnMgr = currentTxnMgr.parent_tran;
    	}

    	StringBuffer sb = new StringBuffer();
    	sb.append(Txn.ROOT_TRANSACTION);
    	sb.append("\n");
    	int count = 2;
    	while (!txnStack.empty()) {
    		appendSpaces(count, sb);
    		sb.append(txnStack.pop().getRawTransaction().toString());
    		sb.append("\n");
    		count += 2;
    	}
		appendSpaces(count, sb);
    	sb.append("currentTxn: ");
    	BaseSpliceTransaction currentTxn = getRawTransaction();
		sb.append(currentTxn == null ? "null" : currentTxn.toString());
    	return sb.toString();
    }

    /**
     * Adds 'n' number of spaces to a buffer.
     * @param n number of spaces to add to a buffer.
     */
    private StringBuffer appendSpaces(int n, StringBuffer buf) {
    	if (buf == null) buf = new StringBuffer(n);
    	for (int i = 0; i < n; i++) {
    		buf.append(" ");
    	}
    	return buf;
    }

    public TxnView getActiveStateTxn() {
        return ((BaseSpliceTransaction)rawtran).getActiveStateTxn();
    }

    /**
     * Add a column to a conglomerate. The conglomerate must not be open in the
     * current transaction. This also means that there must not be any active
     * scans on it.
     *
     * The column can only be added at the spot just after the current set of
     * columns.
     *
     * The template_column must be nullable.
     *
     * After this call has been made, all fetches of this column from rows that
     * existed in the table prior to this call will return "null".
     *
     * @param conglomId
     *            The identifier of the conglomerate to drop.
     * @param column_id
     *            The column number to add this column at.
     * @exception StandardException
     *                Only some types of conglomerates can support adding a
     *                column, for instance "heap" conglomerates support adding a
     *                column while "btree" conglomerates do not. If the column
     *                can not be added an exception will be thrown.
     **/
    public void dropColumnFromConglomerate(long conglomId, int storagePosition, int position)
            throws StandardException {
        if (LOG.isTraceEnabled())
            LOG.trace("addColumnToConglomerate conglomID " + conglomId
                    + ", storagePosition" + storagePosition + ", position = " + position);
        boolean is_temporary = (conglomId < 0);

        Conglomerate conglom = findConglomerate(conglomId);
        if (conglom == null) {
            throw StandardException.newException(
                    SQLState.AM_NO_SUCH_CONGLOMERATE_DROP, conglomId);
        }

        // Get exclusive lock on the table being altered.
        ConglomerateController cc = conglom
                .open(this,
                        rawtran,
                        false,
                        OPENMODE_FORUPDATE,
                        MODE_TABLE,
                        null,
                        null);
        conglom.dropColumn(this, storagePosition, position);

        // remove the old entry in the Conglomerate directory, and add the
        // new one.
        if (is_temporary) {
            // remove old entry in the Conglomerate directory, and add new one
            if (tempCongloms != null){
                tempCongloms.remove(conglomId);
                tempCongloms.put(conglomId, conglom);
            }
        } else {
            alterTableCallMade = true;
            // have access manager update the conglom to this new one.
            accessmanager.conglomCacheUpdateEntry(conglomId, conglom);
        }

        cc.close();
    }

    public long getActiveStateTxId() {
        if(rawtran!=null)
            return getRawTransaction().getActiveStateTxn().getTxnId();
        return -1;
    }

    @Override
    public void rewritePropertyConglomerate() throws StandardException {
        accessmanager.getTransactionalProperties().rewritePropertyConglomerate(this);
    }

    @Override
    public void recoverPropertyConglomerateIfNecessary() throws StandardException {
        accessmanager.getTransactionalProperties().recoverPropertyConglomerateIfNecessary(this);
    }

    @Override
    public long getTxnAt(long ts) throws StandardException {
        try {
            return SIDriver.driver().getTxnStore().getTxnAt(ts);
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }
    }

    @Override
    public boolean txnWithin(long period, long pastTx) throws StandardException {
        if(pastTx < SIConstants.OLDEST_TIME_TRAVEL_TX) {
            return false;
        }
        long mrpTx = 0;
        try {
            mrpTx = SIDriver.driver().getTxnStore().getTxnAt(System.currentTimeMillis() - period * 1000);
        } catch (IOException e) {
            throw Exceptions.parseException(e);
        }
        return mrpTx <= pastTx;
    }

    @Override
    public boolean txnWithin(long period, Timestamp pastTx) throws StandardException {
        Timestamp currentTs = new Timestamp(System.currentTimeMillis());
        if (pastTx.after(currentTs)) { // future time travel is no-op anyway
            return true;
        }
        return ((System.currentTimeMillis() - pastTx.getTime()) / 1000) <= period;
    }
}
