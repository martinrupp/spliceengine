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

package com.splicemachine.derby.test.framework;

import com.splicemachine.derby.utils.test.DecoderIT;
import com.splicemachine.homeless.TestUtils;
import com.splicemachine.utils.Pair;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.runner.Description;
import splice.com.google.common.base.Joiner;

import java.io.*;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.*;

public class SpliceUnitTest {

    private static Pattern overallCostP = Pattern.compile("totalCost=[0-9]+\\.?[0-9]*");
    private static Pattern outputRowsP = Pattern.compile("outputRows=[0-9]+\\.?[0-9]*");
    private static Pattern scannedRowsP = Pattern.compile("scannedRows=[0-9]+\\.?[0-9]*");

    /// set the following boolean to true to prevent deletion of temporary files (e.g. for debugging)
    static private final boolean debug_no_delete = false;

    public String getSchemaName() {
        Class<?> enclosingClass = getClass().getEnclosingClass();
        if (enclosingClass != null)
            return enclosingClass.getSimpleName().toUpperCase();
        else
            return getClass().getSimpleName().toUpperCase();
    }

    /**
     * Load a table with given values
     *
     * @param statement calling test's statement that may be in txn
     * @param tableName fully-qualified table name, i.e., <pre>schema.table</pre>
     * @param values list of row values
     * @throws Exception
     */
    public static void loadTable(Statement statement, String tableName, List<String> values) throws Exception {
        for (String rowVal : values) {
            statement.executeUpdate("insert into " + tableName + " values " + rowVal);
        }
    }

    public String getTableReference(String tableName) {
        return getSchemaName() + "." + tableName;
    }

    public String getPaddedTableReference(String tableName) {
        return " " + getSchemaName() + "." + tableName.toUpperCase()+ " ";
    }


    public static int resultSetSize(ResultSet rs) throws Exception {
        int i = 0;
        while (rs.next()) {
            i++;
        }
        return i;
    }

    public static int columnWidth(ResultSet rs ) throws SQLException {
        return rs.getMetaData().getColumnCount();
    }

    public static String format(String format, Object...args) {
        return String.format(format, args);
    }
    public static String getBaseDirectory() {
        String userDir = System.getProperty("user.dir");
        /*
         * The ITs can run in multiple different locations based on the different architectures
         * that are available, but the actual test data files are located in the splice_machine directory; thus,
         * to find the source file, we have to do a little looking around. Implicitely, the ITs run
         * in a sibling directory to splice_machine (like mem_sql or hbase_sql), so we need to make
         * sure that we go up and over to the splice_machine directory.
         *
         * Of course, if we are in the correct location to begin with, then we are good to go.
         */
        if(userDir.endsWith("splice_machine")) return userDir;

        Path nioPath = Paths.get(userDir);
        while(nioPath!=null){
            /*
             * Look for splice_machine in our parent hierarchy. If we can find it, then we are good
             */
            if(nioPath.endsWith("splice_machine")) break;
            nioPath = nioPath.getParent();
        }
        if(nioPath==null){
            /*
             * We did not find it in our parent hierarchy.  It's possible that it's in a child
             * directory of us, so look around at it directly
             */
            Path us = Paths.get(userDir);
            nioPath = Paths.get(us.toString(),"splice_machine");
            if(!Files.exists(nioPath)){
                /* Try to go up and to the left. If it's not
                 * there, then we are screwed anyway, so just go with it
                 */
                Path parent=Paths.get(userDir).getParent();
                if(parent == null) throw new RuntimeException("path " + userDir + " not found");
                nioPath=Paths.get(parent.toString(),"splice_machine");
            }
        }
        return nioPath.toString();
    }

    public static String getResourceDirectory() {
        return getBaseDirectory()+"/src/test/test-data/";
    }

    public static String getHbaseRootDirectory() {
        return getHBaseDirectory()+"/target/hbase";
    }

    public static String getHBaseDirectory() {
        String userDir = System.getProperty("user.dir");
        /*
         * The ITs can run in multiple different locations based on the different architectures
         * that are available, but the actual test data files are located in the splice_machine directory; thus,
         * to find the source file, we have to do a little looking around. Implicitely, the ITs run
         * in a sibling directory to splice_machine (like mem_sql or hbase_sql), so we need to make
         * sure that we go up and over to the splice_machine directory.
         *
         * Of course, if we are in the correct location to begin with, then we are good to go.
         */
        if(userDir.endsWith("platform_it")) return userDir;

        Path nioPath = Paths.get(userDir);
        while(nioPath!=null){
            /*
             * Look for splice_machine in our parent hierarchy. If we can find it, then we are good
             */
            if(nioPath.endsWith("platform_it")) break;
            nioPath = nioPath.getParent();
        }
        if(nioPath==null){
            /*
             * We did not find it in our parent hierarchy.  It's possible that it's in a child
             * directory of us, so look around at it directly
             */
            Path us = Paths.get(userDir);
            nioPath = Paths.get(us.toString(),"platform_it");
            if(!Files.exists(nioPath)){
                /* Try to go up and to the left. If it's not
                 * there, then we are screwed anyway, so just go with it
                 */
                Path parent=Paths.get(userDir).getParent();
                if(parent == null) throw new RuntimeException("path " + userDir + " not found");
                nioPath=Paths.get(parent.toString(),"platform_it");
            }
        }
        return nioPath.toString();
    }

    public static String getHiveWarehouseDirectory() {
        return getHBaseDirectory()+"/user/hive/warehouse";
    }

    public static class MyWatcher extends SpliceTableWatcher {

        public MyWatcher(String tableName, String schemaName, String createString) {
            super(tableName, schemaName, createString);
        }

        public void create(Description desc) {
            super.starting(desc);
        }
    }

    protected void firstRowContainsQuery(String query, String contains,SpliceWatcher methodWatcher) throws Exception {
        rowContainsQuery(1,query,contains,methodWatcher);
    }

    protected void secondRowContainsQuery(String query, String contains,SpliceWatcher methodWatcher) throws Exception {
        rowContainsQuery(2,query,contains,methodWatcher);
    }

    protected void thirdRowContainsQuery(String query, String contains,SpliceWatcher methodWatcher) throws Exception {
        rowContainsQuery(3,query,contains,methodWatcher);
    }

    protected void fourthRowContainsQuery(String query, String contains,SpliceWatcher methodWatcher) throws Exception {
        rowContainsQuery(4,query,contains,methodWatcher);
    }

    protected void rowContainsQuery(int[] levels, String query,SpliceWatcher methodWatcher,String... contains) throws Exception {
        try(ResultSet resultSet = methodWatcher.executeQuery(query)){
            int i=0;
            int k=0;
            while(resultSet.next()){
                i++;
                for(int level : levels){
                    if(level==i){
                        Assert.assertTrue("failed query at level ("+level+"): \n"+query+"\nExpected: "+contains[k]+"\nWas: "
                                +resultSet.getString(1),resultSet.getString(1).contains(contains[k]));
                        k++;
                    }
                }
            }
            for (int level : levels){
                if (level > i) {
                    Assert.fail("Try to compare at level " + level + " but result set has only " + i + " levels.");
                }
            }
        }
    }

    public static void rowContainsQuery(int[] levels, String query,SpliceWatcher methodWatcher,String[]... contains) throws Exception {
        try(ResultSet resultSet = methodWatcher.executeQuery(query)){
            int i=0;
            int k=0;
            while(resultSet.next()){
                i++;
                for(int level : levels){
                    if(level==i){
                        String resultString = resultSet.getString(1);
                        for (String phrase: contains[k]) {
                            Assert.assertTrue("failed query at level (" + level + "): \n" + query + "\nExpected: " + phrase + "\nWas: "
                                    + resultString, resultString.contains(phrase));
                        }
                        k++;
                    }
                }
            }
            if (k < contains.length)
                fail("fail to match the given strings");
        }
    }

    public static void rowContainsQuery(int[] levels, String query, TestConnection conn, String[]... contains) throws Exception {
        try(ResultSet resultSet = conn.query(query)){
            int i=0;
            int k=0;
            while(resultSet.next()){
                i++;
                for(int level : levels){
                    if(level==i){
                        String resultString = resultSet.getString(1);
                        for (String phrase: contains[k]) {
                            Assert.assertTrue("failed query at level (" + level + "): \n" + query + "\nExpected: " + phrase + "\nWas: "
                                    + resultString, resultString.contains(phrase));
                        }
                        k++;
                    }
                }
            }
            if (k < contains.length)
                fail("fail to match the given strings");
        }
    }

    protected void rowContainsCount(int[] levels, String query,SpliceWatcher methodWatcher,double[] counts, double[] deltas ) throws Exception {
        try(ResultSet resultSet = methodWatcher.executeQuery(query)){
            int i=0;
            int k=0;
            while(resultSet.next()){
                i++;
                for(int level : levels){
                    if(level==i){
                        Assert.assertEquals("failed query at level ("+level+"): \n"+query+"\nExpected: "+counts[k]+"\nWas: "
                                        +resultSet.getString(1),
                                counts[k],parseOutputRows(resultSet.getString(1)),deltas[k]);
                        k++;
                    }
                }
            }
        }
    }

    public static String getExplainMessage(int level, String query,SpliceWatcher methodWatcher) throws Exception {
        try(ResultSet resultSet = methodWatcher.executeQuery(query)){
            int i=0;
            int k=0;
            while(resultSet.next()){
                i++;
                if(level==i){
                    return resultSet.getString(1);
                }
            }
        }
        Assert.fail("Missing level: " + level);
        return null;
    }


    protected void rowContainsQuery(int level, String query, String contains, SpliceWatcher methodWatcher) throws Exception {
        try(ResultSet resultSet = methodWatcher.executeQuery(query)){
            for(int i=0;i<level;i++){
                resultSet.next();
            }
            String actualString=resultSet.getString(1);
            String failMessage=String.format("expected result of query '%s' to contain '%s' at row %,d but did not, actual result was '%s'",
                    query,contains,level,actualString);
            Assert.assertTrue(failMessage,actualString.contains(contains));
        }
    }

    protected void columnInRowsContainsQuery(int[] levels, int columnPosition, String query, SpliceWatcher methodWatcher, String... contains) throws Exception {
        try(ResultSet resultSet = methodWatcher.executeQuery(query)){
            int i=0;
            int k=0;
            while(resultSet.next()){
                i++;
                for(int level : levels){
                    if(level==i){
                        Assert.assertTrue("failed query at level ("+level+"): \n"+query+"\nExpected: "+contains[k]+"\nWas: "
                                +resultSet.getString(columnPosition),resultSet.getString(columnPosition).contains(contains[k]));
                        k++;
                    }
                }
            }
            if (k < contains.length)
                fail("fail to match the given strings");
        }
    }


    protected void queryDoesNotContainString(String query, String notContains,SpliceWatcher methodWatcher) throws Exception {
        queryDoesNotContainString(query,new String[] {notContains}, methodWatcher, false);
    }

    protected void queryDoesNotContainString(String query, String[] notContains,SpliceWatcher methodWatcher, boolean caseInsensitive) throws Exception {
        ResultSet rs = null;
        try {
            rs = methodWatcher.executeQuery(query);
            String matchString = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
            if (caseInsensitive)
                matchString = matchString.toUpperCase();

            for (String str: notContains) {
                if (caseInsensitive)
                    str = str.toUpperCase();
                if (matchString.contains(str))
                    fail("ResultSet should not contain string: " + str);
            }
        }
        finally {
            if (rs != null)
                rs.close();
        }
    }

    public static void rowsContainsQuery(String query, Contains mustContain, SpliceWatcher methodWatcher) throws Exception {
        ResultSet resultSet = methodWatcher.executeQuery(query);
        int i = 0;
        for (Pair<Integer,String> p : mustContain.get()) {
            for (; i< p.getFirst();i++)
                resultSet.next();
            Assert.assertTrue("failed query: " + query + " -> " + resultSet.getString(1), resultSet.getString(1).contains(p.getSecond()));
        }
    }


    public static double parseTotalCost(String planMessage) {
        Matcher m1 = overallCostP.matcher(planMessage);
        Assert.assertTrue("No Overall cost found!", m1.find());
        return Double.parseDouble(m1.group().substring("totalCost=".length()));
    }

    public static double parseOutputRows(String planMessage) {
        Matcher m1 = outputRowsP.matcher(planMessage);
        Assert.assertTrue("No OutputRows found!", m1.find());
        return Double.parseDouble(m1.group().substring("outputRows=".length()));
    }

    public static double parseScannedRows(String planMessage) {
        Matcher m1 = scannedRowsP.matcher(planMessage);
        Assert.assertTrue("No ScannedRows found!", m1.find());
        return Double.parseDouble(m1.group().substring("scannedRows=".length()));
    }

    protected void runQuery(String sqlText, SpliceWatcher methodWatcher) throws Exception {
        try (ResultSet rs = methodWatcher.executeQuery(sqlText)) {
        }
    }

    protected void testQuery(String sqlText, String expected, SpliceWatcher methodWatcher) throws Exception {
        try (ResultSet rs = methodWatcher.executeQuery(sqlText)) {
            assertEquals("\n" + sqlText + "\n", expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
    }

    protected void testQuery(String sqlText, String expected, Statement s) throws Exception {
        try (ResultSet rs = s.executeQuery(sqlText)) {
            assertEquals("\n" + sqlText + "\n", expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
    }

    protected void testQuery(String sqlText, String expected, TestConnection c) throws Exception {
        try (ResultSet rs = c.query(sqlText)) {
            assertEquals("\n" + sqlText + "\n", expected, TestUtils.FormattedResult.ResultFactory.toString(rs));
        }
    }

    protected void testQueryContains(String sqlText, String containedString, Statement s) throws Exception {
        ResultSet rs = null;
        try {
            rs = s.executeQuery(sqlText);
            if (!TestUtils.FormattedResult.ResultFactory.toString(rs).contains(containedString))
                fail("ResultSet does not contain string: " + containedString);
        }
        finally {
            if (rs != null)
                rs.close();
        }
    }

    protected void testQueryContains(String sqlText, String containedString,
                                     SpliceWatcher methodWatcher) throws Exception {
        testQueryContains(sqlText, containedString, methodWatcher, true);
    }

    protected void testQueryContains(String sqlText, String containedString,
                                     SpliceWatcher methodWatcher,
                                     boolean caseInsensitive) throws Exception {
        ResultSet rs = null;
        try {
            rs = methodWatcher.executeQuery(sqlText);
            String matchString = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
            if (caseInsensitive) {
                matchString = matchString.toUpperCase();
                containedString = containedString.toUpperCase();
            }
            if (!matchString.contains(containedString))
                fail("ResultSet does not contain string: " + containedString + "\nResult set:\n"+matchString);
        }
        finally {
            if (rs != null)
                rs.close();
        }
    }

    protected void testQueryContains(String sqlText, List<String> containedStrings,
                                     SpliceWatcher methodWatcher,
                                     boolean caseInsensitive) throws Exception {
        ResultSet rs = null;
        try {
            rs = methodWatcher.executeQuery(sqlText);
            String matchString = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
            if (caseInsensitive)
                matchString = matchString.toUpperCase();

            for (String containedString:containedStrings) {
                if (caseInsensitive)
                    containedString = containedString.toUpperCase();
                if (matchString.contains(containedString))
                    return;
            }

            fail("ResultSet does not contain any of the expected strings.");
        }
        finally {
            if (rs != null)
                rs.close();
        }
    }

    protected void testPreparedQuery(String sqlText, SpliceWatcher methodWatcher,
                                     String expected, List<Integer> paramList) throws Exception {
        try(PreparedStatement ps = methodWatcher.prepareStatement(sqlText)) {
            int i = 1;
            for (int param : paramList) {
                ps.setInt(i++, param);
            }

            try(ResultSet rs = ps.executeQuery()) {
                assertEquals("\n" + sqlText + "\n", expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
            }
        }
    }

    protected void testExplainContains(String sqlText,
                                       SpliceWatcher methodWatcher,
                                       List<String> containedStrings,
                                       List<String> notContainedStrings) throws Exception {
        String explainQuery = "explain " + sqlText;
        try (ResultSet rs = methodWatcher.executeQuery(explainQuery)){
            String explainText = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
            if (containedStrings != null)
                for (String containedString : containedStrings) {
                    assertTrue(format("\n" + explainQuery + "\n\n" + "Expected to contain: %s\n", containedString),
                        explainText.contains(containedString));
                }
            if (notContainedStrings != null)
                for (String notContainedString : notContainedStrings) {
                    assertTrue(format("\n" + explainQuery + "\n\n" + "Expected not to contain: %s\n", notContainedString),
                        !explainText.contains(notContainedString));
                }
        }
    }

    protected void testParameterizedExplainContains(String sqlText,
                                     SpliceWatcher methodWatcher,
                                     List<String> containedStrings,
                                     List<String> notContainedStrings,
                                     List<Integer> paramList) throws Exception {

        String explainQuery = "explain " + sqlText;
        try(PreparedStatement ps = methodWatcher.prepareStatement(explainQuery)) {
            int i = 1;
            for (int param : paramList) {
                ps.setInt(i++, param);
            }
            try(ResultSet rs = ps.executeQuery()) {
                String explainText = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
                if (containedStrings != null)
                    for (String containedString : containedStrings) {
                        assertTrue(format("\n" + explainQuery + "\n\n" + "Expected to contain: %s\n", containedString),
                            explainText.contains(containedString));
                    }
                if (notContainedStrings != null)
                    for (String notContainedString : notContainedStrings) {
                        assertTrue(format("\n" + explainQuery + "\n\n" + "Expected not to contain: %s\n", notContainedString),
                            !explainText.contains(notContainedString));
                    }
            }
        }
    }

    protected void testQueryDoesNotContain(String sqlText, String containedString,
                                           SpliceWatcher methodWatcher,
                                           boolean caseInsensitive) throws Exception {
        ResultSet rs = null;
        try {
            rs = methodWatcher.executeQuery(sqlText);
            String matchString = TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
            if (caseInsensitive) {
                matchString = matchString.toUpperCase();
                containedString = containedString.toUpperCase();
            }
            if (matchString.contains(containedString))
                fail("ResultSet does not contain string: " + containedString);
        }
        finally {
            if (rs != null)
                rs.close();
        }
    }

    protected void testQueryUnsorted(String sqlText, String expected, SpliceWatcher methodWatcher) throws Exception {
        ResultSet rs = null;
        try {
            rs = methodWatcher.executeQuery(sqlText);
            assertEquals("\n" + sqlText + "\n", expected, TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs));
        }
        finally {
            if (rs != null)
                rs.close();
        }
    }

    protected void testFail(String sqlText,
                            List<String> expectedErrors,
                            SpliceWatcher methodWatcher) throws Exception {
        try (ResultSet rs = methodWatcher.executeQuery(sqlText)) {
            String failMsg = format("Query not expected to succeed.\n%s", sqlText);
            fail(failMsg);
        }
        catch (Exception e) {
            boolean found = expectedErrors.contains(e.getMessage());
            if (!found)
                fail(format("\n + Query {%s} failed with an unexpected error message: {%s}.\n", sqlText, e.getMessage()));
        }
    }

    protected void testUpdateFail(String sqlText,
                                  List<String> expectedErrors,
                                  SpliceWatcher methodWatcher) throws AssertionError {

        try {
            methodWatcher.executeUpdate(sqlText);
            String failMsg = format("Update not expected to succeed.\n%s", sqlText);
            fail(failMsg);
        }
        catch (Exception e) {
            String msg = e.getMessage().split("\n")[0];
            boolean found = expectedErrors.contains(msg);
            if (!found)
                fail(format("\n + Unexpected error message: %s + \n", e.getMessage()));
        }
    }

    protected void testFail(String sqlText,
                            List<String> expectedErrors,
                            Statement s) throws Exception {
        try {
            s.execute(sqlText);
            String failMsg = format("Query not expected to succeed.\n%s", sqlText);
            fail(failMsg);
        }
        catch (Exception e) {
            String msg = e.getMessage().split("\n")[0];
            boolean found = expectedErrors.contains(msg);
            if (!found)
                fail(format("\n + Unexpected error message: %s + \n", e.getMessage()));
        }
    }

    protected void testFail(String expectedErrorCode,
                            String sqlText,
                            Statement s) throws AssertionError {

        try {
            s.execute(sqlText);
            String failMsg = format("SQL not expected to succeed.\n%s", sqlText);
            fail(failMsg);
        }
        catch (Exception e) {
            boolean found = false;
            String extraText = "";
            if (e instanceof SQLException) {
                found = ((SQLException) e).getSQLState().equals(expectedErrorCode);
                if (!found)
                    extraText = format("found error code: %s", ((SQLException) e).getSQLState());
            }
            if (!found) {
                fail(format("\n + Expected error code: %s, ", expectedErrorCode) + extraText);
            }
        }
    }

    protected void testFail(String expectedErrorCode, String sqlText, SpliceWatcher methodWatcher) throws Exception {
        ResultSet rs = null;
        try {
            rs = methodWatcher.executeQuery(sqlText);
            String failMsg = format("SQL not expected to succeed.\n%s", sqlText);
            fail(failMsg);
        }
        catch (Exception e) {
            boolean found = false;
            String extraText = "";
            if (e instanceof SQLException) {
                found = ((SQLException) e).getSQLState().equals(expectedErrorCode);
                if (!found)
                    extraText = format("found error code: %s", ((SQLException) e).getSQLState());
            }
            if (!found) {
                fail(format("\n + Expected error code: %s, ", expectedErrorCode) + extraText);
            }
        }
        finally {
            if (rs != null)
                rs.close();
        }
    }

    protected void assertStatementError(String expectedErrorCode,
                                        Statement s,
                                        String sqlText) throws AssertionError {
        testFail(expectedErrorCode, sqlText, s);
    }

    /**
     * Assert that the number of rows in a table is an expected value.
     * Query uses a SELECT COUNT(*) FROM table.
     *
     * @param table Name of table in current schema, will be quoted
     * @param rowCount Number of rows expected in the table
     * @throws SQLException Error accessing the database.
     */
    public void assertTableRowCount(String table, int rowCount, Statement s)
            throws SQLException, AssertionError
    {
        try( ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table) ) {
            rs.next();
            assertEquals(table + " row count:",
                    rowCount, rs.getInt(1));
        }
    }

    public static void assertUpdateCount(Statement st,
                                         int expectedRC, String sql) throws SQLException, AssertionError
    {
        assertEquals("Update count does not match:",
                expectedRC, st.executeUpdate(sql));
    }

    public static class Contains {
        private List<Pair<Integer,String>> rows = new ArrayList<>();

        public Contains add(Integer row, String shouldContain) {
            rows.add(new Pair<>(row, shouldContain));
            return this;
        }

        public List<Pair<Integer,String>> get() {
            Collections.sort(this.rows, new Comparator<Pair<Integer, String>>() {
                @Override
                public int compare(Pair<Integer, String> p1, Pair<Integer, String> p2) {
                    return p1.getFirst().compareTo(p2.getFirst());
                }
            });
            return this.rows;
        }
    }

    protected static void importData(SpliceWatcher methodWatcher, String schema,String tableName, String fileName) throws Exception {
        String file = SpliceUnitTest.getResourceDirectory()+ fileName;
        String sql = String.format("call SYSCS_UTIL.IMPORT_DATA('%s','%s','%s','%s',',',null,null,null,null,1,null,true,'utf-8')", schema, tableName, null, file);
        methodWatcher.execute(sql);
    }

    protected static void validateImportResults(ResultSet resultSet, int good,int bad) throws SQLException {
        Assert.assertTrue("No rows returned!",resultSet.next());
        Assert.assertEquals("Incorrect number of files reported!",1,resultSet.getInt(3));
        Assert.assertEquals("Incorrect number of rows reported!",good,resultSet.getInt(1));
        Assert.assertEquals("Incorrect number of bad records reported!", bad, resultSet.getInt(2));
    }

    protected static void validateMergeResults(ResultSet resultSet, int affected, int updated,int inserted, int bad) throws SQLException {
        Assert.assertTrue("No rows returned!",resultSet.next());
        Assert.assertEquals("Incorrect number of rows reported!",affected,resultSet.getInt(1));
        Assert.assertEquals("Incorrect number of rows reported!",updated,resultSet.getInt(2));
        Assert.assertEquals("Incorrect number of rows reported!",inserted,resultSet.getInt(3));
        Assert.assertEquals("Incorrect number of files reported!",1,resultSet.getInt(5));
        Assert.assertEquals("Incorrect number of bad records reported!", bad, resultSet.getInt(4));
    }

    protected static void validateMergeResults(ResultSet resultSet, int affected, int updated,int inserted, int bad, int file) throws SQLException {
        Assert.assertTrue("No rows returned!",resultSet.next());
        Assert.assertEquals("Incorrect number of rows reported!",affected,resultSet.getInt(1));
        Assert.assertEquals("Incorrect number of rows reported!",updated,resultSet.getInt(2));
        Assert.assertEquals("Incorrect number of rows reported!",inserted,resultSet.getInt(3));
        Assert.assertEquals("Incorrect number of files reported!",file,resultSet.getInt(5));
        Assert.assertEquals("Incorrect number of bad records reported!", bad, resultSet.getInt(4));
    }

    public static String printMsgSQLState(String testName, SQLException e) {
        // useful for debugging import errors
        StringBuilder buf =new StringBuilder(testName);
        buf.append("\n");
        int i =1;
        SQLException child = e;
        while (child != null) {
            buf.append(i++).append(" ").append(child.getSQLState()).append(" ")
                    .append(child.getLocalizedMessage()).append("\n");
            child = child.getNextException();
        }
        return buf.toString();
    }

    public static File createBadLogDirectory(String schemaName) {
        File badImportLogDirectory = new File(getHBaseDirectory() + "/target/BAD/" + schemaName);
        if (badImportLogDirectory.exists()) {
            recursiveDelete(badImportLogDirectory);
        }
        assertTrue("Couldn't create "+badImportLogDirectory,badImportLogDirectory.mkdirs());
        assertTrue("Failed to create "+badImportLogDirectory,badImportLogDirectory.exists());
        return badImportLogDirectory;
    }

    public static File createBulkLoadDirectory(String schemaName) {
        File bulkLoadDirectory = new File(getHBaseDirectory() + "/target/HFILE/" + schemaName);
        if (bulkLoadDirectory.exists()) {
            recursiveDelete(bulkLoadDirectory);
        }
        assertTrue("Couldn't create " + bulkLoadDirectory, bulkLoadDirectory.mkdirs());
        assertTrue("Failed to create " + bulkLoadDirectory, bulkLoadDirectory.exists());
        return bulkLoadDirectory;
    }

    public static File createBackupDirectory() {
        File backupDirectory = new File(getHBaseDirectory() + "/target/backup/");
        if (!backupDirectory.exists()) {
            assertTrue("Couldn't create " + backupDirectory, backupDirectory.mkdirs());
            assertTrue("Failed to create " + backupDirectory, backupDirectory.exists());
        }
        return backupDirectory;
    }

    public static void recursiveDelete(File file) {
        if (file != null) {
            File[] directoryFiles = file.listFiles();
            if (directoryFiles != null) {
                for (File aFile : directoryFiles) {
                    if (aFile.isDirectory()) {
                        recursiveDelete(aFile);
                    } else {
                        assertTrue("Couldn't delete " + aFile, aFile.delete());
                    }
                }
            }
            assertTrue("Couldn't delete "+file,file.delete());
        }
    }

    public static File createImportFileDirectory(String schemaName) {
        File importFileDirectory = new File(getHBaseDirectory() + "/target/import_data/" + schemaName);
        if (importFileDirectory.exists()) {
            //noinspection ConstantConditions
            File[] files = importFileDirectory.listFiles();
            if( files != null ) {
                for (File file : files) {
                    if (file != null)
                        assertTrue("Couldn't create " + file, file.delete());
                }
            }
            assertTrue("Couldn't create "+importFileDirectory,importFileDirectory.delete());
        }
        assertTrue("Couldn't create " + importFileDirectory, importFileDirectory.mkdirs());
        assertTrue("Failed to create "+importFileDirectory,importFileDirectory.exists());
        return importFileDirectory;
    }

    public static void assertBadFileContainsError(File directory, String importFileName,
                                                  String errorCode, String errorMsg) throws IOException {
        printBadFile(directory, importFileName, errorCode, errorMsg, true);
    }

    public static String printBadFile(File directory, String importFileName) throws IOException {
        return printBadFile(directory, importFileName, null, null, false);
    }

    public static String printBadFile(File directory, String importFileName, String errorCode, String errorMsg, boolean assertTrue) throws IOException {
        // look for file in the "baddir" directory with same name as import file ending in ".bad"
        String badFile = getBadFile(directory, importFileName);
        boolean exists = existsBadFile(directory, importFileName);
        if (exists) {
            List<String> badLines = Files.readAllLines((new File(directory, badFile)).toPath(), Charset.defaultCharset());
            if (errorCode != null && ! errorCode.isEmpty()) {
                // make sure at least one error entry contains the errorCode
                boolean found = false;
                Set<String> codes = new HashSet<>();
                for (String line : badLines) {
                    addCode(line, codes);
                    if (line.startsWith(errorCode)) {
                        found = true;
                        if (assertTrue && errorMsg != null) {
                            assertThat("Incorrect error message!", line, containsString(errorMsg));
                        }
                        break;
                    }
                }
                if (! found && assertTrue) {
                    fail("Didn't find expected SQLState '"+errorCode+"' in bad file: "+badFile+" Found: "+codes);
                }
            }
            return "Error file contents: "+badLines.toString();
        } else if (assertTrue) {
            fail("Bad file ["+badFile+"] does not exist.");
        }
        return "File does not exist: "+badFile;
    }

    private static void addCode(String line, Set<String> codes) {
        if (line != null && ! line.isEmpty()) {
            String[] parts = line.split("\\s");
            if (parts.length > 0) {
                codes.add(parts[0]);
            }
        }
    }

    public static String printImportFile(File directory, String fileName) throws IOException {
        File file = new File(directory, fileName);
        if (file.exists()) {
            List<String> badLines = new ArrayList<>();
            for(String line : Files.readAllLines(file.toPath(), Charset.defaultCharset())) {
                badLines.add("{" + line + "}");
            }
            return "File contents: "+badLines.toString();
        }
        return "File does not exist: "+file.getCanonicalPath();
    }


    public static SpliceUnitTest.TestFileGenerator generatePartialRow(File directory, String fileName, int size,
                                                                      List<int[]> fileData) throws IOException {
        SpliceUnitTest.TestFileGenerator generator = new SpliceUnitTest.TestFileGenerator(directory, fileName);
        try {
            for (int i = 0; i < size; i++) {
                int[] row = {i, 0}; //0 is the value sql chooses for null entries
                fileData.add(row);
                generator.row(row);
            }
        } finally {
            generator.close();
        }
        return generator;
    }

    public static SpliceUnitTest.TestFileGenerator generateFullRow(File directory, String fileName, int size,
                                                                   List<int[]> fileData,
                                                                   boolean duplicateLast) throws IOException {
        return generateFullRow(directory,fileName,size,fileData,duplicateLast,false);
    }

    public static SpliceUnitTest.TestFileGenerator generateFullRow(File directory, String fileName, int size,
                                                                   List<int[]> fileData,
                                                                   boolean duplicateLast, boolean emptyLast) throws IOException {
        SpliceUnitTest.TestFileGenerator generator = new SpliceUnitTest.TestFileGenerator(directory, fileName);
        try {
            for (int i = 0; i < size; i++) {
                int[] row = {i, 2 * i};
                fileData.add(row);
                generator.row(row);
            }
            if (duplicateLast) {
                int[] row = {size - 1, 2 * (size - 1)};
                fileData.add(row);
                generator.row(row);
            }
            if (emptyLast) {
                generator.row(new String[]{""});
            }
        } finally {
            generator.close();
        }
        return generator;
    }

    public static boolean existsBadFile(File badDir, String prefix) {
        String[] files = badDir.list();
        if( files == null ) return false;
        for (String file : files) {
            if (file.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static String getBadFile(File badDir, String prefix) {
        String[] files = badDir.list();
        if( files == null ) return null;
        for (String file : files) {
            if (file.startsWith(prefix)) {
                return file;
            }
        }
        return null;
    }

    public static List<String> getAllBadFiles(File badDir, String prefix) {
        List<String> badFiles = new ArrayList<>();
        String[] files = badDir.list();
        if( files == null ) return badFiles;
        for (String file : files) {
            if (file.startsWith(prefix)) {
                badFiles.add(file);
            }
        }
        return badFiles;
    }

    /**
     * System to generate fake data points into a file. This way we can write out quick,
     * well known files without storing a bunch of extras anywhere.
     *
     * @author Scott Fines
     *         Date: 10/20/14
     */
    public static class TestFileGenerator implements Closeable {
        private final String fileName;
        private final File file;
        private BufferedWriter writer;
        private final Joiner joiner;

        public TestFileGenerator(File directory, String fileName) throws IOException {
            this.fileName = fileName+".csv";
            this.file = new File(directory, this.fileName);
            this.writer =  new BufferedWriter( (new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)));
            this.joiner = Joiner.on(",");
        }

        public TestFileGenerator row(String[] row) throws IOException {
            String line = joiner.join(row)+"\n";
            writer.write(line);
            return this;
        }

        public TestFileGenerator row(int[] row) throws IOException {
            String[] copy = new String[row.length];
            for(int i=0;i<row.length;i++){
                copy[i] = Integer.toString(row[i]);
            }
            return row(copy);
        }

        public String getFileName(){
            return this.fileName;
        }

        public String getFilePath() throws IOException {
            return file.getCanonicalPath();
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }

    public static class ResultList {
        private List<List<String>> resultList = new ArrayList<>();
        private List<List<String>> expectedList = new ArrayList<>();

        public static ResultList create() {
            return new ResultList();
        }

        public ResultList toFileRow(String... values) {
            if (values != null && values.length > 0) {
                resultList.add(Arrays.asList(values));
            }
            return this;
        }

        public ResultList expected(String... values) {
            if (values != null && values.length > 0) {
                expectedList.add(Arrays.asList(values));
            }
            return this;
        }

        public ResultList fill(PrintWriter pw) {
            for (List<String> row : resultList) {
                pw.println(Joiner.on(",").join((row)));
            }
            return this;
        }

        public int nRows() {
            return resultList.size();
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder();
            for (List<String> row : expectedList) {
                buf.append(row.toString()).append("\n");
            }
            return buf.toString();
        }
    }

    public static String getJarFileForClass(Class clazz) throws Exception {
        if (clazz == null)
            return null;
        URL jarURL = clazz.getProtectionDomain().getCodeSource().getLocation();
        if (jarURL == null)
            return null;
        return jarURL.toURI().getPath();
    }

    public static void assertFailed(Connection connection, String sql, String errorState) {
        try(Statement s = connection.createStatement())
        {
            s.execute(sql);
            fail("Did not fail");
        } catch (Exception e) {
            assertTrue("Incorrect error type: " + e.getClass().getName(), e instanceof SQLException);
            SQLException se = (SQLException) e;
            assertTrue("Incorrect error state: " + se.getSQLState() + ", expected: " + errorState,  errorState.startsWith( se.getSQLState()));
        }
    }

    public static File createTempDirectory(String subpath) throws java.io.IOException
    {
        File path = new File(getHBaseDirectory(), "/target/tmp/" + subpath);
        FileUtils.deleteDirectory(path); // clean directory
        if( !path.mkdirs() )
            throw new RuntimeException("couldn't create " + path.toString());
        return path;
    }

    public static void deleteTempDirectory(File tempDir) throws Exception
    {
        if( tempDir == null )
            return;
        else if( debug_no_delete )
            System.out.println( "Not deleting test temporary directory " + tempDir );
        else {
            FileUtils.deleteDirectory(tempDir);
        }
    }

    /// copy subfolder of resource directory (i.e. GITROOT/splice_machine/src/test/test-data/)
    /// to temporary directory @sa getTempOutputDirectory
    /// @return destination directory as string
    public String getTempCopyOfResourceDirectory( File tmpDir, String subfolder ) throws Exception
    {
        File src = new File( SpliceUnitTest.getResourceDirectory(), subfolder);
        File dest = new File( tmpDir, subfolder );
        FileUtils.copyDirectory( src, dest );
        return dest.toString();
    }

    /// @return number of files in directory tree
    // recursively, going down directories, but not counting directories as files
    public static int getNumberOfFiles(String dirPath)
    {
        int count = 0;
        File f = new File(dirPath);
        File[] files = f.listFiles();

        if (files == null)
            return 0;

        for (File file : files)
        {
            count++;
            if (file.isDirectory()) {
                count += getNumberOfFiles(file.getAbsolutePath());
            }
        }
        return count;
    }

    public static void sqlExpectException(Connection conn, String sqlText, String expectedState, boolean update) {
        try ( Statement s = conn.createStatement() ){
            if( update )
                s.executeUpdate(sqlText);
            else {
                ResultSet rs = s.executeQuery(sqlText);
                rs.close();
            }
            Assert.fail("Exception not thrown for " + sqlText);

        } catch (SQLException e) {
            Assert.assertEquals("Wrong Exception for " + sqlText + ". " + e, expectedState, e.getSQLState());
        } catch (Exception e) {
            Assert.assertEquals("Wrong Exception for " + sqlText + ". " + e, expectedState, e.getClass().getName());
        }
    }

    /// execute sql query 'sqlText' and expect an exception of certain state being thrown
    public static void sqlExpectException(SpliceWatcher methodWatcher, String sqlText, String expectedState, boolean update)
    {
        sqlExpectException( methodWatcher.getOrCreateConnection(), sqlText, expectedState, update );
    }

    public static void assertThrows(Runnable r, String expectedExceptionStr) {
        try {
            r.run();
            Assert.fail("expected exception");
        } catch(Exception e) {
            Assert.assertEquals(expectedExceptionStr, e.toString());
        }
    }

    /// execute sql query 'sqlText' and expect a certain formatted result
    public static void sqlExpectToString(SpliceWatcher methodWatcher, String sqlText, String expected, boolean sort ) throws Exception
    {
        try( ResultSet rs = methodWatcher.executeQuery(sqlText) ) {
            String val = sort
                    ? TestUtils.FormattedResult.ResultFactory.toString(rs)
                    : TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
            Assert.assertEquals(sqlText, expected, val);
        }
    }


    /**
     * check if there's any transactions still open
     * if getOldestActiveTransaction is two times the same in a row, there's a stale transaction
     * otherwise, we will get every time different ids since also the CALL SYSUTIL_... will open a mini-transaction
     *
     * Note that this is only correct if the test ends and nothing else is running anymore
     * (e.g. @Category({SerialTest.class}) ). Because of this, the default should be failOnError = false.
     * @param failOnError if true, fail on error (don't do this on parallel tests). otherwise, just LOG.
     */
    public static void waitForStaleTransactions(SpliceWatcher methodWatcher, String name, int numSeconds,
                                                boolean failOnError)
    {
        Logger LOG = Logger.getLogger("SpliceUnitTest");
        try {
            long oldest1 = getOldestActiveTransaction(methodWatcher);
            LOG.info("checking for stale transactions");
            for (int i = 0; i < numSeconds; i++) {
                long oldest2 = getOldestActiveTransaction(methodWatcher);
                if (oldest1 != oldest2)
                    return;
                Thread.sleep(1000);
                oldest1 = oldest2;
            }
            String oldestMsg = "Oldest txn: " + oldest1;
            if (failOnError) {
                Assert.fail(name + " failed to close all transactions. " + oldestMsg);
            } else {
                // if you see this error, this is a hint that something might be left open, especially if you see
                // multiple of these messages. turn failOnError=true and check tests one by one.
                LOG.info("WARNING: " + name + " failed to close all transactions. This might be due to multiple " +
                        "tests running in parallel. " + oldestMsg);
            }
        } catch( SQLException e)
        {
            SQLException next = e.getNextException();
            if (next != null) {
                e = next;
            }
            if (e.toString().equals("java.sql.SQLNonTransientConnectionException: Java exception: " +
                    "'java.lang.UnsupportedOperationException: Operation not supported in Mem profile: java.io.IOException'.")) {
                return;
            }
            else {
                LOG.info("WARNING: Couldn't execute SYSCS_GET_OLDEST_ACTIVE_TRANSACTION: " + e.toString());
            }
        }
        catch( Exception e)
        {
            // some tests might have closed the database (RestoreIT)
            LOG.info("WARNING: Couldn't execute SYSCS_GET_OLDEST_ACTIVE_TRANSACTION: " + e.toString());
        }
    }

    public static long getOldestActiveTransaction(SpliceWatcher methodWatcher) throws SQLException {
        try (ResultSet rs = methodWatcher.executeQuery("call SYSCS_UTIL.SYSCS_GET_OLDEST_ACTIVE_TRANSACTION()"))
        {
            Assert.assertEquals(true, rs.next());
            return rs.getLong(1);
        }
    }

    @AfterClass
    public static void waitForStaleTransactions() {
        SpliceWatcher methodWatcher = new SpliceWatcher(null);
        // for parallel running tests waitForStaleTransactions might report false positives,
        // but you can set this to true if you're checking the tests one by one manually.
        boolean failOnError = false;
        waitForStaleTransactions(methodWatcher, "Test", 5, failOnError);
        methodWatcher.closeAll();
    }

    public static String getSqlItJarFile()
    {
        String[] paths = {
                System.getProperty("user.dir")+"/target/sql-it/sql-it.jar",
                System.getProperty("user.dir")+"/../platform_it/target/sql-it/sql-it.jar",
                System.getProperty("user.dir")+"/../mem_sql/target/sql-it/sql-it.jar",
        };
        for(String path : paths ) {
            if( new File(path).exists())
                return path;
        }
        String err = "Couldn't find procedures jar file in either of " + Arrays.toString(paths);
        Assert.fail(err);
        throw new RuntimeException(err);
    }

    protected void checkDecfloatExpression(String input, String output, TestConnection conn) throws SQLException {
        String sql = format("select %s", input);
        try(ResultSet rs = conn.query(sql)) {
            rs.next();
            Assert.assertTrue(format("expected: %s, actual: %s", output, rs.getBigDecimal(1)),
                    new BigDecimal(output, MathContext.DECIMAL128).compareTo(rs.getBigDecimal(1)) == 0);
        }
    }

    protected void checkBooleanExpression(String input, boolean output, TestConnection conn) throws SQLException {
        String sql = format("select %s", input);
        try(ResultSet rs = conn.query(sql)) {
            rs.next();
            Assert.assertEquals(output, rs.getBoolean(1));
        }
    }

    protected void checkVarbitExpression(String input, byte[] output, TestConnection conn) throws SQLException {
        String sql = format("select %s", input);
        try(ResultSet rs = conn.query(sql)) {
            rs.next();
            Assert.assertArrayEquals(output, rs.getBytes(1));
        }
    }

    protected void checkNullExpression(String input, TestConnection conn) throws SQLException {
        String sql = format("select %s", input);
        try (ResultSet rs = conn.query(sql)) {
            rs.next();
            Assert.assertNull(rs.getObject(1));
        }
    }

    protected void checkExpressionType(String input, String expectedType, TestConnection conn) throws SQLException {
        String sql = format("select typeof(%s)", input);
        try(ResultSet rs = conn.query(sql)) {
            rs.next();
            Assert.assertEquals(expectedType, rs.getString(1));
        }
    }

    protected void checkStringExpression(String input, String expectedOutput, TestConnection conn) throws SQLException {
        String sql = format("select %s", input);
        try(ResultSet rs = conn.query(sql)) {
            rs.next();
            Assert.assertEquals(expectedOutput, rs.getString(1));
        }
    }

    protected void checkIntExpression(String input, int expectedOutput, TestConnection conn) throws SQLException {
        String sql = addPotentiallyMissingSelect(input);
        try(ResultSet rs = conn.query(sql)) {
            rs.next();
            Assert.assertEquals(expectedOutput, rs.getInt(1));
        }
    }

    protected void checkIntExpression(String input, int expectedOutput, SpliceWatcher methodWatcher) throws SQLException {
        checkIntExpression(input, expectedOutput, methodWatcher.getOrCreateConnection());
    }

    private String addPotentiallyMissingSelect(String input) {
        if (!input.toLowerCase().startsWith("select")) {
            return format("select %s", input);
        } else {
            return input;
        }
    }

    public static boolean isMemPlatform(SpliceWatcher watcher) throws Exception{
        try (ResultSet rs = watcher.executeQuery("CALL SYSCS_UTIL.SYSCS_IS_MEM_PLATFORM()")) {
            rs.next();
            return ((Boolean)rs.getObject(1));
        }
    }

    public interface ThrowingBiConsumer<T, U> extends BiConsumer<T, U> {
        @Override
        default void accept(final T t, final U u) {
            try {
                acceptThrows(t, u);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        }

        void acceptThrows(T t, U u) throws Exception;
    }

    protected void runOltpOlapOlapNativeSpark(ThrowingBiConsumer<Boolean, Boolean> consumer) {
        consumer.accept(false, false);
        consumer.accept(true, false);
        consumer.accept(true, true);
    }



    /**
     * matching regexp with multiple lines by splitting the input string and the result string and
     * comparing individually
     * @param in the input multiline string
     * @param expectedOutRegex the multiline string that is used to match the input
     */
    public static void matchMultipleLines(String in, String expectedOutRegex) {
        String[] o2 = in.split("\n");
        String[] ex2 = expectedOutRegex.split("\n");
        Assert.assertEquals(in + "\n---\n" + expectedOutRegex, o2.length, ex2.length);
        for(int i =0; i<o2.length; i++) {
            Assert.assertTrue("\n" + o2[i] + "\n--------------\ndoesn't match\n--------------\n" + ex2[i], o2[i].matches(ex2[i]));
        }
    }

    /**
     * creates a regexp where all characters are escaped and § is mapped to .*, so that
     * when we have a result with a variable result like with a conglomerate id
     * | 347893 | MY_TABLE |
     * we can use
     * |§| MY_TABLE |
     * to match the result
     * @param asteriskFilter
     * @return escaped regexp String, and § mapped to .*
     */
    public static String escapeRegexp(String asteriskFilter)
    {
        String filter = asteriskFilter;
        String toEscape[] = {"\\", "<", "(", "[", "{", "^", "=", "$", "*", "!", "|", "]", "}", ")", "+", ".", ">", "?"};
        for(String s : toEscape) {
            filter = filter.replace(s, "\\" + s);
        }

        filter = filter.replace("§", ".*");
        return filter;
    }
}
