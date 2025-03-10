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
package com.splicemachine.dbTesting.functionTests.tests.jdbc4;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import junit.framework.Test;
import com.splicemachine.dbTesting.junit.BaseJDBCTestCase;
import com.splicemachine.dbTesting.junit.TestConfiguration;

/**
 * tests set methods of blob
 */
public class BlobSetMethodsTest extends BaseJDBCTestCase {
    private static int BUFFER_SIZE = 1024;
    private static int UPDATE_SIZE = 100;

    public BlobSetMethodsTest (String name) {
        super (name);
    }

    protected void setUp() throws Exception {
        Connection con = getConnection();
        Statement stmt = con.createStatement();
        stmt.execute ("create table blobtest (id integer, data Blob)");
        stmt.close();
        con.close();
    }

    /**
     * Create test suite.
     */
    public static Test suite() {
        return TestConfiguration.defaultSuite (BlobSetMethodsTest.class);
    }

    /**
     * Tests large blob (more than 4k) to ensure LOBStreamControl uses file.
     */
    public void testSetBytesLargeBlob () throws SQLException {
        Connection con = getConnection();
        con.setAutoCommit (false);
        PreparedStatement pstmt = con.prepareStatement("insert into " +
                "blobtest (id, data) values (?,?)");
        Blob blob = con.createBlob();
        byte [] data = new byte [BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            data [i] = (byte) (i % 255);
        }
     //now add more than 4k so file get in use
        for (int i = 0; i < 5; i++)
            blob.setBytes (i * BUFFER_SIZE + 1, data);
        assertEquals (BUFFER_SIZE * 5 , blob.length());
                    //update blob in the middle
        byte [] data1 = new byte [UPDATE_SIZE];
        for (int i = 0; i < UPDATE_SIZE; i++)
            data1 [i] = 120;//just any value
        blob.setBytes (BUFFER_SIZE + 1, data1);
        blob.setBytes (BUFFER_SIZE * 5 + 1, data1);
        assertEquals (5 * BUFFER_SIZE + UPDATE_SIZE, blob.length());
        //insert it into table
        pstmt.setInt (1, 3);
        pstmt.setBlob (2, blob);
        pstmt.executeUpdate ();
        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery("select data from blobtest where " +
                                "id = 3");
        assertEquals(true, rs.next());
        blob = rs.getBlob (1);
        byte [] data2 = blob.getBytes (BUFFER_SIZE + 1, UPDATE_SIZE);
        assertEquals (5 * BUFFER_SIZE + UPDATE_SIZE, blob.length());
        for (int i = 0; i < UPDATE_SIZE; i++)
            assertEquals (data1 [i], data2 [i]);
        data2 = blob.getBytes (5 * BUFFER_SIZE + 1, UPDATE_SIZE);
        for (int i = 0; i < UPDATE_SIZE; i++)
            assertEquals (data1 [i], data2 [i]);
        //test truncate
        blob.truncate (BUFFER_SIZE);
        assertEquals ("truncate failed", BUFFER_SIZE, blob.length());
        rs.close();
        con.commit();
        stmt.close();
        pstmt.close();
    }

    /**
     * tests set bytes method of blob in memory only mode (less than 4k)
     */
    public void testSetBytesSmallBlob () throws SQLException {
        Connection con = getConnection();
        con.setAutoCommit (false);
        PreparedStatement pstmt = con.prepareStatement("insert into " +
                "blobtest (id, data) values (?,?)");
        pstmt.setInt (1,1);
        Blob blob = con.createBlob();
        //add 1024 bytes
        byte [] data = new byte [BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++) {
            data [i] = (byte) (i % 255);
        }
        blob.setBytes (1, data);
        assertEquals (BUFFER_SIZE, blob.length());
        pstmt.setBlob (2, blob);
        pstmt.executeUpdate();
        Statement stmt = con.createStatement();
        ResultSet rs = stmt.executeQuery(
                "select data from blobtest where id = 1");
        assertEquals(true, rs.next());
        blob = rs.getBlob (1);
        assertEquals (BUFFER_SIZE, blob.length());
        //update blob in the middle
        byte [] data1 = new byte [UPDATE_SIZE];
        for (int i = 0; i < UPDATE_SIZE; i++)
            data1 [i] = 120;//just any value
        blob.setBytes (UPDATE_SIZE, data1);
        byte [] data2 = blob.getBytes (100, UPDATE_SIZE);
        for (int i = 0; i < UPDATE_SIZE; i++)
            assertEquals (data1 [i], data2 [i]);
        //update it at the end
        blob.setBytes (BUFFER_SIZE + 1, data1);
        assertEquals (BUFFER_SIZE + UPDATE_SIZE, blob.length());
        data2 = blob.getBytes (BUFFER_SIZE + 1, UPDATE_SIZE);
        for (int i = 0; i < UPDATE_SIZE; i++)
            assertEquals (data1 [i], data2 [i]);
        //insert the blob and test again
        pstmt.setInt (1, 2);
        pstmt.setBlob (2, blob);
        pstmt.executeUpdate();
        rs = stmt.executeQuery("select data from blobtest where " +
                "id = 2");
        assertEquals(true, rs.next());
        blob = rs.getBlob (1);
        assertEquals (BUFFER_SIZE + UPDATE_SIZE, blob.length());
        data2 = blob.getBytes (100, UPDATE_SIZE);
        for (int i = 0; i < UPDATE_SIZE; i++)
            assertEquals (data1 [i], data2 [i]);
        data2 = blob.getBytes (BUFFER_SIZE + 1, UPDATE_SIZE);
        for (int i = 0; i < UPDATE_SIZE; i++)
            assertEquals (data1 [i], data2 [i]);

        //test truncate on small size blob
        blob = con.createBlob();
        data = new byte [100];
        for (int i = 0; i < 100; i++) {
            data [i] = (byte) i;
        }
        blob.setBytes (1, data);
        assertEquals (blob.length(), 100);
        blob.truncate (50);
        assertEquals (blob.length(), 50);
        blob.setBytes (1, data);
        assertEquals ("set failed", blob.length(), 100);
        blob.truncate (50);
        assertEquals ("truncation failed", blob.length(), 50);
        rs.close();
        con.commit();
        stmt.close();
        pstmt.close();
    }

    protected void tearDown() throws Exception {
        Connection con = getConnection();
        con.setAutoCommit (true);
        Statement stmt = con.createStatement();
        stmt.execute ("drop table blobtest");
        stmt.close();
        super.tearDown();
    }
}
