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

import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
import com.splicemachine.db.iapi.services.compiler.LocalField;
import com.splicemachine.db.iapi.reference.ClassName;


import com.splicemachine.db.iapi.sql.compile.CompilerContext;
import com.splicemachine.db.iapi.sql.compile.CodeGeneration;

import com.splicemachine.db.iapi.error.StandardException;

import com.splicemachine.db.iapi.services.classfile.VMOpcode;
import com.splicemachine.db.iapi.sql.compile.DataSetProcessorType;
import com.splicemachine.db.iapi.sql.compile.SparkExecutionType;

import java.lang.reflect.Modifier;

/**
 * ActivationClassBuilder
 * provides an interface to satisfy generation's
 * common tasks in building an activation class,
 * as well as a repository for the JavaFactory used
 * to generate the basic language constructs for the methods in the class.
 * Common tasks include the setting of a static field for each
 * expression function that gets added, the creation
 * of the execute method that gets expanded as the query tree
 * is walked, setting the superclass.
 * <p>
 * An activation class is defined for each statement. It has
 * the following basic layout: TBD
 * See the document
 * \\Jeeves\Unversioned Repository 1\Internal Technical Documents\Other\GenAndExec.doc
 * for details.
 * <p>
 * We could also verify methods as they are
 * added, to have 0 parameters, ...
 *
 */
public class ActivationClassBuilder    extends    ExpressionClassBuilder {
    ///////////////////////////////////////////////////////////////////////
    //
    // CONSTANTS
    //
    ///////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////
    //
    // STATE
    //
    ///////////////////////////////////////////////////////////////////////

    private LocalField    targetResultSetField;
    private LocalField  cursorResultSetField;

    private MethodBuilder closeActivationMethod;
    private DataSetProcessorType pushedType;
    private SparkExecutionType pushedSparkExecutionType;

    ///////////////////////////////////////////////////////////////////////
    //
    // CONSTRUCTOR
    //
    ///////////////////////////////////////////////////////////////////////

    /**
     * By the time this is done, it has constructed the following class:
     * <pre>
     *    public class #className extends #superClass {
     *        // public void reset() { return; }
     *        public ResultSet execute() throws StandardException {
     *            throwIfClosed("execute");
     *            // statements must be added here
     *        }
     *        public #className() { super(); }
     *    }
     * </pre>
     *
     * @exception StandardException thrown on failure
     */
    public ActivationClassBuilder (String superClass, CompilerContext cc) throws StandardException
    {
        super( superClass, (String) null, cc );
        executeMethod = beginExecuteMethod();
        materializationMethod = beginMaterializationMethod();
        subqueryResultSetMethod = beginSubqueryResultSetMethod();
        pushedType = null;
        pushedSparkExecutionType = null;
    }

    ///////////////////////////////////////////////////////////////////////
    //
    // ACCESSORS
    //
    ///////////////////////////////////////////////////////////////////////

    /**
      *    Get the package name that this generated class lives in
      *
      *    @return    package name
      */
    public    String    getPackageName()
    {    return    CodeGeneration.GENERATED_PACKAGE_PREFIX; }

    /**
        The base class for activations is BaseActivation
     */
    String getBaseClassName() {
        return ClassName.BaseActivation;
    }


    /**
      *    Get the number of ExecRows to allocate
      *
      * @exception StandardException thrown on failure
      *    @return    package name
      */
    public    int        getRowCount()
         throws StandardException
    {
        return    myCompCtx.getNumResultSets();
    }

    /**
     * Generate the assignment for numSubqueries = x
     *
     * @exception StandardException thrown on failure
     */
    public     void    setNumSubqueries()
    {
        int                numSubqueries = myCompCtx.getNumSubquerys();

        // If there are no subqueries then
        // the field is set to the correctly
        // value (0) by java.
        if (numSubqueries == 0)
            return;

        /* Generated code is:
         *        numSubqueries = x;
         */
        constructor.pushThis();
        constructor.push(numSubqueries);
        constructor.putField(ClassName.BaseActivation, "numSubqueries", "int");
        constructor.endStatement();
    }


    /**
     *
     *
     *
     */
    @Override
    public void setDataSetProcessorType(DataSetProcessorType type) {
        if (pushedType != null) {
            assert pushedType == type;
        } else {
            pushedType = type;
            constructor.pushThis();
            constructor.push(type.ordinal());
            constructor.putField(ClassName.BaseActivation, "datasetProcessorType", "int");
            constructor.endStatement();
        }
    }

    @Override
    public void setSparkExecutionType(SparkExecutionType type) {
        if (pushedSparkExecutionType != null) {
            assert pushedSparkExecutionType == type;
        } else {
            pushedSparkExecutionType = type;
            constructor.pushThis();
            constructor.push(type.ordinal());
            constructor.putField(ClassName.BaseActivation, "sparkExecutionType", "int");
            constructor.endStatement();
        }
    }

    ///////////////////////////////////////////////////////////////////////
    //
    // EXECUTE METHODS
    //
    ///////////////////////////////////////////////////////////////////////

    /**
     * By the time this is done, it has generated the following code
     * <pre>
     *        public ResultSet execute() throws StandardException {
     *            throwIfClosed("execute");
     *            // statements must be added here
     *        }
     *    }
     * </pre>
     *
     * @exception StandardException thrown on failure
     */
    private    MethodBuilder    beginExecuteMethod()
        throws StandardException
    {
        // create a reset method that does nothing.
        // REVISIT: this might better belong in the Activation
        // superclasses ?? not clear yet what it needs to do.

        // don't yet need a reset method here. when we do,
        // it will need to call super.reset() as well as
        // whatever it does.
        // mb = cb.newMethodBuilder(
        //     Modifier.PUBLIC, "void", "reset");
        // mb.addStatement(javaFac.newStatement(
        //        javaFac.newSpecialMethodCall(
        //            thisExpression(),
        //            BaseActivation.CLASS_NAME,
        //            "reset", "void")));
        // mb.addStatement(javaFac.newReturnStatement());
        // mb.complete(); // there is nothing else.


        // This method is an implementation of the interface method
        // Activation - ResultSet execute()

        // create an empty execute method
        MethodBuilder mb = cb.newMethodBuilder(Modifier.PUBLIC,
            ClassName.ResultSet, "execute");
        mb.addThrownException(ClassName.StandardException);

        // put a 'throwIfClosed("execute");' statement into the execute method.
        mb.pushThis(); // instance
        mb.push("execute");
        mb.callMethod(VMOpcode.INVOKEVIRTUAL, ClassName.BaseActivation, "throwIfClosed", "void", 1);

        // call this.startExecution(), so the parent class can know an execution
        // has begun.

        mb.pushThis(); // instance
        mb.callMethod(VMOpcode.INVOKEVIRTUAL, ClassName.BaseActivation, "startExecution", "void", 0);

        return    mb;
    }

    private    MethodBuilder    beginMaterializationMethod()
            throws StandardException
    {
        MethodBuilder mb = cb.newMethodBuilder(Modifier.PUBLIC,
                "void", "materialize");
        mb.addThrownException(ClassName.StandardException);

        // put a 'throwIfClosed("execute");' statement into the execute method.
        mb.pushThis(); // instance
        mb.push("materialize");
        mb.callMethod(VMOpcode.INVOKEVIRTUAL, ClassName.BaseActivation, "throwIfClosed", "void", 1);

        return mb;
    }

    private MethodBuilder beginSubqueryResultSetMethod() throws StandardException {
        MethodBuilder mb = cb.newMethodBuilder(Modifier.PUBLIC,
                "java.util.Vector", "getSubqueryResultSets");
        mb.addThrownException(ClassName.StandardException);

        mb.pushThis(); // instance
        mb.push("getSubqueryResultSets");
        mb.callMethod(VMOpcode.INVOKEVIRTUAL, ClassName.BaseActivation, "throwIfClosed", "void", 1);

        mb.pushNewStart("java.util.Vector");
        mb.pushNewComplete(0);
        return mb;
    }

    /// this creates
    // return super.resultSet == null ? (super.resultSet = this.fillResultSet()) : super.resultSet;
    public void finishExecuteCode(boolean genMarkAsTopNode) {

        MethodBuilder executeMethod = getExecuteMethod();
        executeMethod.pushThis();
        executeMethod.getField(ClassName.BaseActivation,"resultSet", ClassName.ResultSet);

        executeMethod.conditionalIfNull();

        // Generate the result set tree and store the
        // resulting top-level result set into the resultSet
        // field, as well as returning it from the execute method.

        executeMethod.pushThis();
        executeMethod.callMethod(VMOpcode.INVOKEVIRTUAL,null,"fillResultSet",ClassName.ResultSet,0);
        executeMethod.pushThis();
        executeMethod.swap();
        executeMethod.putField(ClassName.BaseActivation,"resultSet",ClassName.ResultSet);

        executeMethod.startElseCode(); // this is here as the compiler only supports ? :
        executeMethod.pushThis();
        executeMethod.getField(ClassName.BaseActivation,"resultSet",ClassName.ResultSet);
        executeMethod.completeConditional();

        // wrap up the activation class definition
        // generate on the tree gave us back the newExpr
        // for getting a result set on the tree.
        // we put it in a return statement and stuff
        // it in the execute method of the activation.
        // The generated statement is the expression:
        // the activation class builder takes care of constructing it
        // for us, given the resultSetExpr to use.
        //   return (this.resultSet = #resultSetExpr);
        finishExecuteMethod(genMarkAsTopNode);
    }

    MethodBuilder startResetMethod() {
        MethodBuilder mb = cb.newMethodBuilder(Modifier.PUBLIC,
            "void", "reset");

        mb.addThrownException(ClassName.StandardException);
        mb.pushThis();
        mb.callMethod(VMOpcode.INVOKESPECIAL, ClassName.BaseActivation, "reset", "void", 0);

        return mb;
    }

    /**
     * An execute method always ends in a return statement, returning
     * the result set that has been constructed.  We want to
     * do some bookkeeping on that statement, so we generate
     * the return given the result set.

       Upon entry the only word on the stack is the result set expression
     */
    public void finishExecuteMethod(boolean genMarkAsTopNode) {

        /* We only call markAsTopResultSet() for selects.
         * Non-select DML marks the top NoPutResultSet in the constructor.
         * Needed for closing down resultSet on an error.
         */
        if (genMarkAsTopNode) {
            // dup the result set to leave one for the return and one for this call
            executeMethod.dup();
            executeMethod.cast(ClassName.NoPutResultSet);
            executeMethod.callMethod(VMOpcode.INVOKEINTERFACE, (String) null, "markAsTopResultSet", "void", 0);
        }

        /* return resultSet */
        executeMethod.methodReturn();
        executeMethod.complete();
    }

    public void addFields() {

        getClassBuilder().newFieldWithAccessors("getExecutionCount", "setExecutionCount",
                Modifier.PROTECTED, true, "int");

        getClassBuilder().newFieldWithAccessors("getRowCountCheckVector", "setRowCountCheckVector",
                Modifier.PROTECTED, true, "java.util.Vector");

        getClassBuilder().newFieldWithAccessors("getStalePlanCheckInterval", "setStalePlanCheckInterval",
                Modifier.PROTECTED, true, "int");
    }

    public void finishActivationMethod() {

        if (closeActivationMethod != null) {
            closeActivationMethod.methodReturn();
            closeActivationMethod.complete();
        }
    }

    void addSubqueryResultSet(MethodBuilder mb) {
        subqueryResultSetMethod.dup();
        subqueryResultSetMethod.pushThis();
        subqueryResultSetMethod.callMethod(VMOpcode.INVOKEVIRTUAL, null, mb.getName(), ClassName.ResultSet, 0);
        subqueryResultSetMethod.upCast("java.lang.Object");
        subqueryResultSetMethod.callMethod(VMOpcode.INVOKEVIRTUAL, "java.util.Vector", "addElement", "void", 1);
    }

    public void finishMaterializationMethod() {
        materializationMethod.methodReturn();
        materializationMethod.complete();
    }

    public void finishSubqueryResultSetMethod() {
        subqueryResultSetMethod.methodReturn();
        subqueryResultSetMethod.complete();
    }
    ///////////////////////////////////////////////////////////////////////
    //
    // CURSOR SUPPORT
    //
    ///////////////////////////////////////////////////////////////////////

    /**
     * Updatable cursors
     * need to add a getter method for use in BaseActivation to access
     * the result set that identifies target rows for a positioned
     * update or delete.
     * <p>
     * The code that is generated is:
     * <pre><verbatim>
     *  public CursorResultSet getTargetResultSet() {
     *        return targetResultSet;
     *  }
     *
     *  public CursorResultSet getCursorResultSet() {
     *        return cursorResultSet;
     *  }
     * </verbatim></pre>
     *
     */
    void addCursorPositionCode() {

        // the getter
        // This method is an implementation of the interface method
        // CursorActivation - CursorResultSet getTargetResultSet()
        MethodBuilder getter = cb.newMethodBuilder(Modifier.PUBLIC,
            ClassName.CursorResultSet, "getTargetResultSet");

        getter.getField(targetResultSetField);
        getter.methodReturn();
        getter.complete();

        // This method is an implementation of the interface method
        // CursorActivation - CursorResultSet getCursorResultSet()

        getter = cb.newMethodBuilder(Modifier.PUBLIC,
            ClassName.CursorResultSet, "getCursorResultSet");

        getter.getField(cursorResultSetField);
        getter.methodReturn();
        getter.complete();
    }

    /**
     * Updatable cursors
     * need to add a field and its initialization
     * for use in BaseActivation to access the result set that
     * identifies target rows for a positioned update or delete.
     * <p>
     * The code that is generated is:
     * <pre><verbatim>
     *  private CursorResultSet targetResultSet;
     *
     * </verbatim></pre>
     *
     * The expression that is generated is:
     * <pre><verbatim>
     *  (ResultSet) (targetResultSet = (CursorResultSet) #expression#)
     * </verbatim></pre>
     *
     */
    void rememberCursorTarget(MethodBuilder mb) {

        // the field
        targetResultSetField = cb.addField(ClassName.CursorResultSet,
                    "targetResultSet",
                    Modifier.PRIVATE);

        mb.cast(ClassName.CursorResultSet);
        mb.putField(targetResultSetField);
        mb.cast(ClassName.NoPutResultSet);
    }

    /**
     * Updatable cursors
     * need to add a field and its initialization
     * for use in BaseActivation to access the result set that
     * identifies cursor result rows for a positioned update or delete.
     * <p>
     * The code that is generated is:
     * <pre><verbatim>
     *  private CursorResultSet cursorResultSet;
     *
     * </verbatim></pre>
     *
     * The expression that is generated is:
     * <pre><verbatim>
     *  (ResultSet) (cursorResultSet = (CursorResultSet) #expression#)
     * </verbatim></pre>

       The expression must be the top stack word when this method is called.
     *
     */
    void rememberCursor(MethodBuilder mb) {

        // the field
        cursorResultSetField = cb.addField(ClassName.CursorResultSet,
                    "cursorResultSet",
                    Modifier.PRIVATE);

        mb.cast(ClassName.CursorResultSet);
        mb.putField(cursorResultSetField);
        mb.cast(ClassName.ResultSet);
    }

    ///////////////////////////////////////////////////////////////////////
    //
    // CURRENT DATE/TIME SUPPORT
    //
    ///////////////////////////////////////////////////////////////////////

    /*
        The first time a current datetime is needed, create the class
        level support for it. The first half of the logic is in our parent
        class.
     */
    protected LocalField getCurrentDateTime()
    {
        if (cdtField != null) return cdtField;

        LocalField lf = super.getCurrentDateTime();

        // 3) Set current timestamp precision
        //    cdt.setCurrentTimestampPrecision(precision)
        executeMethod.getField(lf);
        executeMethod.push(myCompCtx.getCurrentTimestampPrecision());
        executeMethod.callMethod(VMOpcode.INVOKEVIRTUAL, (String) null, "setCurrentTimestampPrecision", "void", 1);

        // 4) the execute method gets a statement (prior to the return)
        //    to tell cdt to restart:
        //      cdt.forget();
        executeMethod.getField(lf);
        executeMethod.callMethod(VMOpcode.INVOKEVIRTUAL, (String) null, "forget", "void", 0);

        return lf;
    }

    MethodBuilder getCloseActivationMethod() {

        if (closeActivationMethod == null) {
            closeActivationMethod = cb.newMethodBuilder(Modifier.PUBLIC, "void", "closeActivationAction");
            closeActivationMethod.addThrownException("java.lang.Exception");
        }
        return closeActivationMethod;
    }
}

