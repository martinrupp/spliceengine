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

package com.splicemachine.db.impl.tools.ij;

import com.splicemachine.db.tools.JDBCDisplayUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.SQLWarning;

/**
 * This impl is intended to be used with a resultset,
 * where the execution of the statement is already complete.
 */
@SuppressFBWarnings({"NM_CLASS_NAMING_CONVENTION", "EI_EXPOSE_REP"})
public class ijResultSetResult extends ijResultImpl {

	ResultSet resultSet;
	Statement statement;

	int[]     displayColumns = null;
	int[]     columnWidths = null;

	/**
	 * Create a ijResultImpl that represents a result set.
	 */
	public ijResultSetResult(ResultSet r) throws SQLException {
		resultSet = r;
		statement = resultSet.getStatement();
	}

	/**
	 * Create a ijResultImpl that represents a result set, only
	 * displaying a subset of the columns, using specified column widths.
	 * 
	 * @param r The result set to display
	 * @param display Which column numbers to display, or null to display
	 *                all columns.
	 * @param widths  The widths of the columns specified in 'display', or
	 *                null to display using default column sizes.
	 */
	@SuppressFBWarnings("EI_EXPOSE_REP2")
	public ijResultSetResult(ResultSet r, int[] display,
							 int[] widths) throws SQLException {
		resultSet = r;
		statement = resultSet.getStatement();

		displayColumns = display;
		columnWidths   = widths;
	}

	static class ColumnParameters
	{
		public int pos, preferredWidth;
		public int maxWidth = 0; // 0 meaning no maximum

		public ColumnParameters(ResultSet rs, String name, int preferredWidth) throws SQLException {
			this.pos = rs.findColumn(name);
			this.preferredWidth = preferredWidth;
		}
		public ColumnParameters maxWidth(int val)
		{
			this.maxWidth = val;
			return this;
		}
	}

	public ijResultSetResult(ResultSet r, ColumnParameters[] columnParameters) throws SQLException {
		resultSet = r;
		statement = resultSet.getStatement();
		this.displayColumns = new int[columnParameters.length];
		this.columnWidths = new int[columnParameters.length];
		for(int i=0; i<columnParameters.length; i++)
		{
			displayColumns[i] = columnParameters[i].pos;
			int colWidth = columnParameters[i].preferredWidth;
			// if != 0, this is the maximum width this column needs
			int maximumWidthOfColumn  = columnParameters[i].maxWidth;
			// maximumdisplaywidth defined for the sqlshell session
			int maximumdisplaywidth = JDBCDisplayUtil.getMaxDisplayWidth();
			if( maximumdisplaywidth != JDBCDisplayUtil.MAXWIDTH_DEFAULT )
			{
				if(maximumdisplaywidth == JDBCDisplayUtil.MAXWIDTH_NO_ALIGN)
					colWidth = 0; // width=0 -> no align mode
				else if (maximumWidthOfColumn == 0 )
					colWidth = maximumdisplaywidth; // this column didn't define a max width.
				else // this columned defined a max width
					colWidth = Math.min(maximumWidthOfColumn, maximumdisplaywidth);
			}
			// else (maximumdisplaywidth == JDBCDisplayUtil.MAXWIDTH_DEFAULT): no change to colWidth
			columnWidths[i] = colWidth;
		}
	}

	public boolean isResultSet() throws SQLException { return statement==null || statement.getUpdateCount() == -1; }

	public ResultSet getResultSet() throws SQLException { return resultSet; }

	public void closeStatement() throws SQLException { if(statement!=null) statement.close(); else resultSet.close(); }

	public int[] getColumnDisplayList() { return displayColumns; }
	public int[] getColumnWidthList() { return columnWidths; }

	public SQLWarning getSQLWarnings() throws SQLException { return resultSet.getWarnings(); }
	public void clearSQLWarnings() throws SQLException { resultSet.clearWarnings(); }
}
