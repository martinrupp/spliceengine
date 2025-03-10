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

package com.splicemachine.pipeline.mem;

import com.splicemachine.pipeline.PipelineWriter;
import com.splicemachine.pipeline.api.BulkWriter;
import com.splicemachine.pipeline.client.BulkWrites;
import com.splicemachine.pipeline.client.BulkWritesResult;

import java.io.IOException;

/**
 * @author Scott Fines
 *         Date: 12/23/15
 */
public class DirectBulkWriter implements BulkWriter{
    PipelineWriter writer;

    public DirectBulkWriter(PipelineWriter writer){
        this.writer = writer;
    }

    @Override
    public BulkWritesResult write(BulkWrites write,boolean refreshCache, boolean loadReplaceMode) throws IOException{
        return writer.bulkWrite(write, -1, loadReplaceMode);
    }
}
