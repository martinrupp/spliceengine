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

import com.splicemachine.homeless.TestUtils;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import splice.com.google.common.collect.Lists;
import splice.com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static splice.com.google.common.base.Strings.isNullOrEmpty;

/**
 * A TestWatcher that provides Connections, Statements, and ResultSets and then closes them when finished() is called.
 *
 * Not thread-safe, synchronize externally if using in a multi-threaded test case.
 */
public class SpliceWatcher extends TestWatcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SpliceWatcher.class);
    private static ExecutorService executor = Executors.newCachedThreadPool(
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("connection-close-%d").build());

    private TestConnection currentConnection;
    private final String defaultSchema;

    /* Collections below can be accessed concurrently when a @Test(timeout=) annotated test fails. This
     * class is NOT meant to be thread safe-- we use concurrent structures here just so that we don't
     * obscure @Test(timeout=) related exceptions with ConcurrentModification exceptions */

    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final List<Statement> statements = new CopyOnWriteArrayList<>();
    private final List<ResultSet> resultSets = new CopyOnWriteArrayList<>();

    public SpliceWatcher() {
        this((String)null);
    }

    public SpliceWatcher(String defaultSchema) {
        this.defaultSchema = defaultSchema == null ? null : defaultSchema.toUpperCase();
    }

    @Override
    public void close() throws Exception {
        closeAll();
    }

    public class ConnectionBuilder {
        SpliceNetConnection.ConnectionBuilder delegate = SpliceNetConnection.newBuilder();
        boolean schemaSet = false;

        public ConnectionBuilder host(String host) {
            delegate.host(host);
            return this;
        }
        public ConnectionBuilder port(int port) {
            delegate.port(port);
            return this;
        }
        public ConnectionBuilder database(String database) {
            delegate.database(database);
            return this;
        }
        public ConnectionBuilder create(boolean create) {
            delegate.create(create);
            return this;
        }
        public ConnectionBuilder user(String user) {
            delegate.user(user);
            return this;
        }
        public ConnectionBuilder password(String password) {
            delegate.password(password);
            return this;
        }
        public ConnectionBuilder schema(String schema) {
            delegate.schema(schema);
            schemaSet = true;
            return this;
        }
        public ConnectionBuilder ssl(boolean ssl) {
            delegate.ssl(ssl);
            return this;
        }
        public ConnectionBuilder useOLAP(boolean useOLAP) {
            delegate.useOLAP(useOLAP);
            return this;
        }
        public ConnectionBuilder useNativeSpark(boolean useNativeSpark) {
            delegate.useNativeSpark(useNativeSpark);
            return this;
        }
        public ConnectionBuilder minPlanTimeout(long minPlanTimeout) {
            delegate.minPlanTimeout(minPlanTimeout);
            return this;
        }
        public ConnectionBuilder setCurrentFunctionPath(String currentFunctionPath) {
            delegate.setCurrentFunctionPath(currentFunctionPath);
            return this;
        }
        public ConnectionBuilder snapshot(long snapshot) {
            delegate.snapshot(snapshot);
            return this;
        }

        public ConnectionBuilder autoCommit(boolean autoCommit) {
            delegate.autoCommit(autoCommit);
            return this;
        }
        /**
         * Always creates a new connection, replacing this class's reference to the current connection, if any.
         */
        public TestConnection build() throws SQLException {
            if (!schemaSet && !isNullOrEmpty(defaultSchema)) {
                delegate.schema(defaultSchema);
            }
            currentConnection = new TestConnection(delegate.build());
            connections.add(currentConnection);
            return currentConnection;
        }
    }

    public ConnectionBuilder connectionBuilder() {
        return new ConnectionBuilder();
    }

    public void setConnection(Connection connection) throws SQLException{
        currentConnection = new TestConnection(connection);
    }

    /**
     * Returns the same Connection object until finished() is called (that is, until a given test method ends).
     *
     * This method used to create and return a new Connection if it determined that the current one was invalid but
     * this meant that tests using the convenience methods herein that delegate to this method could not assume all
     * operations happened with the same connection or consequently in the same transaction.
     *
     * If a test intentionally leaves a connection in an invalid state (mid test) it must call closeAll().  If a class
     * un-intentionally leaves a connection in an invalid state (mid test) it should fail.
     */
    public TestConnection getOrCreateConnection() {
        try {
            if (currentConnection == null || currentConnection.isClosed()) {
                connectionBuilder().build();
            }
            return currentConnection;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Always creates a new connection, replacing this class's reference to the current connection, if any.
     */
    public synchronized TestConnection createConnection() throws Exception {
        return connectionBuilder().build();
    }

    public PreparedStatement prepareStatement(String sql) throws SQLException {
        PreparedStatement ps = getOrCreateConnection().prepareStatement(sql);
        statements.add(ps);
        return ps;
    }

    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        PreparedStatement ps = getOrCreateConnection().prepareStatement(sql, resultSetType, resultSetConcurrency);
        statements.add(ps);
        return ps;
    }

    /**
     * Try closing connections gracefully from different threads, if that takes long it could be some queries are stuck,
     * so abort the connections forcefully instead
     */
    private void closeConnections() {
        try {
            List<Callable<Void>> callables = connections.stream().map(connection -> (Callable<Void>) () -> {
                try {
                    if (connection != null && !connection.isClosed())
                        connection.close();
                } catch (SQLException e) {
                    LOG.error("Exception while closing connection", e);
                }
                return null;
            }).collect(Collectors.toList());
            List<Future<Void>> results = executor.invokeAll(callables, 10, TimeUnit.SECONDS);
            List<Future<Void>> stillRunning = results.stream().filter(f -> f.isCancelled() || !f.isDone()).collect(Collectors.toList());
            if (stillRunning.isEmpty()) {
                // we are done
                return;
            }
            stillRunning.stream().forEach(f -> f.cancel(true));
        } catch (InterruptedException e) {
            LOG.error("Interrupted while closing", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("Exception while closing gracefully", e);
        }
        LOG.warn("Some tasks still running, aborting connections");

        for(Connection connection : connections) {
            try {
                if (connection != null && !connection.isClosed()) {
                    LOG.info("Aborting " + connection);
                    connection.abort(executor);
                }
            } catch (SQLException sqle) {
                LOG.error("Couldn't abort connection", sqle);
            }
        }

    }

    private void closeStatements() {
        try {
            for (Statement s : statements) {
                if (!s.isClosed())
                    s.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void closeResultSets() {
        List<Throwable> t = Lists.newArrayListWithExpectedSize(0);
        for (ResultSet r : resultSets) {
            try {
                if (!r.isClosed()) {
                    r.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
                t.add(e);
            }
        }
        try {
            MultipleFailureException.assertEmpty(t);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    @Override
    protected void starting(Description description) {
        LOG.info("Test " + description.getMethodName() + " started.");
    }

    @Override
    protected void finished(Description description) {
        closeAll();
        super.finished(description);
        LOG.info("Test " + description.getMethodName() + " finished.");
    }

    public void closeAll() {
        closeResultSets();
        closeStatements();
        closeConnections();
        currentConnection = null;
    }

    public ResultSet executeQuery(String sql) throws SQLException {
        Statement s = getStatement();
        ResultSet rs = s.executeQuery(sql);
        resultSets.add(rs);
        return rs;
    }

    public ResultSet executeQuery(String sql, String userName, String password) throws Exception {
        Statement s = getStatement(userName, password);
        ResultSet rs = s.executeQuery(sql);
        resultSets.add(rs);
        return rs;
    }

    /**
     * Return column one from all rows.
     */
    public <T> List<T> queryList(String sql) throws Exception {
        List<T> resultList = Lists.newArrayList();
        try (ResultSet rs = executeQuery(sql)) {
            while (rs.next()) {
                resultList.add((T) rs.getObject(1));
            }
        }
        return resultList;
    }

    /**
     * Return columns from all rows.
     */
    public <T> List<Object[]> queryListMulti(String sql, int columns) throws Exception {
        List<Object[]> resultList = Lists.newArrayList();
        try (ResultSet rs = executeQuery(sql)) {
            while (rs.next()) {
                Object[] row = new Object[columns];
                for (int i = 0; i < columns; i++) {
                    row[i] = rs.getObject(i + 1);
                }
                resultList.add(row);
            }
        }
        return resultList;
    }

    /**
     * Return column one from the first row.  Asserts that one and only one row is returned.
     */
    public <T> T query(String sql) throws Exception {
        T result;
        try(ResultSet rs = executeQuery(sql)) {
            assertTrue("does not have next", rs.next());
            result = (T) rs.getObject(1);
            assertFalse(rs.next());
            return result;
        }
    }

    public int executeUpdate(String sql) throws Exception {
        try(Statement s = getOrCreateConnection().createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    public boolean execute(String sql) throws Exception {
        try(Statement s = getOrCreateConnection().createStatement()) {
            return s.execute(sql);
        }
    }

    public int executeUpdate(String sql, String userName, String password) throws Exception {
        Statement s = getStatement(userName, password);
        return s.executeUpdate(sql);
    }

    public Statement getStatement() throws SQLException {
        Statement s = getOrCreateConnection().createStatement();
        statements.add(s);
        return s;
    }

    public Statement getStatement(String userName, String password) throws Exception {
        Statement s = connectionBuilder().user(userName).password(password).build().createStatement();
        statements.add(s);
        return s;
    }

    public CallableStatement prepareCall(String sql) throws Exception {
        CallableStatement s = getOrCreateConnection().prepareCall(sql);
        statements.add(s);
        return s;
    }

    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws Exception {
        CallableStatement s = getOrCreateConnection().prepareCall(sql, resultSetType, resultSetConcurrency);
        statements.add(s);
        return s;
    }

    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws Exception {
        CallableStatement s = getOrCreateConnection().prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        statements.add(s);
        return s;
    }

    public void setAutoCommit(boolean autoCommit) throws Exception {
        getOrCreateConnection().setAutoCommit(autoCommit);
    }

    public void rollback() throws Exception {
        getOrCreateConnection().rollback();
    }

    public void commit() throws Exception {
        getOrCreateConnection().commit();
    }

    public void setSchema(String schema) throws Exception {
        PreparedStatement stmt = prepareStatement("SET SCHEMA ?");
        stmt.setString(1, schema);
        stmt.executeUpdate();
    }

    public void splitTable(String tableName, String schemaName) throws Exception {
        getConglomId(tableName,schemaName);
    }

    public long getConglomId(String tableName, String schemaName) throws Exception {
        /*
         * This is a needlessly-complicated and annoying way of doing this,
         * because *when it was written*, the metadata information was kind of all messed up
         * and doing a join between systables and sysconglomerates resulted in an error. When you are
         * looking at this code and going WTF?!? feel free to try cleaning up the SQL. If you get a bunch of
         * wonky errors, then we haven't fixed the underlying issue yet. If you don't, then you just cleaned up
         * some ugly-ass code. Good luck to you.
         *
         */
        PreparedStatement ps = prepareStatement("select c.conglomeratenumber from " +
                "sys.systables t, sys.sysconglomerates c,sys.sysschemas s " +
                "where t.tableid = c.tableid " +
                "and s.schemaid = t.schemaid " +
                "and c.isindex = false " +
                "and t.tablename = ? " +
                "and s.schemaname = ?");
        ps.setString(1, tableName);
        ps.setString(2, schemaName);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            } else {
                LOG.warn("Unable to find the conglom id for table  " + tableName);
            }
        }
        return -1l;
    }

    public String executeGetString(String sql, int index) throws SQLException {
        try( Statement s = getOrCreateConnection().createStatement();
             ResultSet rs = s.executeQuery(sql))
        {
            Assert.assertTrue(rs.next());
            return rs.getString(index);
        }
    }

    public String executeToString(String sql, Boolean sort) throws Exception {
        try(ResultSet rs = executeQuery(sql)) {
            return sort ? TestUtils.FormattedResult.ResultFactory.toString(rs) :
                    TestUtils.FormattedResult.ResultFactory.toStringUnsorted(rs);
        }
    }

    public void assertStrResult(String res, String query, Boolean sort) throws Exception {
        Assert.assertEquals( "failed asserting the results of sql\n" + query,
                res, executeToString(query, sort));
    }
}
