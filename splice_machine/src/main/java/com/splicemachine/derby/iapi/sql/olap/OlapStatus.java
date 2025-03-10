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

package com.splicemachine.derby.iapi.sql.olap;

import com.splicemachine.db.shared.ProgressInfo;

import java.util.concurrent.TimeUnit;

/**
 * @author Scott Fines
 *         Date: 4/1/16
 */
public interface OlapStatus{
    enum State{
        NOT_SUBMITTED,
        SUBMITTED,
        RUNNING,
        CANCELED{
            @Override public boolean isFinal(){ return true; }
        },
        FAILED{
            @Override public boolean isFinal(){ return true; }
        },
        COMPLETE{
            @Override public boolean isFinal(){ return true; }
        };

        public boolean isFinal(){ return false; }
    }

    State checkState();

    OlapResult getResult();

    void cancel();

    boolean isAvailable();

    boolean markSubmitted();

    void markCompleted(OlapResult result);

    boolean markRunning();

    boolean isRunning();

    boolean wait(long time, TimeUnit unit) throws InterruptedException;

    String getProgressString();
    void setProgress(ProgressInfo info);
}
