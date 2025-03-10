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
package com.splicemachine.derby.impl.sql.compile;

import com.splicemachine.db.shared.common.reference.SQLState;
import com.splicemachine.derby.test.framework.SpliceSchemaWatcher;
import com.splicemachine.derby.test.framework.SpliceUnitTest;
import com.splicemachine.derby.test.framework.SpliceWatcher;
import com.splicemachine.derby.test.framework.TestConnection;
import com.splicemachine.homeless.TestUtils;
import com.splicemachine.test.LongerThanTwoMinutes;
import com.splicemachine.test.SerialTest;
import com.splicemachine.test_tools.TableCreator;
import org.junit.*;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import splice.com.google.common.collect.Lists;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.splicemachine.test_tools.Rows.row;
import static com.splicemachine.test_tools.Rows.rows;
import static org.junit.Assert.assertEquals;

/**
 * Created by yxia on 11/27/17.
 */
@RunWith(Parameterized.class)
@Category({SerialTest.class, LongerThanTwoMinutes.class})
public class UpdateFromSubqueryIT extends SpliceUnitTest {
    private static final String SCHEMA = UpdateFromSubqueryIT.class.getSimpleName().toUpperCase();
    private static SpliceWatcher spliceClassWatcher = new SpliceWatcher(SCHEMA);

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        Collection<Object[]> params = Lists.newArrayListWithCapacity(8);
        params.add(new Object[]{"NESTEDLOOP","true"});
        params.add(new Object[]{"SORTMERGE","true"});
        params.add(new Object[]{"BROADCAST","true"});
        params.add(new Object[]{"MERGE","true"});
        params.add(new Object[]{"NESTEDLOOP","false"});
        params.add(new Object[]{"SORTMERGE","false"});
        params.add(new Object[]{"BROADCAST","false"});
        params.add(new Object[]{"MERGE","false"});
        return params;
    }

    private String joinStrategy;
    private String useSparkString;

    public UpdateFromSubqueryIT(String joinStrategy, String useSparkString) {
        this.joinStrategy = joinStrategy;
        this.useSparkString = useSparkString;
    }

    @ClassRule
    public static SpliceSchemaWatcher spliceSchemaWatcher = new SpliceSchemaWatcher(SCHEMA);

    @BeforeClass
    public static void createSharedTables() throws Exception {
        TestConnection connection=spliceClassWatcher.getOrCreateConnection();
        new TableCreator(connection)
                .withCreate("create table t1 (a1 int, b1 int, c1 int, d1 int, primary key(a1,d1))")
                .withInsert("insert into t1 values(?,?,?,?)")
                .withRows(rows(row(1, 1, 1, 1),
                        row(2, 2, 2, 2),
                        row(3, 3, 3, 3),
                        row(1, 1, 1, 11),
                        row(2, 2, 2, 21))).create();

        new TableCreator(connection)
                .withCreate("create table t2 (a2 int, b2 int, c2 int, d2 int, primary key (a2,d2))")
                .withInsert("insert into t2 values(?,?,?,?)")
                .withRows(rows(row(1, 10, 10, 10),
                        row(3, 30, 30, 30),
                        row(4, 40, 40, 40))).create();

        new TableCreator(connection)
                .withCreate("create table t3 (a3 int, b3 int default 5, c3 int, d3 varchar(20) default 'NNN', e3 varchar(20))")
                .withIndex("create index T3_IX_C3 on t3 (c3)")
                .withInsert("INSERT INTO T3 VALUES(?,?,?,?,?)")
                .withRows(rows(row(8, 8, 8, "GGG", "GGG"), row(10, 10, 10, "III", "III"), row(3,3,3,"AAA", "AAA")))
                .create();

        new TableCreator(connection)
                .withCreate("create table t4 (a4 int, b4 int, c4 char(3))")
                .withIndex("create index T4_IX on t4 (a4, b4)")
                .withInsert("INSERT INTO T4 VALUES(?,?,?)")
                .withRows(rows(row(8, 1000, "GGG"), row(10, 1000, "III")))
                .create();

        new TableCreator(connection)
                .withCreate("CREATE TABLE DIM_PROFILE (\n" +
                "            DIM_PROFILE_KEY1 VARCHAR(100),\n" +
                "            DIM_PROFILE_KEY2 int,\n" +
                "            EMAIL_ADDRESS VARCHAR(100),\n" +
                "            extra_int int,\n" +
                "            primary key(DIM_PROFILE_KEY1, DIM_PROFILE_KEY2))")
                .withInsert("INSERT INTO DIM_PROFILE VALUES(?,?,?,?)")
                .withRows(rows(row("1", 1, "username@email.com", 11), row("2", 2, null, 22),
                               row("3", 3, null, null), row("4", 4, "prot@kpax.pnt", null)))
                .create();

        new TableCreator(connection)
                .withCreate("CREATE TABLE DIM_PROFILE2 (\n" +
                "            DIM_PROFILE_KEY1 VARCHAR(100),\n" +
                "            DIM_PROFILE_KEY2 int,\n" +
                "            EMAIL_ADDRESS VARCHAR(100),\n" +
                "            extra_int int,\n" +
                "            primary key(DIM_PROFILE_KEY1, DIM_PROFILE_KEY2))")
                .withInsert("INSERT INTO DIM_PROFILE2 VALUES(?,?,?,?)")
                .withRows(rows(row("1", 1, null, null), row("2", 2, "mork@ork.pnt", null),
                               row("3", 3, "betelgeuse@eastcorinth.vt.us", 33), row("4", 4, null, 44)))
                .create();

        new TableCreator(connection)
                .withCreate("CREATE TABLE DIM_PROFILE_NN (\n" +
                "            DIM_PROFILE_KEY1 VARCHAR(100),\n" +
                "            DIM_PROFILE_KEY2 int,\n" +
                "            EMAIL_ADDRESS VARCHAR(100) NOT NULL,\n" +
                "            extra_int int NOT NULL,\n" +
                "            primary key(DIM_PROFILE_KEY1, DIM_PROFILE_KEY2))")
                .withInsert("INSERT INTO DIM_PROFILE_NN VALUES(?,?,?,?)")
                .withRows(rows(row("1", 1, "username@email.com", 11), row("2", 2, "mj@nike.com", 22),
                               row("3", 3, "blightyear@pixar.com", 33), row("4", 4, "prot@kpax.pnt", 44)))
                .create();

        new TableCreator(connection)
                .withCreate("create table tab5 (a5 int, b5 int, c5 int)")
                .withInsert("insert into tab5 values (?,?,?)")
                .withRows(rows(row(1, 10, 100)))
                .create();

        new TableCreator(connection)
                .withCreate("create table tab6 (a6 int, b6 int, c6 int)")
                .withInsert("insert into tab6 values (?,?,?)")
                .withRows(rows(row(2, 20, 200)))
                .create();
    }

    private Connection conn;

    @Before
    public void setUpTest() throws Exception{
        conn=spliceClassWatcher.getOrCreateConnection();
        conn.setAutoCommit(false);
    }

    @After
    public void tearDownTest() throws Exception{
        conn.rollback();
    }

    @Test
    public void testUpdateFromSpliceTable() throws Exception {
        spliceClassWatcher.executeUpdate(format("update t1 set (b1) = (select b2 from t2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
                "where a1=a2)", this.joinStrategy, this.useSparkString));

        String sql = "select * from t1";

        String expected = "A1 |B1 |C1 |D1 |\n" +
                "----------------\n" +
                " 1 |10 | 1 | 1 |\n" +
                " 1 |10 | 1 |11 |\n" +
                " 2 | 2 | 2 | 2 |\n" +
                " 2 | 2 | 2 |21 |\n" +
                " 3 |30 | 3 | 3 |";
        ResultSet rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();
    }

    @Test
    public void testUpdateFromVTITable() throws Exception {
        try {
            spliceClassWatcher.executeUpdate(format("update t1 set (b1) = (select b2 from " +
                            "new com.splicemachine.derby.vti.SpliceFileVTI('%s',NULL,',',NULL,'HH:mm:ss','yyyy-MM-dd','yyyy-MM-ddHH:mm:ss.SSZ','true','UTF-8') AS importVTI (a2 INTEGER, b2 INTEGER, C2 CHAR(24)) --splice-properties joinStrategy=%s, useSpark=%s\n" +
                            "WHERE a1 = importVTI.a2 - 30)",
                    SpliceUnitTest.getResourceDirectory() + "t1_part2.csv",
                    this.joinStrategy, this.useSparkString));

            String sql = "select * from t1";

            String expected = "A1 |B1 |C1 |D1 |\n" +
                    "----------------\n" +
                    " 1 |31 | 1 | 1 |\n" +
                    " 1 |31 | 1 |11 |\n" +
                    " 2 |32 | 2 | 2 |\n" +
                    " 2 |32 | 2 |21 |\n" +
                    " 3 |33 | 3 | 3 |";
            ResultSet rs = spliceClassWatcher.executeQuery(sql);
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
            rs.close();
        } catch (SQLException e) {
            if (this.joinStrategy == "MERGE")
                Assert.assertEquals("Upexpected failure: "+ e.getMessage(), e.getSQLState(), SQLState.LANG_NO_BEST_PLAN_FOUND);
            else
                Assert.fail("Unexpected failure for join strategy: " + this.joinStrategy);
        }
    }

    @Test
    public void testUpdateFromIndexLookupAccessPath() throws Exception {
        if (this.joinStrategy != "MERGE") {
            /* update with from subquery */
            String sql = format("update t3 --splice-properties index=t3_ix_c3, useSpark=%s\n" +
                    "set (b3) = (select b4 from t4 --splice-properties joinStrategy=%s \n" +
                    "where a3=a4)", this.useSparkString, this.joinStrategy);
            int n = spliceClassWatcher.executeUpdate(sql);
            Assert.assertEquals("Incorrect number of rows updated", 2, n);

            sql = "select * from t3";

            String expected = "A3 | B3  |C3 |D3  |E3  |\n" +
                    "------------------------\n" +
                    "10 |1000 |10 |III |III |\n" +
                    " 3 |  3  | 3 |AAA |AAA |\n" +
                    " 8 |1000 | 8 |GGG |GGG |";
            ResultSet rs = spliceClassWatcher.executeQuery(sql);
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
            rs.close();
        } else {
            /* update with a different query to test */
            String sql = format("update t3 --splice-properties index=t3_ix_c3, useSpark=%s\n" +
                    "set (b3) = (select b4 from t4 --splice-properties joinStrategy=%s \n" +
                    "where c3=a4)", this.useSparkString, this.joinStrategy);
            int n = spliceClassWatcher.executeUpdate(sql);
            Assert.assertEquals("Incorrect number of rows updated", 2, n);

            sql = "select * from t3";

            String expected = "A3 | B3  |C3 |D3  |E3  |\n" +
                    "------------------------\n" +
                    "10 |1000 |10 |III |III |\n" +
                    " 3 |  3  | 3 |AAA |AAA |\n" +
                    " 8 |1000 | 8 |GGG |GGG |";
            ResultSet rs = spliceClassWatcher.executeQuery(sql);
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
            rs.close();
        }
    }

    @Test
    public void testUpdateWithNulls() throws Exception {
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EMAIL_ADDRESS, DIM_PROFILE_KEY1) =\n" +
        "(SELECT EMAIL_ADDRESS, DIM_PROFILE_KEY1 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        String sql = "select * from DIM_PROFILE";

        String expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |        EMAIL_ADDRESS        | EXTRA_INT |\n" +
        "------------------------------------------------------------------------------\n" +
        "        1        |        1        |            NULL             |    11     |\n" +
        "        2        |        2        |        mork@ork.pnt         |    22     |\n" +
        "        3        |        3        |betelgeuse@eastcorinth.vt.us |   NULL    |\n" +
        "        4        |        4        |            NULL             |   NULL    |";
        ResultSet rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();

        spliceClassWatcher.rollback();
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EMAIL_ADDRESS, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EMAIL_ADDRESS, DIM_PROFILE_KEY2 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();

        spliceClassWatcher.rollback();
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EXTRA_INT, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, DIM_PROFILE_KEY2 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |   EMAIL_ADDRESS   | EXTRA_INT |\n" +
        "--------------------------------------------------------------------\n" +
        "        1        |        1        |username@email.com |   NULL    |\n" +
        "        2        |        2        |       NULL        |   NULL    |\n" +
        "        3        |        3        |       NULL        |    33     |\n" +
        "        4        |        4        |   prot@kpax.pnt   |    44     |";
        rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();

        spliceClassWatcher.rollback();
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY2 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |        EMAIL_ADDRESS        | EXTRA_INT |\n" +
        "------------------------------------------------------------------------------\n" +
        "        1        |        1        |            NULL             |   NULL    |\n" +
        "        2        |        2        |        mork@ork.pnt         |   NULL    |\n" +
        "        3        |        3        |betelgeuse@eastcorinth.vt.us |    33     |\n" +
        "        4        |        4        |            NULL             |    44     |";
        rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();

        spliceClassWatcher.rollback();
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, 123 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |        EMAIL_ADDRESS        | EXTRA_INT |\n" +
        "------------------------------------------------------------------------------\n" +
        "        1        |       123       |            NULL             |   NULL    |\n" +
        "        2        |       123       |        mork@ork.pnt         |   NULL    |\n" +
        "        3        |       123       |betelgeuse@eastcorinth.vt.us |    33     |\n" +
        "        4        |       123       |            NULL             |    44     |";
        rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();

        spliceClassWatcher.rollback();
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY1) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, 123 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |        EMAIL_ADDRESS        | EXTRA_INT |\n" +
        "------------------------------------------------------------------------------\n" +
        "       123       |        1        |            NULL             |   NULL    |\n" +
        "       123       |        2        |        mork@ork.pnt         |   NULL    |\n" +
        "       123       |        3        |betelgeuse@eastcorinth.vt.us |    33     |\n" +
        "       123       |        4        |            NULL             |    44     |";
        rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();

        spliceClassWatcher.rollback();
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY1, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE.DIM_PROFILE_KEY1-1, DIM_PROFILE.DIM_PROFILE_KEY2+1 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |        EMAIL_ADDRESS        | EXTRA_INT |\n" +
        "------------------------------------------------------------------------------\n" +
        "        0        |        2        |            NULL             |   NULL    |\n" +
        "        1        |        3        |        mork@ork.pnt         |   NULL    |\n" +
        "        2        |        4        |betelgeuse@eastcorinth.vt.us |    33     |\n" +
        "        3        |        5        |            NULL             |    44     |";
        rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();

        spliceClassWatcher.rollback();
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY1, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE2.DIM_PROFILE_KEY1-1, DIM_PROFILE2.DIM_PROFILE_KEY2+1 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString));

        expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |        EMAIL_ADDRESS        | EXTRA_INT |\n" +
        "------------------------------------------------------------------------------\n" +
        "        0        |        2        |            NULL             |   NULL    |\n" +
        "        1        |        3        |        mork@ork.pnt         |   NULL    |\n" +
        "        2        |        4        |betelgeuse@eastcorinth.vt.us |    33     |\n" +
        "        3        |        5        |            NULL             |    44     |";
        rs = spliceClassWatcher.executeQuery(sql);
        assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        rs.close();
    }

    @Test
    public void testIllegalUpdateWithNulls() throws Exception {
        String sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EMAIL_ADDRESS, DIM_PROFILE_KEY1) =\n" +
        "(SELECT EMAIL_ADDRESS, DIM_PROFILE_KEY1 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);

        List<String> expectedErrors =
          Arrays.asList("Column 'EMAIL_ADDRESS' cannot accept a NULL value.",
                        "Column 'EXTRA_INT' cannot accept a NULL value.");
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

        sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EMAIL_ADDRESS, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EMAIL_ADDRESS, DIM_PROFILE_KEY2 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

        sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EXTRA_INT, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, DIM_PROFILE_KEY2 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

        sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY2 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

        sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, 123 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

        sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY1) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, 123 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

        sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY1, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_NN.DIM_PROFILE_KEY1-1, DIM_PROFILE_NN.DIM_PROFILE_KEY2+1 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

        sqlText = format("UPDATE DIM_PROFILE_NN\n" +
        " set (EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE_KEY1, DIM_PROFILE_KEY2) =\n" +
        "(SELECT EXTRA_INT, EMAIL_ADDRESS, DIM_PROFILE2.DIM_PROFILE_KEY1-1, DIM_PROFILE2.DIM_PROFILE_KEY2+1 from DIM_PROFILE2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
        "WHERE DIM_PROFILE_NN.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1\n" +
        " and DIM_PROFILE_NN.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2)", this.joinStrategy, this.useSparkString);
        testUpdateFail(sqlText, expectedErrors, spliceClassWatcher);

    }

    @Test
    public void testNonFlattenedSubqueryShouldNotConsiderJoin() throws Exception {
        if (this.joinStrategy.equals("SORTMERGE") || this.joinStrategy.equals("BROADCAST") || this.joinStrategy.equals("MERGE")) {
            return;
        }
        spliceClassWatcher.executeUpdate(format("update tab6 set b6=(select a5 from tab5 --splice-properties useSpark=%s\n" +
                                                "where a6=a5+1)", this.useSparkString));
        String sql = "select * from tab6";

        String expected = "A6 |B6 |C6  |\n" +
                "-------------\n" +
                " 2 | 1 |200 |";
        try (ResultSet rs = spliceClassWatcher.executeQuery(sql)) {
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
        spliceClassWatcher.rollback();
    }

    @Test
    public void testNestedFromSubquery() throws Exception {
        if (this.joinStrategy.equals("SORTMERGE") || this.joinStrategy.equals("BROADCAST") || this.joinStrategy.equals("MERGE")) {
            return;
        }

        // inline syntax with list of columns in parentheses
        // where clause is part of the subquery
        spliceClassWatcher.executeUpdate(format("update t1 set (b1) = (select b2 from t2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
                " cross join (select a3 from t3) where a1=a2)", this.joinStrategy, this.useSparkString));

        String expected = "A1 |B1 |C1 |D1 |\n" +
                "----------------\n" +
                " 1 |10 | 1 | 1 |\n" +
                " 1 |10 | 1 |11 |\n" +
                " 2 | 2 | 2 | 2 |\n" +
                " 2 | 2 | 2 |21 |\n" +
                " 3 |30 | 3 | 3 |";
        try (ResultSet rs = spliceClassWatcher.executeQuery("select * from t1")) {
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }

        // UPDATE ... FROM, single subquery
        // where clause is part of the update statement
        spliceClassWatcher.executeUpdate(format("update t1 set b1 = b2" +
                " from (select a2, b2 from t2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
                " cross join (select a3 from t3)) where a1=a2", this.joinStrategy, this.useSparkString));

        try (ResultSet rs = spliceClassWatcher.executeQuery("select * from t1")) {
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }

        // UPDATE ... FROM, from list
        // where clause is part of the update statement
        spliceClassWatcher.executeUpdate(format("update t1 set b1 = b2" +
                " from t2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
                "    , (select a3 from t3) where a1=a2", this.joinStrategy, this.useSparkString));

        try (ResultSet rs = spliceClassWatcher.executeQuery("select * from t1")) {
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
    }

    @Test
    public void testUpdateMultipleColumnsUsingFromSyntax() throws Exception {
        spliceClassWatcher.executeUpdate(format("UPDATE DIM_PROFILE " +
                " SET EMAIL_ADDRESS = EMAIL_ADDRESS " +
                "   , DIM_PROFILE_KEY1 = DIM_PROFILE_KEY1 " +
                " FROM DIM_PROFILE2 --splice-properties joinStrategy=%s, useSpark=%s \n" +
                " WHERE DIM_PROFILE.DIM_PROFILE_KEY1 = DIM_PROFILE2.DIM_PROFILE_KEY1 " +
                "   AND DIM_PROFILE.DIM_PROFILE_KEY2 = DIM_PROFILE2.DIM_PROFILE_KEY2", this.joinStrategy, this.useSparkString));

        String expected = "DIM_PROFILE_KEY1 |DIM_PROFILE_KEY2 |        EMAIL_ADDRESS        | EXTRA_INT |\n" +
                "------------------------------------------------------------------------------\n" +
                "        1        |        1        |            NULL             |    11     |\n" +
                "        2        |        2        |        mork@ork.pnt         |    22     |\n" +
                "        3        |        3        |betelgeuse@eastcorinth.vt.us |   NULL    |\n" +
                "        4        |        4        |            NULL             |   NULL    |";

        try (ResultSet rs = spliceClassWatcher.executeQuery("select * from DIM_PROFILE")) {
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
    }

    @Test
    public void testSetExpressionsFrom() throws Exception {
        if (this.joinStrategy.equals("SORTMERGE") || this.joinStrategy.equals("BROADCAST") || this.joinStrategy.equals("MERGE")) {
            return;
        }

        spliceClassWatcher.executeUpdate(format("update t1 set b1 = b2 + 1" +
                " from t2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
                " cross join (select a3 from t3) where a1=a2", this.joinStrategy, this.useSparkString));

        String expected = "A1 |B1 |C1 |D1 |\n" +
                "----------------\n" +
                " 1 |11 | 1 | 1 |\n" +
                " 1 |11 | 1 |11 |\n" +
                " 2 | 2 | 2 | 2 |\n" +
                " 2 | 2 | 2 |21 |\n" +
                " 3 |31 | 3 | 3 |";
        try (ResultSet rs = spliceClassWatcher.executeQuery("select * from t1")) {
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
    }

    @Test
    public void testExpressionsInBothSetAndSubquery() throws Exception {
        if (this.joinStrategy.equals("SORTMERGE") || this.joinStrategy.equals("BROADCAST") || this.joinStrategy.equals("MERGE")) {
            return;
        }

        spliceClassWatcher.executeUpdate(format("update t1 set b1 = col + 2" +
                " from (select b2 + 1 as col, a2 from t2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
                " cross join (select a3 from t3)) where a1=a2", this.joinStrategy, this.useSparkString));

        String expected = "A1 |B1 |C1 |D1 |\n" +
                "----------------\n" +
                " 1 |13 | 1 | 1 |\n" +
                " 1 |13 | 1 |11 |\n" +
                " 2 | 2 | 2 | 2 |\n" +
                " 2 | 2 | 2 |21 |\n" +
                " 3 |33 | 3 | 3 |";
        try (ResultSet rs = spliceClassWatcher.executeQuery("select * from t1")) {
            assertEquals(expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
    }

    /* DB-6509
     * It's quite intuitive to write the following query that "where a1=a2" is in the subquery
     * in from list. But there is a subtle difference. The parentheses syntax has the subquery
     * in UPDATE statement level. The FROM syntax, however, has the subquery in a from list.
     * The actual subquery in UPDATE statement level is built from this from list. That means
     * the subquery written by user is not in the adjacent nesting level of T1. Thus, it can't
     * references columns in T1. To make correlations, one has to use the WHERE clause in the
     * UPDATE statement. Check testNestedFromSubquery() for correct queries.
     */
    @Test
    public void testSubtleCorrelationSemanticInFromSyntax() throws Exception {
        try {
            spliceClassWatcher.executeUpdate(format("update t1 set b1 = b2" +
                " from (select b2 from t2 --splice-properties joinStrategy=%s,useSpark=%s\n" +
                " cross join (select a3 from t3) where a1=a2)", this.joinStrategy, this.useSparkString));
        } catch (SQLException e) {
            Assert.assertEquals("42X04", e.getSQLState());
            Assert.assertTrue(e.getMessage().contains("Column 'A1' is either not in any table in the FROM list or appears"));
        }
    }

    @Test
    public void testSelectAsteriskInSubquery() throws Exception {
        try {
            spliceClassWatcher.executeUpdate("update t1 set (b1) = (select * from (select b2 from t2))");
        } catch (SQLException e) {
            Assert.assertEquals("42X38", e.getSQLState());
            Assert.assertTrue(e.getMessage().contains("'SELECT *' only allowed in EXISTS and NOT EXISTS subqueries."));
        }
    }

    @Test
    public void testSubqueryNumberOfColumnsMismatch() throws Exception {
        try {
            spliceClassWatcher.executeUpdate("update t1 set (b1, c1) = (select b2 from t2)");
        } catch (SQLException e) {
            Assert.assertEquals("42X58", e.getSQLState());
            Assert.assertTrue(e.getMessage().contains("The number of columns on the left and right sides of the assignment in set clause must be the same."));
        }
    }

    @Test
    public void testUpdateFromSubquerySyntaxError() throws Exception {
        /* Adding a pair of parentheses to the LHS of the assignment in set clause
         * triggers the parsing rule of
         *     <LEFT_PAREN> column_list <RIGHT_PAREN> <EQ> update_source
         * which is the same semantic as the alternative syntax
         *     UPDATE ... SET ... FROM ... WHERE ...
         * One can choose either of them but not both.
         */
        try {
            spliceClassWatcher.executeUpdate("update t1 set (b1) = b2" +
                    " from t2, t3 where a1 = a2");
        } catch (SQLException e) {
            Assert.assertEquals("42X67", e.getSQLState());
            Assert.assertTrue(e.getMessage().contains("Invalid UPDATE statement syntax."));
        }
    }

}
