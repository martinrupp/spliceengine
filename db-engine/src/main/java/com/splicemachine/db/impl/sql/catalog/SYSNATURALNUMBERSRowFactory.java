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

package com.splicemachine.db.impl.sql.catalog;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.services.uuid.UUIDFactory;
import com.splicemachine.db.iapi.sql.dictionary.*;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.sql.execute.ExecutionFactory;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.types.DataValueFactory;
import com.splicemachine.db.iapi.types.SQLInteger;

import java.sql.Types;

/**
 * Factory for creating a SYSNATURALNUMBERS row.
 */

public class SYSNATURALNUMBERSRowFactory extends CatalogRowFactory
{
    private static final String TABLENAME_STRING = "SYSNATURALNUMBERS";

    private static final int SYSNATURALNUMBERS_COLUMN_COUNT = 1;
    private static final int SYSNATURALNUMBERS_N = 1;

    private static final int MAX_NUMBER = 2048;

    private static final String[] uuids =
    {
         "9ddbc69f-c5fa-4ca8-8b95-aa3bd1e266a4"    // catalog UUID
        ,"9ddbc69f-c60b-4ca8-8b95-aa3bd1e266a4"    // heap UUID
    };

    /////////////////////////////////////////////////////////////////////////////
    //
    //    CONSTRUCTORS
    //
    /////////////////////////////////////////////////////////////////////////////

    public SYSNATURALNUMBERSRowFactory(UUIDFactory uuidf, ExecutionFactory ef, DataValueFactory dvf, DataDictionary dd)
    {
        super(uuidf,ef,dvf,dd);
        initInfo(SYSNATURALNUMBERS_COLUMN_COUNT, TABLENAME_STRING, indexColumnPositions, null, uuids);
    }

    /////////////////////////////////////////////////////////////////////////////
    //
    //    METHODS
    //
    /////////////////////////////////////////////////////////////////////////////

    /**
     * Make a SYSNATURALNUMBERS row
     *
     * @param td NaturalNumberDescriptor
     *
     * @return Row suitable for inserting into SYSNATURALNUMBERS.
     *
     * @exception StandardException thrown on failure
     */
    public ExecRow makeRow(boolean latestVersion, TupleDescriptor td, TupleDescriptor parent)
			throws StandardException
    {
        ExecRow row;
        int value = -1;

        if (td != null) {
            if (!(td instanceof NaturalNumberDescriptor))
                throw new RuntimeException("Unexpected TupleDescriptor " + td.getClass().getName());

            NaturalNumberDescriptor nnd = (NaturalNumberDescriptor)td;
            value = nnd.getValue();
        }

        row = getExecutionFactory().getValueRow(SYSNATURALNUMBERS_COLUMN_COUNT);
        row.setColumn(SYSNATURALNUMBERS_N, new SQLInteger(value));

        return row;
    }

    ///////////////////////////////////////////////////////////////////////////
    //
    //    ABSTRACT METHODS TO BE IMPLEMENTED BY CHILDREN OF CatalogRowFactory
    //
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Make a NaturalNumberDescriptor out of a SYSNATURALNUMBERS row
     *
     * @param row a SYSNATURALNUMBERS row
     * @param parentTupleDescriptor Null for this kind of descriptor.
     * @param dd dataDictionary
     *
     * @param tc
     * @exception   StandardException thrown on failure
     */
    public TupleDescriptor buildDescriptor(ExecRow row, TupleDescriptor parentTupleDescriptor, DataDictionary dd, TransactionController tc)
			throws StandardException
	{
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(
                row.nColumns() == SYSNATURALNUMBERS_COLUMN_COUNT,
                "Wrong number of columns for a SYSNATURALNUMBERS row");
        }

        return new NaturalNumberDescriptor(row.getColumn(SYSNATURALNUMBERS_N).getInt());
    }

    /**
     * Builds a list of columns suitable for creating this Catalog.
     *
     * @return array of SystemColumn suitable for making this catalog.
     */

    public SystemColumn[] buildColumnList() throws StandardException {
       return new SystemColumn[] {
            SystemColumnImpl.getColumn("N", Types.INTEGER, false)
       };
    }

    /**
     * Populate SYSNATURALNUMBERS table.
     *
     * @throws StandardException Standard Derby error policy
     */
    public static void populateSYSNATURALNUMBERS(TabInfoImpl sysNaturalNumbersTable, TransactionController tc)
            throws StandardException
    {
        for (int i = 1; i <= MAX_NUMBER; i++) {
            NaturalNumberDescriptor nnd = new NaturalNumberDescriptor(i);
            ExecRow row = sysNaturalNumbersTable.getCatalogRowFactory().makeRow(nnd, null);
            // ignore return value because sysnaturalnumbers does not have indexes
            sysNaturalNumbersTable.insertRow(row, tc);
        }
    }
}
