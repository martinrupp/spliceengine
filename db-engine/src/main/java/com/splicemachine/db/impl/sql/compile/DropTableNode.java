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

import com.splicemachine.db.iapi.error.ExceptionSeverity;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.StatementType;
import com.splicemachine.db.iapi.sql.compile.CompilerContext;
import com.splicemachine.db.iapi.sql.dictionary.ConglomerateDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.sql.execute.ConstantAction;

/**
 * A DropTableNode is the root of a QueryTree that represents a DROP TABLE
 * statement.
 *
 */

public class DropTableNode extends DDLStatementNode
{
	private long		conglomerateNumber;
	private int			dropBehavior;
	private	TableDescriptor	td;

	/**
	 * Intializer for a DropTableNode
	 *
	 * @param dropObjectName	The name of the object being dropped
	 * @param dropBehavior		Drop behavior (RESTRICT | CASCADE)
	 *
	 */

	public void init(Object dropObjectName, Object dropBehavior)
		throws StandardException
	{
		initAndCheck(dropObjectName);
		this.dropBehavior = (Integer) dropBehavior;
	}

	/**
	 * Convert this object to a String.  See comments in QueryTreeNode.java
	 * for how this should be done for tree printing.
	 *
	 * @return	This object as a String
	 */

	public String toString()
	{
		if (SanityManager.DEBUG)
		{
			return super.toString() +
				"conglomerateNumber: " + conglomerateNumber + "\n" +
				"td: " + ((td == null) ? "null" : td.toString()) + "\n" +
				"dropBehavior: " + "\n" + dropBehavior + "\n";
		}
		else
		{
			return "";
		}
	}

	public String statementToString()
	{
		return "DROP TABLE";
	}

	/**
	 * Bind this LockTableNode.  This means looking up the table,
	 * verifying it exists and getting the heap conglomerate number.
	 *
	 *
	 * @exception StandardException		Thrown on error
	 */

	public void bindStatement() throws StandardException
	{
		CompilerContext			cc = getCompilerContext();

		try {
			td = getTableDescriptor();
		} catch (StandardException e) {
	        if (e.getMessageId().equals(SQLState.LANG_OBJECT_DOES_NOT_EXIST) && this.dropBehavior == StatementType.DROP_IF_EXISTS) {
	        	// this case is only a warning - no exception - reset severity to WARN
	            e.setSeverity(ExceptionSeverity.WARNING_SEVERITY);
	        }
            throw e;
		}

		conglomerateNumber = td.getHeapConglomerateId();

		/* Get the base conglomerate descriptor */
		ConglomerateDescriptor cd = td.getConglomerateDescriptor(conglomerateNumber);

		/* Statement is dependent on the TableDescriptor and ConglomerateDescriptor */
		cc.createDependency(td);
		cc.createDependency(cd);
	}

	/**
	 * Return true if the node references SESSION schema tables (temporary or permanent)
	 *
	 * @return	true if references SESSION schema tables, else false
	 *
	 * @exception StandardException		Thrown on error
	 */
	@Override
	public boolean referencesSessionSchema()
		throws StandardException
	{
		//If table being dropped is in SESSION schema, then return true. 
		return isSessionSchema(td.getSchemaDescriptor());
	}

	/**
	 * Return true if the node references temporary tables no matter under which schema
	 *
	 * @return true if references temporary tables, else false
	 */
	@Override
	public boolean referencesTemporaryTable() {
		return td.isTemporary();
	}

	// inherit generate() method from DDLStatementNode


	/**
	 * Create the Constant information that will drive the guts of Execution.
	 *
	 * @exception StandardException		Thrown on failure
	 */
	public ConstantAction	makeConstantAction() throws StandardException
	{
		return	getGenericConstantActionFactory().getDropTableConstantAction(
			getFullName(),
			getRelativeName(),
			getSchemaDescriptor(!td.isTemporary(), true),
			conglomerateNumber,
			td.getUUID(),
			dropBehavior);
	}
}
