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

package com.splicemachine.si.testenv;

import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.concurrent.Clock;
import com.splicemachine.si.api.txn.TxnStore;
import com.splicemachine.storage.Partition;
import com.splicemachine.timestamp.api.TimestampSource;

import java.io.IOException;

public interface SITestEnv extends SITestDataEnv{

    void initialize() throws IOException;

    String getPersonTableName();

    Clock getClock();

    TxnStore getTxnStore();

    TimestampSource getTimestampSource();

    Partition getPersonTable(TestTransactionSetup tts) throws IOException;

    Partition getPartition(String name, TestTransactionSetup tts) throws IOException;

    PartitionFactory getTableFactory();

    void createTransactionalTable(byte[] tableNameBytes) throws IOException;
}
