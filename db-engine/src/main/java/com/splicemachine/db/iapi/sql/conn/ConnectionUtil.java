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

package com.splicemachine.db.iapi.sql.conn;

import com.splicemachine.db.iapi.services.context.ContextService;
import com.splicemachine.db.iapi.services.i18n.MessageService;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.error.ExceptionSeverity;

import java.sql.SQLException;

public class ConnectionUtil {

    /**
        Get the current LanguageConnectionContext.
        Used by public api code that needs to ensure it
        is in the context of a SQL connection.

        @exception SQLException Caller is not in the context of a connection.
    */
    public static LanguageConnectionContext getCurrentLCC()
        throws SQLException {
        return getCurrentLCC(true);
    }

    /**
     Get the current LanguageConnectionContext.
     Used by public api code that needs to ensure it
     is in the context of a SQL connection.

     @param raiseError in case of error, if set, throws an exception, else returns null
     @exception SQLException Caller is not in the context of a connection.
     */
    public static LanguageConnectionContext getCurrentLCC(boolean raiseError) throws SQLException {
        LanguageConnectionContext lcc = (LanguageConnectionContext)
                ContextService.getContextOrNull(LanguageConnectionContext.CONTEXT_ID);

        if (lcc == null && raiseError)
            throw new SQLException(
                    // No current connection
                    MessageService.getTextMessage(
                            SQLState.NO_CURRENT_CONNECTION),
                    SQLState.NO_CURRENT_CONNECTION,
                    ExceptionSeverity.SESSION_SEVERITY);

        return lcc;
    }
}
