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

package com.splicemachine.db.impl.sql.compile;

import com.splicemachine.db.iapi.services.context.ContextManager;
import com.splicemachine.db.iapi.sql.compile.C_NodeTypes;

import com.splicemachine.db.iapi.services.sanity.SanityManager;

import com.splicemachine.db.iapi.error.StandardException;

/**
 * An AllResultColumn represents a "*" result column in a SELECT
 * statement.  It gets replaced with the appropriate set of columns
 * at bind time.
 *
 */
public class AllResultColumn extends ResultColumn
{
    private TableName tableName;

    public AllResultColumn() {}

    public AllResultColumn(TableName t, ContextManager contextManager) {
        super(contextManager);
        setNodeType(C_NodeTypes.ALL_RESULT_COLUMN);
        init(t);
    }

    /**
     * This initializer is for use in the parser for a "*".
     *
     * @param tableName	Dot expression qualifying "*"
     */
    public void init(Object tableName)
    {
        this.tableName = (TableName) tableName;
    }

    /**
     * Return the full table name qualification for this node
     *
     * @return Full table name qualification as a String
     */
    public String getFullTableName()
    {
        if (tableName == null)
        {
            return null;
        }
        else
        {
            return tableName.getFullTableName();
        }
    }

    /**
     * Make a copy of this ResultColumn in a new ResultColumn
     *
     * @return	A new ResultColumn with the same contents as this one
     *
     * @exception StandardException		Thrown on error
     */
    // Splice fork: changed from package protected to public
    public ResultColumn cloneMe() throws StandardException
    {
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(columnDescriptor == null, "columnDescriptor is expected to be null");
        }

        return new AllResultColumn(tableName, getContextManager());
    }

    public TableName getTableNameObject() {
        return tableName;
    }

    /**
     * because super = ResultColumn, and ResultColumn.equals(Object o) { return this == o; }
     * AllResultColumn.equals is the same.
     * so for this to be fully functional, ResultColumn.equals has to be a full equal implementation
     */
    public boolean equals(Object o){
        if(o == this) return true;
        if(o instanceof AllResultColumn) {
            AllResultColumn arc = (AllResultColumn) o;
            return arc.tableName.equals(tableName) &&
                    super.equals(o);
        }
        else {
            return false;
        }
    }

    public int hashCode() {
        int result = super.hashCode();
        return 31 * result + (tableName == null ? 0 : tableName.hashCode());
    }
}
