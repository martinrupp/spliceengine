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

package com.splicemachine.db.impl.ast;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.compile.*;
import com.splicemachine.db.impl.sql.compile.*;
import splice.com.google.common.base.Function;
import splice.com.google.common.base.Predicates;
import splice.com.google.common.collect.*;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Utilities for Derby's ResultSetNodes
 *
 * @author P Trolard Date: 18/10/2013
 */
public class RSUtils {

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // functions
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    public static final Function<ResultSetNode, Integer> rsNum = new Function<ResultSetNode, Integer>() {
        @Override
        @SuppressFBWarnings(value = "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE", justification = "DB-9844")
        public Integer apply(ResultSetNode rsn) {
            return rsn == null? -1 : rsn.getResultSetNumber();
        }
    };

    public static final Function<Object, Class<?>> classOf = new Function<Object, Class<?>>() {
        @Override
        public Class<?> apply(Object input) {
            return input == null ? null : input.getClass();
        }
    };

    public static final Function<ValueNode, ResultColumn> refToRC = new Function<ValueNode, ResultColumn>() {
        @Override
        public ResultColumn apply(ValueNode vn) {
            if (vn instanceof ColumnReference) {
                ColumnReference cr = (ColumnReference) vn;
                return cr.getSource();
            } else if (vn instanceof VirtualColumnNode) {
                VirtualColumnNode vcn = (VirtualColumnNode) vn;
                return vcn.getSourceColumn();
            }
            return null;
        }
    };

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // predicates
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    public static final splice.com.google.common.base.Predicate<Object> isRSN = Predicates.instanceOf(ResultSetNode.class);

    public static final splice.com.google.common.base.Predicate<ResultSetNode> rsnHasPreds =
            Predicates.or(Predicates.instanceOf(ProjectRestrictNode.class), Predicates.instanceOf(FromBaseTable.class),
                    Predicates.instanceOf(IndexToBaseRowNode.class));


    public final static splice.com.google.common.base.Predicate<ResultSetNode> isSinkingNode = new splice.com.google.common.base.Predicate<ResultSetNode>() {
        @Override
        @SuppressFBWarnings(value = "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE", justification = "DB-9844")
        public boolean apply(ResultSetNode rsn) {
            return rsn != null && sinkers.contains(rsn.getClass()) &&
                    (!(rsn instanceof JoinNode) || RSUtils.isSinkingJoin(RSUtils.ap((JoinNode) rsn)) ||
                            RSUtils.leftHasIndexLookup(rsn));
        }
    };


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // collections
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    public static final Set<?> binaryRSNs = ImmutableSet.of(
            JoinNode.class,
            HalfOuterJoinNode.class,
            FullOuterJoinNode.class,
            UnionNode.class,
            IntersectOrExceptNode.class);

    public static final Set<?> binaryRSNsExcludeUnion = ImmutableSet.of(
            JoinNode.class,
            HalfOuterJoinNode.class,
            FullOuterJoinNode.class,
            IntersectOrExceptNode.class);

    public static final splice.com.google.common.base.Predicate<Object> isBinaryRSN =
            Predicates.compose(Predicates.in(binaryRSNs), classOf);

    public static final splice.com.google.common.base.Predicate<Object> isBinaryRSNExcludeUnion =
            Predicates.compose(Predicates.in(binaryRSNsExcludeUnion), classOf);

    public static final splice.com.google.common.base.Predicate<Object> isIntersectOrExcept =
            Predicates.compose(Predicates.in(ImmutableSet.of(IntersectOrExceptNode.class)), classOf);

    // leafRSNs might need VTI eventually
    public static final Set<?> leafRSNs = ImmutableSet.of(
            FromBaseTable.class,
            RowResultSetNode.class);

    public static final Map<Class<?>, String> sinkingNames =
            ImmutableMap.<Class<?>, String>builder()
                    .put(JoinNode.class, "join")
                    .put(HalfOuterJoinNode.class, "join")
                    .put(FullOuterJoinNode.class, "join")
                    .put(AggregateNode.class, "aggregate")
                    .put(DistinctNode.class, "distinct")
                    .put(OrderByNode.class, "sort")
            .build();

    public final static Set<?> sinkers =
            ImmutableSet.of(
                    JoinNode.class,
                    HalfOuterJoinNode.class,
                    FullOuterJoinNode.class,
                    AggregateNode.class,
                    DistinctNode.class,
                    OrderByNode.class);

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
    //
    // methods
    //
    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    /**
     * Return any instances of clazz at or below node
     */
    public static <N> List<N> collectNodes(Visitable node, Class<N> clazz) throws StandardException {
        return CollectingVisitorBuilder.forClass(clazz).collect(node);
    }

    /**
     * Visit the node in question and all descendants of the node until the specified predicate evaluates to true. Note
     * that if the predicate evaluates to true for the passed top node parameter then no nodes will be visited.
     */
    public static <N> List<N> collectNodesUntil(Visitable node, Class<N> clazz,
                                                splice.com.google.common.base.Predicate<? super Visitable> pred) throws StandardException {
        return CollectingVisitorBuilder.forClass(clazz).until(pred).collect(node);
    }

    public static <N> List<N> collectExpressionNodes(ResultSetNode node, Class<N> clazz) throws StandardException {
        // define traversal axis to be the node itself (so we can get to its descendants) or,
        // our real target, non-ResultSetNodes
        splice.com.google.common.base.Predicate<Object> onAxis = Predicates.or(Predicates.equalTo((Object) node), Predicates.not(isRSN));
        return CollectingVisitorBuilder.forClass(clazz).onAxis(onAxis).collect(node);
    }

    /**
     * Return list of node and its ResultSetNode descendants, as returned by depth-first, pre-order traversal. Does not
     * descend into expression nodes (therefore doesn't consider ResultSetNodes in subqueries descendants).
     */
    public static List<ResultSetNode> getSelfAndDescendants(ResultSetNode rsn) throws StandardException {
        return CollectingVisitorBuilder.forClass(ResultSetNode.class).onAxis(isRSN).collect(rsn);
    }

    /**
     * Return immediate (ResultSetNode) children of node
     */
    public static List<ResultSetNode> getChildren(ResultSetNode node) throws StandardException {
        splice.com.google.common.base.Predicate<Object> self = Predicates.equalTo((Object) node);
        splice.com.google.common.base.Predicate<Object> notSelfButRS = Predicates.and(Predicates.not(self), isRSN);
        return CollectingVisitorBuilder.<ResultSetNode>forPredicate(notSelfButRS)
                .onAxis(self)
                .collect(node);
    }

    public static List<ResultSetNode> nodesUntilBinaryNode(ResultSetNode rsn) throws StandardException {
        return CollectingVisitorBuilder.forClass(ResultSetNode.class)
                .onAxis(isRSN)
                .until(isBinaryRSN)
                .collect(rsn);
    }

    public static List<ResultSetNode> nodesUntilBinaryNodeExcludeUnion(ResultSetNode rsn) throws StandardException {
        return CollectingVisitorBuilder.forClass(ResultSetNode.class)
                .onAxis(isRSN)
                .until(isBinaryRSNExcludeUnion)
                .collect(rsn);
    }

    public static List<ResultSetNode> nodesUntilIntersectOrExcept(ResultSetNode rsn) throws StandardException {
        return CollectingVisitorBuilder.forClass(ResultSetNode.class)
                .onAxis(isRSN)
                .until(isIntersectOrExcept)
                .collect(rsn);
    }

    /**
     * Returns the leaves for a query plan subtree
     */
    public static List<ResultSetNode> getLeafNodes(ResultSetNode rsn) throws StandardException {
        List<ResultSetNode> rsns = getSelfAndDescendants(rsn);
        List<ResultSetNode> leaves = new LinkedList<>();
        for (ResultSetNode r : rsns) {
            if (leafRSNs.contains(r.getClass())) {
                leaves.add(r);
            }
        }
        return leaves;
    }

    /**
     * CAUTION: This method modifies the FromBaseTable parameter.
     */
    // Makes a list of non-key predicates.
    public static PredicateList getPreds(FromBaseTable t) throws StandardException {
        PredicateList pl = new PredicateList();
        t.pullNonKeyPredicates(pl);
        for (int i = 0, s = pl.size(); i < s; i++) {
            OptimizablePredicate p = pl.getOptPredicate(i);
            t.pushOptPredicate(p);
        }
        PredicateList storeRestrictionList = t.storeRestrictionList;
        for (int i = 0; i < storeRestrictionList.size(); ++i) {
            OptimizablePredicate pred = storeRestrictionList.getOptPredicate(i);
            if (!pred.isScanKey() && !contains(pl, pred)) {
                pl.addOptPredicate(pred);
            }
        }
        return pl;
    }

    /**
     * CAUTION: This method modifies the FromBaseTable parameter.
     */
    // Makes a list of key predicates, that allow scanning a subset of rows in the table.
    public static PredicateList getKeyPreds(FromBaseTable t) throws StandardException {
        PredicateList pl = new PredicateList();
        t.pullKeyPredicates(pl);
        for (int i = 0, s = pl.size(); i < s; i++) {
            OptimizablePredicate p = pl.getOptPredicate(i);
            t.pushOptPredicate(p);
        }
        PredicateList storeRestrictionList = t.storeRestrictionList;
        for (int i = 0; i < storeRestrictionList.size(); ++i) {
            OptimizablePredicate pred = storeRestrictionList.getOptPredicate(i);
            if (pred.isScanKey() && !contains(pl, pred)) {
                pl.addOptPredicate(pred);
            }
        }
        return pl;
    }

    private static boolean contains(PredicateList pl, OptimizablePredicate pred) {
        for (int i = 0; i < pl.size(); ++i) {
            OptimizablePredicate p = pl.getOptPredicate(i);
            if (p == pred) {
                return true;
            }
        }
        return false;
    }

    public static PredicateList getPreds(ProjectRestrictNode pr) throws StandardException {
        return pr.restrictionList != null ? pr.restrictionList : new PredicateList();
    }


    public static PredicateList getPreds(IndexToBaseRowNode in) throws StandardException {
        return in.restrictionList != null ? in.restrictionList : new PredicateList();
    }

    public static boolean isMSJ(AccessPath ap) {
        return (ap != null && ap.getJoinStrategy().getJoinStrategyType().equals(JoinStrategy.JoinStrategyType.MERGE_SORT));
    }

    public static boolean isMJ(AccessPath ap) {
        return (ap != null && (ap.getJoinStrategy().getJoinStrategyType().equals(JoinStrategy.JoinStrategyType.MERGE)));
    }

    public static boolean isNLJ(AccessPath ap) {
        return (ap != null && ap.getJoinStrategy().getJoinStrategyType().equals(JoinStrategy.JoinStrategyType.NESTED_LOOP));
    }

    public static boolean isHashableJoin(AccessPath ap) {
        if (ap == null) return false;
        JoinStrategy strategy = ap.getJoinStrategy();
        return strategy instanceof HashableJoinStrategy;
    }

    public static boolean isCrossJoin(AccessPath ap) {
        if (ap == null) return false;
        JoinStrategy strategy = ap.getJoinStrategy();
        return strategy.getJoinStrategyType() == JoinStrategy.JoinStrategyType.CROSS;
    }

    public static boolean isSinkingJoin(AccessPath ap) {
        return isMSJ(ap) || isMJ(ap);
    }

    public static splice.com.google.common.base.Predicate<ResultColumn> pointsTo(ResultSetNode rsn) throws StandardException {
        final Set<Integer> rsns = Sets.newHashSet(Iterables.transform(getSelfAndDescendants(rsn), rsNum));
        return new splice.com.google.common.base.Predicate<ResultColumn>() {
            @Override
            public boolean apply(ResultColumn rc) {
                return rc != null && rsns.contains(rc.getResultSetNumber());
            }
        };
    }

    public static splice.com.google.common.base.Predicate<ValueNode> refPointsTo(ResultSetNode rsn) throws StandardException {
        return Predicates.compose(pointsTo(rsn), refToRC);
    }

    public static AccessPath ap(JoinNode j) {
        return ((Optimizable) j.getRightResultSet()).getTrulyTheBestAccessPath();
    }

    public static Iterable<ResultSetNode> sinkingChildren(ResultSetNode node) throws StandardException {
        return Iterables.filter(RSUtils.getSelfAndDescendants(node), isSinkingNode);
    }

    public static boolean hasSinkingChildren(ResultSetNode node) throws StandardException {
        List<ResultSetNode> sinks = Lists.newLinkedList(sinkingChildren(node));
        return (sinks != null && !sinks.isEmpty());
    }

    public static boolean leftHasIndexLookup(ResultSetNode node) {
        ResultSetNode currentNode = node;
        while (currentNode != null) {
            if (currentNode instanceof IndexToBaseRowNode)
                return true;

            else if (currentNode instanceof TableOperatorNode) {
                currentNode = ((TableOperatorNode) currentNode).getLeftResultSet();
            } else if (currentNode instanceof ProjectRestrictNode) {
                currentNode = ((ProjectRestrictNode) currentNode).getChildResult();
            } else {
                // FromBaseTable or other cases
                return false;
            }
        }

        return false;
    }
}
