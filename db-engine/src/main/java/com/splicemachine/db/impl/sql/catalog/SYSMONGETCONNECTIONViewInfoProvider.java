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
 *
 */

package com.splicemachine.db.impl.sql.catalog;

import com.splicemachine.db.catalog.UUID;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.sql.dictionary.ColumnDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.MonGetConnectionDescriptor;
import com.splicemachine.db.iapi.sql.dictionary.SystemColumn;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.sql.execute.ExecutionFactory;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.db.iapi.types.*;
import splice.com.google.common.collect.Lists;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Created by zli on 8/10/20.
 */
public class SYSMONGETCONNECTIONViewInfoProvider implements ViewInfoProvider {
    public static final String		TABLENAME_STRING = "MON_GET_CONNECTION";

    public static final int		COLUMN_COUNT = 378;

    public static final int      APPLICATION_HANDLE = 1;
    public static final int      APPLICATION_NAME = 2;
    public static final int      APPLICATION_ID = 3;
    public static final int      MEMBER = 4;
    public static final int      CLIENT_WRKSTNNAME = 5;
    public static final int      CLIENT_ACCTNG = 6;
    public static final int      CLIENT_USERID = 7;
    public static final int      CLIENT_APPLNAME = 8;
    public static final int      CLIENT_PID = 9;
    public static final int      CLIENT_PRDID = 10;
    public static final int      CLIENT_PLATFORM = 11;
    public static final int      CLIENT_PROTOCOL = 12;
    public static final int      SYSTEM_AUTH_ID = 13;
    public static final int      SESSION_AUTH_ID = 14;
    public static final int      COORD_MEMBER = 15;
    public static final int      CONNECTION_START_TIME = 16;
    public static final int      ACT_ABORTED_TOTAL = 17;
    public static final int      ACT_COMPLETED_TOTAL = 18;
    public static final int      ACT_REJECTED_TOTAL = 19;
    public static final int      AGENT_WAIT_TIME = 20;
    public static final int      AGENT_WAITS_TOTAL = 21;
    public static final int      POOL_DATA_L_READS = 22;
    public static final int      POOL_INDEX_L_READS = 23;
    public static final int      POOL_TEMP_DATA_L_READS = 24;
    public static final int      POOL_TEMP_INDEX_L_READS = 25;
    public static final int      POOL_TEMP_XDA_L_READS = 26;
    public static final int      POOL_XDA_L_READS = 27;
    public static final int      POOL_DATA_P_READS = 28;
    public static final int      POOL_INDEX_P_READS = 29;
    public static final int      POOL_TEMP_DATA_P_READS = 30;
    public static final int      POOL_TEMP_INDEX_P_READS = 31;
    public static final int      POOL_TEMP_XDA_P_READS = 32;
    public static final int      POOL_XDA_P_READS = 33;
    public static final int      POOL_DATA_WRITES = 34;
    public static final int      POOL_INDEX_WRITES = 35;
    public static final int      POOL_XDA_WRITES = 36;
    public static final int      POOL_READ_TIME = 37;
    public static final int      POOL_WRITE_TIME = 38;
    public static final int      CLIENT_IDLE_WAIT_TIME = 39;
    public static final int      DEADLOCKS = 40;
    public static final int      DIRECT_READS = 41;
    public static final int      DIRECT_READ_TIME = 42;
    public static final int      DIRECT_WRITES = 43;
    public static final int      DIRECT_WRITE_TIME = 44;
    public static final int      DIRECT_READ_REQS = 45;
    public static final int      DIRECT_WRITE_REQS = 46;
    public static final int      FCM_RECV_VOLUME = 47;
    public static final int      FCM_RECVS_TOTAL = 48;
    public static final int      FCM_SEND_VOLUME = 49;
    public static final int      FCM_SENDS_TOTAL = 50;
    public static final int      FCM_RECV_WAIT_TIME = 51;
    public static final int      FCM_SEND_WAIT_TIME = 52;
    public static final int      IPC_RECV_VOLUME = 53;
    public static final int      IPC_RECV_WAIT_TIME = 54;
    public static final int      IPC_RECVS_TOTAL = 55;
    public static final int      IPC_SEND_VOLUME = 56;
    public static final int      IPC_SEND_WAIT_TIME = 57;
    public static final int      IPC_SENDS_TOTAL = 58;
    public static final int      LOCK_ESCALS = 59;
    public static final int      LOCK_TIMEOUTS = 60;
    public static final int      LOCK_WAIT_TIME = 61;
    public static final int      LOCK_WAITS = 62;
    public static final int      LOG_BUFFER_WAIT_TIME = 63;
    public static final int      NUM_LOG_BUFFER_FULL = 64;
    public static final int      LOG_DISK_WAIT_TIME = 65;
    public static final int      LOG_DISK_WAITS_TOTAL = 66;
    public static final int      NUM_LOCKS_HELD = 67;
    public static final int      RQSTS_COMPLETED_TOTAL = 68;
    public static final int      ROWS_MODIFIED = 69;
    public static final int      ROWS_READ = 70;
    public static final int      ROWS_RETURNED = 71;
    public static final int      TCPIP_RECV_VOLUME = 72;
    public static final int      TCPIP_SEND_VOLUME = 73;
    public static final int      TCPIP_RECV_WAIT_TIME = 74;
    public static final int      TCPIP_RECVS_TOTAL = 75;
    public static final int      TCPIP_SEND_WAIT_TIME = 76;
    public static final int      TCPIP_SENDS_TOTAL = 77;
    public static final int      TOTAL_APP_RQST_TIME = 78;
    public static final int      TOTAL_RQST_TIME = 79;
    public static final int      WLM_QUEUE_TIME_TOTAL = 80;
    public static final int      WLM_QUEUE_ASSIGNMENTS_TOTAL = 81;
    public static final int      TOTAL_CPU_TIME = 82;
    public static final int      TOTAL_WAIT_TIME = 83;
    public static final int      APP_RQSTS_COMPLETED_TOTAL = 84;
    public static final int      TOTAL_SECTION_SORT_TIME = 85;
    public static final int      TOTAL_SECTION_SORT_PROC_TIME = 86;
    public static final int      TOTAL_SECTION_SORTS = 87;
    public static final int      TOTAL_SORTS = 88;
    public static final int      POST_THRESHOLD_SORTS = 89;
    public static final int      POST_SHRTHRESHOLD_SORTS = 90;
    public static final int      SORT_OVERFLOWS = 91;
    public static final int      TOTAL_COMPILE_TIME = 92;
    public static final int      TOTAL_COMPILE_PROC_TIME = 93;
    public static final int      TOTAL_COMPILATIONS = 94;
    public static final int      TOTAL_IMPLICIT_COMPILE_TIME = 95;
    public static final int      TOTAL_IMPLICIT_COMPILE_PROC_TIME = 96;
    public static final int      TOTAL_IMPLICIT_COMPILATIONS = 97;
    public static final int      TOTAL_SECTION_TIME = 98;
    public static final int      TOTAL_SECTION_PROC_TIME = 99;
    public static final int      TOTAL_APP_SECTION_EXECUTIONS = 100;
    public static final int      TOTAL_ACT_TIME = 101;
    public static final int      TOTAL_ACT_WAIT_TIME = 102;
    public static final int      ACT_RQSTS_TOTAL = 103;
    public static final int      TOTAL_ROUTINE_TIME = 104;
    public static final int      TOTAL_ROUTINE_INVOCATIONS = 105;
    public static final int      TOTAL_COMMIT_TIME = 106;
    public static final int      TOTAL_COMMIT_PROC_TIME = 107;
    public static final int      TOTAL_APP_COMMITS = 108;
    public static final int      INT_COMMITS = 109;
    public static final int      TOTAL_ROLLBACK_TIME = 110;
    public static final int      TOTAL_ROLLBACK_PROC_TIME = 111;
    public static final int      TOTAL_APP_ROLLBACKS = 112;
    public static final int      INT_ROLLBACKS = 113;
    public static final int      TOTAL_RUNSTATS_TIME = 114;
    public static final int      TOTAL_RUNSTATS_PROC_TIME = 115;
    public static final int      TOTAL_RUNSTATS = 116;
    public static final int      TOTAL_REORG_TIME = 117;
    public static final int      TOTAL_REORG_PROC_TIME = 118;
    public static final int      TOTAL_REORGS = 119;
    public static final int      TOTAL_LOAD_TIME = 120;
    public static final int      TOTAL_LOAD_PROC_TIME = 121;
    public static final int      TOTAL_LOADS = 122;
    public static final int      CAT_CACHE_INSERTS = 123;
    public static final int      CAT_CACHE_LOOKUPS = 124;
    public static final int      PKG_CACHE_INSERTS = 125;
    public static final int      PKG_CACHE_LOOKUPS = 126;
    public static final int      THRESH_VIOLATIONS = 127;
    public static final int      NUM_LW_THRESH_EXCEEDED = 128;
    public static final int      LOCK_WAITS_GLOBAL = 129;
    public static final int      LOCK_WAIT_TIME_GLOBAL = 130;
    public static final int      LOCK_TIMEOUTS_GLOBAL = 131;
    public static final int      LOCK_ESCALS_MAXLOCKS = 132;
    public static final int      LOCK_ESCALS_LOCKLIST = 133;
    public static final int      LOCK_ESCALS_GLOBAL = 134;
    public static final int      RECLAIM_WAIT_TIME = 135;
    public static final int      SPACEMAPPAGE_RECLAIM_WAIT_TIME = 136;
    public static final int      CF_WAITS = 137;
    public static final int      CF_WAIT_TIME = 138;
    public static final int      POOL_DATA_GBP_L_READS = 139;
    public static final int      POOL_DATA_GBP_P_READS = 140;
    public static final int      POOL_DATA_LBP_PAGES_FOUND = 141;
    public static final int      POOL_DATA_GBP_INVALID_PAGES = 142;
    public static final int      POOL_INDEX_GBP_L_READS = 143;
    public static final int      POOL_INDEX_GBP_P_READS = 144;
    public static final int      POOL_INDEX_LBP_PAGES_FOUND = 145;
    public static final int      POOL_INDEX_GBP_INVALID_PAGES = 146;
    public static final int      POOL_XDA_GBP_L_READS = 147;
    public static final int      POOL_XDA_GBP_P_READS = 148;
    public static final int      POOL_XDA_LBP_PAGES_FOUND = 149;
    public static final int      POOL_XDA_GBP_INVALID_PAGES = 150;
    public static final int      AUDIT_EVENTS_TOTAL = 151;
    public static final int      AUDIT_FILE_WRITES_TOTAL = 152;
    public static final int      AUDIT_FILE_WRITE_WAIT_TIME = 153;
    public static final int      AUDIT_SUBSYSTEM_WAITS_TOTAL = 154;
    public static final int      AUDIT_SUBSYSTEM_WAIT_TIME = 155;
    public static final int      CLIENT_HOSTNAME = 156;
    public static final int      CLIENT_PORT_NUMBER = 157;
    public static final int      DIAGLOG_WRITES_TOTAL = 158;
    public static final int      DIAGLOG_WRITE_WAIT_TIME = 159;
    public static final int      FCM_MESSAGE_RECVS_TOTAL = 160;
    public static final int      FCM_MESSAGE_RECV_VOLUME = 161;
    public static final int      FCM_MESSAGE_RECV_WAIT_TIME = 162;
    public static final int      FCM_MESSAGE_SENDS_TOTAL = 163;
    public static final int      FCM_MESSAGE_SEND_VOLUME = 164;
    public static final int      FCM_MESSAGE_SEND_WAIT_TIME = 165;
    public static final int      FCM_TQ_RECVS_TOTAL = 166;
    public static final int      FCM_TQ_RECV_VOLUME = 167;
    public static final int      FCM_TQ_RECV_WAIT_TIME = 168;
    public static final int      FCM_TQ_SENDS_TOTAL = 169;
    public static final int      FCM_TQ_SEND_VOLUME = 170;
    public static final int      FCM_TQ_SEND_WAIT_TIME = 171;
    public static final int      LAST_EXECUTABLE_ID = 172;
    public static final int      LAST_REQUEST_TYPE = 173;
    public static final int      TOTAL_ROUTINE_USER_CODE_PROC_TIME = 174;
    public static final int      TOTAL_ROUTINE_USER_CODE_TIME = 175;
    public static final int      TQ_TOT_SEND_SPILLS = 176;
    public static final int      EVMON_WAIT_TIME = 177;
    public static final int      EVMON_WAITS_TOTAL = 178;
    public static final int      TOTAL_EXTENDED_LATCH_WAIT_TIME = 179;
    public static final int      TOTAL_EXTENDED_LATCH_WAITS = 180;
    public static final int      INTRA_PARALLEL_STATE = 181;
    public static final int      TOTAL_STATS_FABRICATION_TIME = 182;
    public static final int      TOTAL_STATS_FABRICATION_PROC_TIME = 183;
    public static final int      TOTAL_STATS_FABRICATIONS = 184;
    public static final int      TOTAL_SYNC_RUNSTATS_TIME = 185;
    public static final int      TOTAL_SYNC_RUNSTATS_PROC_TIME = 186;
    public static final int      TOTAL_SYNC_RUNSTATS = 187;
    public static final int      TOTAL_DISP_RUN_QUEUE_TIME = 188;
    public static final int      TOTAL_PEDS = 189;
    public static final int      DISABLED_PEDS = 190;
    public static final int      POST_THRESHOLD_PEDS = 191;
    public static final int      TOTAL_PEAS = 192;
    public static final int      POST_THRESHOLD_PEAS = 193;
    public static final int      TQ_SORT_HEAP_REQUESTS = 194;
    public static final int      TQ_SORT_HEAP_REJECTIONS = 195;
    public static final int      POOL_QUEUED_ASYNC_DATA_REQS = 196;
    public static final int      POOL_QUEUED_ASYNC_INDEX_REQS = 197;
    public static final int      POOL_QUEUED_ASYNC_XDA_REQS = 198;
    public static final int      POOL_QUEUED_ASYNC_TEMP_DATA_REQS = 199;
    public static final int      POOL_QUEUED_ASYNC_TEMP_INDEX_REQS = 200;
    public static final int      POOL_QUEUED_ASYNC_TEMP_XDA_REQS = 201;
    public static final int      POOL_QUEUED_ASYNC_OTHER_REQS = 202;
    public static final int      POOL_QUEUED_ASYNC_DATA_PAGES = 203;
    public static final int      POOL_QUEUED_ASYNC_INDEX_PAGES = 204;
    public static final int      POOL_QUEUED_ASYNC_XDA_PAGES = 205;
    public static final int      POOL_QUEUED_ASYNC_TEMP_DATA_PAGES = 206;
    public static final int      POOL_QUEUED_ASYNC_TEMP_INDEX_PAGES = 207;
    public static final int      POOL_QUEUED_ASYNC_TEMP_XDA_PAGES = 208;
    public static final int      POOL_FAILED_ASYNC_DATA_REQS = 209;
    public static final int      POOL_FAILED_ASYNC_INDEX_REQS = 210;
    public static final int      POOL_FAILED_ASYNC_XDA_REQS = 211;
    public static final int      POOL_FAILED_ASYNC_TEMP_DATA_REQS = 212;
    public static final int      POOL_FAILED_ASYNC_TEMP_INDEX_REQS = 213;
    public static final int      POOL_FAILED_ASYNC_TEMP_XDA_REQS = 214;
    public static final int      POOL_FAILED_ASYNC_OTHER_REQS = 215;
    public static final int      PREFETCH_WAIT_TIME = 216;
    public static final int      PREFETCH_WAITS = 217;
    public static final int      APP_ACT_COMPLETED_TOTAL = 218;
    public static final int      APP_ACT_ABORTED_TOTAL = 219;
    public static final int      APP_ACT_REJECTED_TOTAL = 220;
    public static final int      TOTAL_CONNECT_REQUEST_TIME = 221;
    public static final int      TOTAL_CONNECT_REQUEST_PROC_TIME = 222;
    public static final int      TOTAL_CONNECT_REQUESTS = 223;
    public static final int      TOTAL_CONNECT_AUTHENTICATION_TIME = 224;
    public static final int      TOTAL_CONNECT_AUTHENTICATION_PROC_TIME = 225;
    public static final int      TOTAL_CONNECT_AUTHENTICATIONS = 226;
    public static final int      POOL_DATA_GBP_INDEP_PAGES_FOUND_IN_LBP = 227;
    public static final int      POOL_INDEX_GBP_INDEP_PAGES_FOUND_IN_LBP = 228;
    public static final int      POOL_XDA_GBP_INDEP_PAGES_FOUND_IN_LBP = 229;
    public static final int      COMM_EXIT_WAIT_TIME = 230;
    public static final int      COMM_EXIT_WAITS = 231;
    public static final int      IDA_SEND_WAIT_TIME = 232;
    public static final int      IDA_SENDS_TOTAL = 233;
    public static final int      IDA_SEND_VOLUME = 234;
    public static final int      IDA_RECV_WAIT_TIME = 235;
    public static final int      IDA_RECVS_TOTAL = 236;
    public static final int      IDA_RECV_VOLUME = 237;
    public static final int      MEMBER_SUBSET_ID = 238;
    public static final int      IS_SYSTEM_APPL = 239;
    public static final int      LOCK_TIMEOUT_VAL = 240;
    public static final int      CURRENT_ISOLATION = 241;
    public static final int      NUM_LOCKS_WAITING = 242;
    public static final int      UOW_CLIENT_IDLE_WAIT_TIME = 243;
    public static final int      ROWS_DELETED = 244;
    public static final int      ROWS_INSERTED = 245;
    public static final int      ROWS_UPDATED = 246;
    public static final int      TOTAL_HASH_JOINS = 247;
    public static final int      TOTAL_HASH_LOOPS = 248;
    public static final int      HASH_JOIN_OVERFLOWS = 249;
    public static final int      HASH_JOIN_SMALL_OVERFLOWS = 250;
    public static final int      POST_SHRTHRESHOLD_HASH_JOINS = 251;
    public static final int      TOTAL_OLAP_FUNCS = 252;
    public static final int      OLAP_FUNC_OVERFLOWS = 253;
    public static final int      DYNAMIC_SQL_STMTS = 254;
    public static final int      STATIC_SQL_STMTS = 255;
    public static final int      FAILED_SQL_STMTS = 256;
    public static final int      SELECT_SQL_STMTS = 257;
    public static final int      UID_SQL_STMTS = 258;
    public static final int      DDL_SQL_STMTS = 259;
    public static final int      MERGE_SQL_STMTS = 260;
    public static final int      XQUERY_STMTS = 261;
    public static final int      IMPLICIT_REBINDS = 262;
    public static final int      BINDS_PRECOMPILES = 263;
    public static final int      INT_ROWS_DELETED = 264;
    public static final int      INT_ROWS_INSERTED = 265;
    public static final int      INT_ROWS_UPDATED = 266;
    public static final int      CALL_SQL_STMTS = 267;
    public static final int      POOL_COL_L_READS = 268;
    public static final int      POOL_TEMP_COL_L_READS = 269;
    public static final int      POOL_COL_P_READS = 270;
    public static final int      POOL_TEMP_COL_P_READS = 271;
    public static final int      POOL_COL_LBP_PAGES_FOUND = 272;
    public static final int      POOL_COL_WRITES = 273;
    public static final int      POOL_COL_GBP_L_READS = 274;
    public static final int      POOL_COL_GBP_P_READS = 275;
    public static final int      POOL_COL_GBP_INVALID_PAGES = 276;
    public static final int      POOL_COL_GBP_INDEP_PAGES_FOUND_IN_LBP = 277;
    public static final int      POOL_QUEUED_ASYNC_COL_REQS = 278;
    public static final int      POOL_QUEUED_ASYNC_TEMP_COL_REQS = 279;
    public static final int      POOL_QUEUED_ASYNC_COL_PAGES = 280;
    public static final int      POOL_QUEUED_ASYNC_TEMP_COL_PAGES = 281;
    public static final int      POOL_FAILED_ASYNC_COL_REQS = 282;
    public static final int      POOL_FAILED_ASYNC_TEMP_COL_REQS = 283;
    public static final int      TOTAL_COL_TIME = 284;
    public static final int      TOTAL_COL_PROC_TIME = 285;
    public static final int      TOTAL_COL_EXECUTIONS = 286;
    public static final int      CLIENT_IPADDR = 287;
    public static final int      SQL_REQS_SINCE_COMMIT = 288;
    public static final int      UOW_START_TIME = 289;
    public static final int      UOW_STOP_TIME = 290;
    public static final int      PREV_UOW_STOP_TIME = 291;
    public static final int      UOW_COMP_STATUS = 292;
    public static final int      NUM_ASSOC_AGENTS = 293;
    public static final int      ASSOCIATED_AGENTS_TOP = 294;
    public static final int      WORKLOAD_OCCURRENCE_STATE = 295;
    public static final int      POST_THRESHOLD_HASH_JOINS = 296;
    public static final int      POOL_DATA_CACHING_TIER_L_READS = 297;
    public static final int      POOL_INDEX_CACHING_TIER_L_READS = 298;
    public static final int      POOL_XDA_CACHING_TIER_L_READS = 299;
    public static final int      POOL_COL_CACHING_TIER_L_READS = 300;
    public static final int      POOL_DATA_CACHING_TIER_PAGE_WRITES = 301;
    public static final int      POOL_INDEX_CACHING_TIER_PAGE_WRITES = 302;
    public static final int      POOL_XDA_CACHING_TIER_PAGE_WRITES = 303;
    public static final int      POOL_COL_CACHING_TIER_PAGE_WRITES = 304;
    public static final int      POOL_DATA_CACHING_TIER_PAGE_UPDATES = 305;
    public static final int      POOL_INDEX_CACHING_TIER_PAGE_UPDATES = 306;
    public static final int      POOL_XDA_CACHING_TIER_PAGE_UPDATES = 307;
    public static final int      POOL_COL_CACHING_TIER_PAGE_UPDATES = 308;
    public static final int      POOL_CACHING_TIER_PAGE_READ_TIME = 309;
    public static final int      POOL_CACHING_TIER_PAGE_WRITE_TIME = 310;
    public static final int      POOL_DATA_CACHING_TIER_PAGES_FOUND = 311;
    public static final int      POOL_INDEX_CACHING_TIER_PAGES_FOUND = 312;
    public static final int      POOL_XDA_CACHING_TIER_PAGES_FOUND = 313;
    public static final int      POOL_COL_CACHING_TIER_PAGES_FOUND = 314;
    public static final int      POOL_DATA_CACHING_TIER_GBP_INVALID_PAGES = 315;
    public static final int      POOL_INDEX_CACHING_TIER_GBP_INVALID_PAGES = 316;
    public static final int      POOL_XDA_CACHING_TIER_GBP_INVALID_PAGES = 317;
    public static final int      POOL_COL_CACHING_TIER_GBP_INVALID_PAGES = 318;
    public static final int      POOL_DATA_CACHING_TIER_GBP_INDEP_PAGES_FOUND = 319;
    public static final int      POOL_INDEX_CACHING_TIER_GBP_INDEP_PAGES_FOUND = 320;
    public static final int      POOL_XDA_CACHING_TIER_GBP_INDEP_PAGES_FOUND = 321;
    public static final int      POOL_COL_CACHING_TIER_GBP_INDEP_PAGES_FOUND = 322;
    public static final int      TOTAL_HASH_GRPBYS = 323;
    public static final int      HASH_GRPBY_OVERFLOWS = 324;
    public static final int      POST_THRESHOLD_HASH_GRPBYS = 325;
    public static final int      EXECUTION_ID = 326;
    public static final int      POST_THRESHOLD_OLAP_FUNCS = 327;
    public static final int      POST_THRESHOLD_COL_VECTOR_CONSUMERS = 328;
    public static final int      TOTAL_COL_VECTOR_CONSUMERS = 329;
    public static final int      ACTIVE_HASH_GRPBYS = 330;
    public static final int      ACTIVE_HASH_JOINS = 331;
    public static final int      ACTIVE_OLAP_FUNCS = 332;
    public static final int      ACTIVE_PEAS = 333;
    public static final int      ACTIVE_PEDS = 334;
    public static final int      ACTIVE_SORT_CONSUMERS = 335;
    public static final int      ACTIVE_SORTS = 336;
    public static final int      ACTIVE_COL_VECTOR_CONSUMERS = 337;
    public static final int      SORT_HEAP_ALLOCATED = 338;
    public static final int      SORT_SHRHEAP_ALLOCATED = 339;
    public static final int      TOTAL_BACKUP_TIME = 340;
    public static final int      TOTAL_BACKUP_PROC_TIME = 341;
    public static final int      TOTAL_BACKUPS = 342;
    public static final int      TOTAL_INDEX_BUILD_TIME = 343;
    public static final int      TOTAL_INDEX_BUILD_PROC_TIME = 344;
    public static final int      TOTAL_INDEXES_BUILT = 345;
    public static final int      EXT_TABLE_RECV_WAIT_TIME = 346;
    public static final int      EXT_TABLE_RECVS_TOTAL = 347;
    public static final int      EXT_TABLE_RECV_VOLUME = 348;
    public static final int      EXT_TABLE_READ_VOLUME = 349;
    public static final int      EXT_TABLE_SEND_WAIT_TIME = 350;
    public static final int      EXT_TABLE_SENDS_TOTAL = 351;
    public static final int      EXT_TABLE_SEND_VOLUME = 352;
    public static final int      EXT_TABLE_WRITE_VOLUME = 353;
    public static final int      FCM_TQ_RECV_WAITS_TOTAL = 354;
    public static final int      FCM_MESSAGE_RECV_WAITS_TOTAL = 355;
    public static final int      FCM_TQ_SEND_WAITS_TOTAL = 356;
    public static final int      FCM_MESSAGE_SEND_WAITS_TOTAL = 357;
    public static final int      FCM_SEND_WAITS_TOTAL = 358;
    public static final int      FCM_RECV_WAITS_TOTAL = 359;
    public static final int      COL_VECTOR_CONSUMER_OVERFLOWS = 360;
    public static final int      TOTAL_COL_SYNOPSIS_TIME = 361;
    public static final int      TOTAL_COL_SYNOPSIS_PROC_TIME = 362;
    public static final int      TOTAL_COL_SYNOPSIS_EXECUTIONS = 363;
    public static final int      COL_SYNOPSIS_ROWS_INSERTED = 364;
    public static final int      LOB_PREFETCH_WAIT_TIME = 365;
    public static final int      LOB_PREFETCH_REQS = 366;
    public static final int      FED_ROWS_DELETED = 367;
    public static final int      FED_ROWS_INSERTED = 368;
    public static final int      FED_ROWS_UPDATED = 369;
    public static final int      FED_ROWS_READ = 370;
    public static final int      FED_WAIT_TIME = 371;
    public static final int      FED_WAITS_TOTAL = 372;
    public static final int      APPL_SECTION_INSERTS = 373;
    public static final int      APPL_SECTION_LOOKUPS = 374;
    public static final int      CONNECTION_REUSABILITY_STATUS = 375;
    public static final int      REUSABILITY_STATUS_REASON = 376;
    public static final int      ADM_OVERFLOWS = 377;
    public static final int      ADM_BYPASS_ACT_TOTAL = 378;

    /////////////////////////////////////////////////////////////////////////////
    //
    //	METHODS
    //
    /////////////////////////////////////////////////////////////////////////////

    /**
     * Make a MONGETCONNECTION row
     * @param row the row we want to set to become suitable for inserting into MONGETCONNECTION.
     */
    public static void makeCompileTimeRow(ExecRow row) throws StandardException {
        MonGetConnectionDescriptor.MonGetConnectionFields f = new MonGetConnectionDescriptor.MonGetConnectionFields();
        if(SanityManager.DEBUG) {
            SanityManager.ASSERT(row.nColumns() == COLUMN_COUNT);
        }
        row.setColumn(APPLICATION_HANDLE, new SQLLongint(f.applicationHandle));
        row.setColumn(APPLICATION_NAME, new SQLVarchar(f.applicationName));
        row.setColumn(APPLICATION_ID, new SQLVarchar(f.applicationId));
        row.setColumn(MEMBER, new SQLSmallint(f.member));
        row.setColumn(CLIENT_WRKSTNNAME, new SQLVarchar(f.clientWrkstnname));
        row.setColumn(CLIENT_ACCTNG, new SQLVarchar(f.clientAcctng));
        row.setColumn(CLIENT_USERID, new SQLVarchar(f.clientUserid));
        row.setColumn(CLIENT_APPLNAME, new SQLVarchar(f.clientApplname));
        row.setColumn(CLIENT_PID, new SQLLongint(f.clientPid));
        row.setColumn(CLIENT_PRDID, new SQLVarchar(f.clientPrdid));
        row.setColumn(CLIENT_PLATFORM, new SQLVarchar(f.clientPlatform));
        row.setColumn(CLIENT_PROTOCOL, new SQLVarchar(f.clientProtocol));
        row.setColumn(SYSTEM_AUTH_ID, new SQLVarchar(f.systemAuthId));
        row.setColumn(SESSION_AUTH_ID, new SQLVarchar(f.sessionAuthId));
        row.setColumn(COORD_MEMBER, new SQLSmallint(f.coordMember));
        row.setColumn(CONNECTION_START_TIME, new SQLTimestamp(f.connectionStartTime));
        row.setColumn(ACT_ABORTED_TOTAL, new SQLLongint(f.actAbortedTotal));
        row.setColumn(ACT_COMPLETED_TOTAL, new SQLLongint(f.actCompletedTotal));
        row.setColumn(ACT_REJECTED_TOTAL, new SQLLongint(f.actRejectedTotal));
        row.setColumn(AGENT_WAIT_TIME, new SQLLongint(f.agentWaitTime));
        row.setColumn(AGENT_WAITS_TOTAL, new SQLLongint(f.agentWaitsTotal));
        row.setColumn(POOL_DATA_L_READS, new SQLLongint(f.poolDataLReads));
        row.setColumn(POOL_INDEX_L_READS, new SQLLongint(f.poolIndexLReads));
        row.setColumn(POOL_TEMP_DATA_L_READS, new SQLLongint(f.poolTempDataLReads));
        row.setColumn(POOL_TEMP_INDEX_L_READS, new SQLLongint(f.poolTempIndexLReads));
        row.setColumn(POOL_TEMP_XDA_L_READS, new SQLLongint(f.poolTempXdaLReads));
        row.setColumn(POOL_XDA_L_READS, new SQLLongint(f.poolXdaLReads));
        row.setColumn(POOL_DATA_P_READS, new SQLLongint(f.poolDataPReads));
        row.setColumn(POOL_INDEX_P_READS, new SQLLongint(f.poolIndexPReads));
        row.setColumn(POOL_TEMP_DATA_P_READS, new SQLLongint(f.poolTempDataPReads));
        row.setColumn(POOL_TEMP_INDEX_P_READS, new SQLLongint(f.poolTempIndexPReads));
        row.setColumn(POOL_TEMP_XDA_P_READS, new SQLLongint(f.poolTempXdaPReads));
        row.setColumn(POOL_XDA_P_READS, new SQLLongint(f.poolXdaPReads));
        row.setColumn(POOL_DATA_WRITES, new SQLLongint(f.poolDataWrites));
        row.setColumn(POOL_INDEX_WRITES, new SQLLongint(f.poolIndexWrites));
        row.setColumn(POOL_XDA_WRITES, new SQLLongint(f.poolXdaWrites));
        row.setColumn(POOL_READ_TIME, new SQLLongint(f.poolReadTime));
        row.setColumn(POOL_WRITE_TIME, new SQLLongint(f.poolWriteTime));
        row.setColumn(CLIENT_IDLE_WAIT_TIME, new SQLLongint(f.clientIdleWaitTime));
        row.setColumn(DEADLOCKS, new SQLLongint(f.deadlocks));
        row.setColumn(DIRECT_READS, new SQLLongint(f.directReads));
        row.setColumn(DIRECT_READ_TIME, new SQLLongint(f.directReadTime));
        row.setColumn(DIRECT_WRITES, new SQLLongint(f.directWrites));
        row.setColumn(DIRECT_WRITE_TIME, new SQLLongint(f.directWriteTime));
        row.setColumn(DIRECT_READ_REQS, new SQLLongint(f.directReadReqs));
        row.setColumn(DIRECT_WRITE_REQS, new SQLLongint(f.directWriteReqs));
        row.setColumn(FCM_RECV_VOLUME, new SQLLongint(f.fcmRecvVolume));
        row.setColumn(FCM_RECVS_TOTAL, new SQLLongint(f.fcmRecvsTotal));
        row.setColumn(FCM_SEND_VOLUME, new SQLLongint(f.fcmSendVolume));
        row.setColumn(FCM_SENDS_TOTAL, new SQLLongint(f.fcmSendsTotal));
        row.setColumn(FCM_RECV_WAIT_TIME, new SQLLongint(f.fcmRecvWaitTime));
        row.setColumn(FCM_SEND_WAIT_TIME, new SQLLongint(f.fcmSendWaitTime));
        row.setColumn(IPC_RECV_VOLUME, new SQLLongint(f.ipcRecvVolume));
        row.setColumn(IPC_RECV_WAIT_TIME, new SQLLongint(f.ipcRecvWaitTime));
        row.setColumn(IPC_RECVS_TOTAL, new SQLLongint(f.ipcRecvsTotal));
        row.setColumn(IPC_SEND_VOLUME, new SQLLongint(f.ipcSendVolume));
        row.setColumn(IPC_SEND_WAIT_TIME, new SQLLongint(f.ipcSendWaitTime));
        row.setColumn(IPC_SENDS_TOTAL, new SQLLongint(f.ipcSendsTotal));
        row.setColumn(LOCK_ESCALS, new SQLLongint(f.lockEscals));
        row.setColumn(LOCK_TIMEOUTS, new SQLLongint(f.lockTimeouts));
        row.setColumn(LOCK_WAIT_TIME, new SQLLongint(f.lockWaitTime));
        row.setColumn(LOCK_WAITS, new SQLLongint(f.lockWaits));
        row.setColumn(LOG_BUFFER_WAIT_TIME, new SQLLongint(f.logBufferWaitTime));
        row.setColumn(NUM_LOG_BUFFER_FULL, new SQLLongint(f.numLogBufferFull));
        row.setColumn(LOG_DISK_WAIT_TIME, new SQLLongint(f.logDiskWaitTime));
        row.setColumn(LOG_DISK_WAITS_TOTAL, new SQLLongint(f.logDiskWaitsTotal));
        row.setColumn(NUM_LOCKS_HELD, new SQLLongint(f.numLocksHeld));
        row.setColumn(RQSTS_COMPLETED_TOTAL, new SQLLongint(f.rqstsCompletedTotal));
        row.setColumn(ROWS_MODIFIED, new SQLLongint(f.rowsModified));
        row.setColumn(ROWS_READ, new SQLLongint(f.rowsRead));
        row.setColumn(ROWS_RETURNED, new SQLLongint(f.rowsReturned));
        row.setColumn(TCPIP_RECV_VOLUME, new SQLLongint(f.tcpipRecvVolume));
        row.setColumn(TCPIP_SEND_VOLUME, new SQLLongint(f.tcpipSendVolume));
        row.setColumn(TCPIP_RECV_WAIT_TIME, new SQLLongint(f.tcpipRecvWaitTime));
        row.setColumn(TCPIP_RECVS_TOTAL, new SQLLongint(f.tcpipRecvsTotal));
        row.setColumn(TCPIP_SEND_WAIT_TIME, new SQLLongint(f.tcpipSendWaitTime));
        row.setColumn(TCPIP_SENDS_TOTAL, new SQLLongint(f.tcpipSendsTotal));
        row.setColumn(TOTAL_APP_RQST_TIME, new SQLLongint(f.totalAppRqstTime));
        row.setColumn(TOTAL_RQST_TIME, new SQLLongint(f.totalRqstTime));
        row.setColumn(WLM_QUEUE_TIME_TOTAL, new SQLLongint(f.wlmQueueTimeTotal));
        row.setColumn(WLM_QUEUE_ASSIGNMENTS_TOTAL, new SQLLongint(f.wlmQueueAssignmentsTotal));
        row.setColumn(TOTAL_CPU_TIME, new SQLLongint(f.totalCpuTime));
        row.setColumn(TOTAL_WAIT_TIME, new SQLLongint(f.totalWaitTime));
        row.setColumn(APP_RQSTS_COMPLETED_TOTAL, new SQLLongint(f.appRqstsCompletedTotal));
        row.setColumn(TOTAL_SECTION_SORT_TIME, new SQLLongint(f.totalSectionSortTime));
        row.setColumn(TOTAL_SECTION_SORT_PROC_TIME, new SQLLongint(f.totalSectionSortProcTime));
        row.setColumn(TOTAL_SECTION_SORTS, new SQLLongint(f.totalSectionSorts));
        row.setColumn(TOTAL_SORTS, new SQLLongint(f.totalSorts));
        row.setColumn(POST_THRESHOLD_SORTS, new SQLLongint(f.postThresholdSorts));
        row.setColumn(POST_SHRTHRESHOLD_SORTS, new SQLLongint(f.postShrthresholdSorts));
        row.setColumn(SORT_OVERFLOWS, new SQLLongint(f.sortOverflows));
        row.setColumn(TOTAL_COMPILE_TIME, new SQLLongint(f.totalCompileTime));
        row.setColumn(TOTAL_COMPILE_PROC_TIME, new SQLLongint(f.totalCompileProcTime));
        row.setColumn(TOTAL_COMPILATIONS, new SQLLongint(f.totalCompilations));
        row.setColumn(TOTAL_IMPLICIT_COMPILE_TIME, new SQLLongint(f.totalImplicitCompileTime));
        row.setColumn(TOTAL_IMPLICIT_COMPILE_PROC_TIME, new SQLLongint(f.totalImplicitCompileProcTime));
        row.setColumn(TOTAL_IMPLICIT_COMPILATIONS, new SQLLongint(f.totalImplicitCompilations));
        row.setColumn(TOTAL_SECTION_TIME, new SQLLongint(f.totalSectionTime));
        row.setColumn(TOTAL_SECTION_PROC_TIME, new SQLLongint(f.totalSectionProcTime));
        row.setColumn(TOTAL_APP_SECTION_EXECUTIONS, new SQLLongint(f.totalAppSectionExecutions));
        row.setColumn(TOTAL_ACT_TIME, new SQLLongint(f.totalActTime));
        row.setColumn(TOTAL_ACT_WAIT_TIME, new SQLLongint(f.totalActWaitTime));
        row.setColumn(ACT_RQSTS_TOTAL, new SQLLongint(f.actRqstsTotal));
        row.setColumn(TOTAL_ROUTINE_TIME, new SQLLongint(f.totalRoutineTime));
        row.setColumn(TOTAL_ROUTINE_INVOCATIONS, new SQLLongint(f.totalRoutineInvocations));
        row.setColumn(TOTAL_COMMIT_TIME, new SQLLongint(f.totalCommitTime));
        row.setColumn(TOTAL_COMMIT_PROC_TIME, new SQLLongint(f.totalCommitProcTime));
        row.setColumn(TOTAL_APP_COMMITS, new SQLLongint(f.totalAppCommits));
        row.setColumn(INT_COMMITS, new SQLLongint(f.intCommits));
        row.setColumn(TOTAL_ROLLBACK_TIME, new SQLLongint(f.totalRollbackTime));
        row.setColumn(TOTAL_ROLLBACK_PROC_TIME, new SQLLongint(f.totalRollbackProcTime));
        row.setColumn(TOTAL_APP_ROLLBACKS, new SQLLongint(f.totalAppRollbacks));
        row.setColumn(INT_ROLLBACKS, new SQLLongint(f.intRollbacks));
        row.setColumn(TOTAL_RUNSTATS_TIME, new SQLLongint(f.totalRunstatsTime));
        row.setColumn(TOTAL_RUNSTATS_PROC_TIME, new SQLLongint(f.totalRunstatsProcTime));
        row.setColumn(TOTAL_RUNSTATS, new SQLLongint(f.totalRunstats));
        row.setColumn(TOTAL_REORG_TIME, new SQLLongint(f.totalReorgTime));
        row.setColumn(TOTAL_REORG_PROC_TIME, new SQLLongint(f.totalReorgProcTime));
        row.setColumn(TOTAL_REORGS, new SQLLongint(f.totalReorgs));
        row.setColumn(TOTAL_LOAD_TIME, new SQLLongint(f.totalLoadTime));
        row.setColumn(TOTAL_LOAD_PROC_TIME, new SQLLongint(f.totalLoadProcTime));
        row.setColumn(TOTAL_LOADS, new SQLLongint(f.totalLoads));
        row.setColumn(CAT_CACHE_INSERTS, new SQLLongint(f.catCacheInserts));
        row.setColumn(CAT_CACHE_LOOKUPS, new SQLLongint(f.catCacheLookups));
        row.setColumn(PKG_CACHE_INSERTS, new SQLLongint(f.pkgCacheInserts));
        row.setColumn(PKG_CACHE_LOOKUPS, new SQLLongint(f.pkgCacheLookups));
        row.setColumn(THRESH_VIOLATIONS, new SQLLongint(f.threshViolations));
        row.setColumn(NUM_LW_THRESH_EXCEEDED, new SQLLongint(f.numLwThreshExceeded));
        row.setColumn(LOCK_WAITS_GLOBAL, new SQLLongint(f.lockWaitsGlobal));
        row.setColumn(LOCK_WAIT_TIME_GLOBAL, new SQLLongint(f.lockWaitTimeGlobal));
        row.setColumn(LOCK_TIMEOUTS_GLOBAL, new SQLLongint(f.lockTimeoutsGlobal));
        row.setColumn(LOCK_ESCALS_MAXLOCKS, new SQLLongint(f.lockEscalsMaxlocks));
        row.setColumn(LOCK_ESCALS_LOCKLIST, new SQLLongint(f.lockEscalsLocklist));
        row.setColumn(LOCK_ESCALS_GLOBAL, new SQLLongint(f.lockEscalsGlobal));
        row.setColumn(RECLAIM_WAIT_TIME, new SQLLongint(f.reclaimWaitTime));
        row.setColumn(SPACEMAPPAGE_RECLAIM_WAIT_TIME, new SQLLongint(f.spacemappageReclaimWaitTime));
        row.setColumn(CF_WAITS, new SQLLongint(f.cfWaits));
        row.setColumn(CF_WAIT_TIME, new SQLLongint(f.cfWaitTime));
        row.setColumn(POOL_DATA_GBP_L_READS, new SQLLongint(f.poolDataGbpLReads));
        row.setColumn(POOL_DATA_GBP_P_READS, new SQLLongint(f.poolDataGbpPReads));
        row.setColumn(POOL_DATA_LBP_PAGES_FOUND, new SQLLongint(f.poolDataLbpPagesFound));
        row.setColumn(POOL_DATA_GBP_INVALID_PAGES, new SQLLongint(f.poolDataGbpInvalidPages));
        row.setColumn(POOL_INDEX_GBP_L_READS, new SQLLongint(f.poolIndexGbpLReads));
        row.setColumn(POOL_INDEX_GBP_P_READS, new SQLLongint(f.poolIndexGbpPReads));
        row.setColumn(POOL_INDEX_LBP_PAGES_FOUND, new SQLLongint(f.poolIndexLbpPagesFound));
        row.setColumn(POOL_INDEX_GBP_INVALID_PAGES, new SQLLongint(f.poolIndexGbpInvalidPages));
        row.setColumn(POOL_XDA_GBP_L_READS, new SQLLongint(f.poolXdaGbpLReads));
        row.setColumn(POOL_XDA_GBP_P_READS, new SQLLongint(f.poolXdaGbpPReads));
        row.setColumn(POOL_XDA_LBP_PAGES_FOUND, new SQLLongint(f.poolXdaLbpPagesFound));
        row.setColumn(POOL_XDA_GBP_INVALID_PAGES, new SQLLongint(f.poolXdaGbpInvalidPages));
        row.setColumn(AUDIT_EVENTS_TOTAL, new SQLLongint(f.auditEventsTotal));
        row.setColumn(AUDIT_FILE_WRITES_TOTAL, new SQLLongint(f.auditFileWritesTotal));
        row.setColumn(AUDIT_FILE_WRITE_WAIT_TIME, new SQLLongint(f.auditFileWriteWaitTime));
        row.setColumn(AUDIT_SUBSYSTEM_WAITS_TOTAL, new SQLLongint(f.auditSubsystemWaitsTotal));
        row.setColumn(AUDIT_SUBSYSTEM_WAIT_TIME, new SQLLongint(f.auditSubsystemWaitTime));
        row.setColumn(CLIENT_HOSTNAME, new SQLVarchar(f.clientHostname));
        row.setColumn(CLIENT_PORT_NUMBER, new SQLInteger(f.clientPortNumber));
        row.setColumn(DIAGLOG_WRITES_TOTAL, new SQLLongint(f.diaglogWritesTotal));
        row.setColumn(DIAGLOG_WRITE_WAIT_TIME, new SQLLongint(f.diaglogWriteWaitTime));
        row.setColumn(FCM_MESSAGE_RECVS_TOTAL, new SQLLongint(f.fcmMessageRecvsTotal));
        row.setColumn(FCM_MESSAGE_RECV_VOLUME, new SQLLongint(f.fcmMessageRecvVolume));
        row.setColumn(FCM_MESSAGE_RECV_WAIT_TIME, new SQLLongint(f.fcmMessageRecvWaitTime));
        row.setColumn(FCM_MESSAGE_SENDS_TOTAL, new SQLLongint(f.fcmMessageSendsTotal));
        row.setColumn(FCM_MESSAGE_SEND_VOLUME, new SQLLongint(f.fcmMessageSendVolume));
        row.setColumn(FCM_MESSAGE_SEND_WAIT_TIME, new SQLLongint(f.fcmMessageSendWaitTime));
        row.setColumn(FCM_TQ_RECVS_TOTAL, new SQLLongint(f.fcmTqRecvsTotal));
        row.setColumn(FCM_TQ_RECV_VOLUME, new SQLLongint(f.fcmTqRecvVolume));
        row.setColumn(FCM_TQ_RECV_WAIT_TIME, new SQLLongint(f.fcmTqRecvWaitTime));
        row.setColumn(FCM_TQ_SENDS_TOTAL, new SQLLongint(f.fcmTqSendsTotal));
        row.setColumn(FCM_TQ_SEND_VOLUME, new SQLLongint(f.fcmTqSendVolume));
        row.setColumn(FCM_TQ_SEND_WAIT_TIME, new SQLLongint(f.fcmTqSendWaitTime));
        row.setColumn(LAST_EXECUTABLE_ID, new SQLVarchar(f.lastExecutableId));
        row.setColumn(LAST_REQUEST_TYPE, new SQLVarchar(f.lastRequestType));
        row.setColumn(TOTAL_ROUTINE_USER_CODE_PROC_TIME, new SQLLongint(f.totalRoutineUserCodeProcTime));
        row.setColumn(TOTAL_ROUTINE_USER_CODE_TIME, new SQLLongint(f.totalRoutineUserCodeTime));
        row.setColumn(TQ_TOT_SEND_SPILLS, new SQLLongint(f.tqTotSendSpills));
        row.setColumn(EVMON_WAIT_TIME, new SQLLongint(f.evmonWaitTime));
        row.setColumn(EVMON_WAITS_TOTAL, new SQLLongint(f.evmonWaitsTotal));
        row.setColumn(TOTAL_EXTENDED_LATCH_WAIT_TIME, new SQLLongint(f.totalExtendedLatchWaitTime));
        row.setColumn(TOTAL_EXTENDED_LATCH_WAITS, new SQLLongint(f.totalExtendedLatchWaits));
        row.setColumn(INTRA_PARALLEL_STATE, new SQLVarchar(f.intraParallelState));
        row.setColumn(TOTAL_STATS_FABRICATION_TIME, new SQLLongint(f.totalStatsFabricationTime));
        row.setColumn(TOTAL_STATS_FABRICATION_PROC_TIME, new SQLLongint(f.totalStatsFabricationProcTime));
        row.setColumn(TOTAL_STATS_FABRICATIONS, new SQLLongint(f.totalStatsFabrications));
        row.setColumn(TOTAL_SYNC_RUNSTATS_TIME, new SQLLongint(f.totalSyncRunstatsTime));
        row.setColumn(TOTAL_SYNC_RUNSTATS_PROC_TIME, new SQLLongint(f.totalSyncRunstatsProcTime));
        row.setColumn(TOTAL_SYNC_RUNSTATS, new SQLLongint(f.totalSyncRunstats));
        row.setColumn(TOTAL_DISP_RUN_QUEUE_TIME, new SQLLongint(f.totalDispRunQueueTime));
        row.setColumn(TOTAL_PEDS, new SQLLongint(f.totalPeds));
        row.setColumn(DISABLED_PEDS, new SQLLongint(f.disabledPeds));
        row.setColumn(POST_THRESHOLD_PEDS, new SQLLongint(f.postThresholdPeds));
        row.setColumn(TOTAL_PEAS, new SQLLongint(f.totalPeas));
        row.setColumn(POST_THRESHOLD_PEAS, new SQLLongint(f.postThresholdPeas));
        row.setColumn(TQ_SORT_HEAP_REQUESTS, new SQLLongint(f.tqSortHeapRequests));
        row.setColumn(TQ_SORT_HEAP_REJECTIONS, new SQLLongint(f.tqSortHeapRejections));
        row.setColumn(POOL_QUEUED_ASYNC_DATA_REQS, new SQLLongint(f.poolQueuedAsyncDataReqs));
        row.setColumn(POOL_QUEUED_ASYNC_INDEX_REQS, new SQLLongint(f.poolQueuedAsyncIndexReqs));
        row.setColumn(POOL_QUEUED_ASYNC_XDA_REQS, new SQLLongint(f.poolQueuedAsyncXdaReqs));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_DATA_REQS, new SQLLongint(f.poolQueuedAsyncTempDataReqs));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_INDEX_REQS, new SQLLongint(f.poolQueuedAsyncTempIndexReqs));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_XDA_REQS, new SQLLongint(f.poolQueuedAsyncTempXdaReqs));
        row.setColumn(POOL_QUEUED_ASYNC_OTHER_REQS, new SQLLongint(f.poolQueuedAsyncOtherReqs));
        row.setColumn(POOL_QUEUED_ASYNC_DATA_PAGES, new SQLLongint(f.poolQueuedAsyncDataPages));
        row.setColumn(POOL_QUEUED_ASYNC_INDEX_PAGES, new SQLLongint(f.poolQueuedAsyncIndexPages));
        row.setColumn(POOL_QUEUED_ASYNC_XDA_PAGES, new SQLLongint(f.poolQueuedAsyncXdaPages));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_DATA_PAGES, new SQLLongint(f.poolQueuedAsyncTempDataPages));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_INDEX_PAGES, new SQLLongint(f.poolQueuedAsyncTempIndexPages));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_XDA_PAGES, new SQLLongint(f.poolQueuedAsyncTempXdaPages));
        row.setColumn(POOL_FAILED_ASYNC_DATA_REQS, new SQLLongint(f.poolFailedAsyncDataReqs));
        row.setColumn(POOL_FAILED_ASYNC_INDEX_REQS, new SQLLongint(f.poolFailedAsyncIndexReqs));
        row.setColumn(POOL_FAILED_ASYNC_XDA_REQS, new SQLLongint(f.poolFailedAsyncXdaReqs));
        row.setColumn(POOL_FAILED_ASYNC_TEMP_DATA_REQS, new SQLLongint(f.poolFailedAsyncTempDataReqs));
        row.setColumn(POOL_FAILED_ASYNC_TEMP_INDEX_REQS, new SQLLongint(f.poolFailedAsyncTempIndexReqs));
        row.setColumn(POOL_FAILED_ASYNC_TEMP_XDA_REQS, new SQLLongint(f.poolFailedAsyncTempXdaReqs));
        row.setColumn(POOL_FAILED_ASYNC_OTHER_REQS, new SQLLongint(f.poolFailedAsyncOtherReqs));
        row.setColumn(PREFETCH_WAIT_TIME, new SQLLongint(f.prefetchWaitTime));
        row.setColumn(PREFETCH_WAITS, new SQLLongint(f.prefetchWaits));
        row.setColumn(APP_ACT_COMPLETED_TOTAL, new SQLLongint(f.appActCompletedTotal));
        row.setColumn(APP_ACT_ABORTED_TOTAL, new SQLLongint(f.appActAbortedTotal));
        row.setColumn(APP_ACT_REJECTED_TOTAL, new SQLLongint(f.appActRejectedTotal));
        row.setColumn(TOTAL_CONNECT_REQUEST_TIME, new SQLLongint(f.totalConnectRequestTime));
        row.setColumn(TOTAL_CONNECT_REQUEST_PROC_TIME, new SQLLongint(f.totalConnectRequestProcTime));
        row.setColumn(TOTAL_CONNECT_REQUESTS, new SQLLongint(f.totalConnectRequests));
        row.setColumn(TOTAL_CONNECT_AUTHENTICATION_TIME, new SQLLongint(f.totalConnectAuthenticationTime));
        row.setColumn(TOTAL_CONNECT_AUTHENTICATION_PROC_TIME, new SQLLongint(f.totalConnectAuthenticationProcTime));
        row.setColumn(TOTAL_CONNECT_AUTHENTICATIONS, new SQLLongint(f.totalConnectAuthentications));
        row.setColumn(POOL_DATA_GBP_INDEP_PAGES_FOUND_IN_LBP, new SQLLongint(f.poolDataGbpIndepPagesFoundInLbp));
        row.setColumn(POOL_INDEX_GBP_INDEP_PAGES_FOUND_IN_LBP, new SQLLongint(f.poolIndexGbpIndepPagesFoundInLbp));
        row.setColumn(POOL_XDA_GBP_INDEP_PAGES_FOUND_IN_LBP, new SQLLongint(f.poolXdaGbpIndepPagesFoundInLbp));
        row.setColumn(COMM_EXIT_WAIT_TIME, new SQLLongint(f.commExitWaitTime));
        row.setColumn(COMM_EXIT_WAITS, new SQLLongint(f.commExitWaits));
        row.setColumn(IDA_SEND_WAIT_TIME, new SQLLongint(f.idaSendWaitTime));
        row.setColumn(IDA_SENDS_TOTAL, new SQLLongint(f.idaSendsTotal));
        row.setColumn(IDA_SEND_VOLUME, new SQLLongint(f.idaSendVolume));
        row.setColumn(IDA_RECV_WAIT_TIME, new SQLLongint(f.idaRecvWaitTime));
        row.setColumn(IDA_RECVS_TOTAL, new SQLLongint(f.idaRecvsTotal));
        row.setColumn(IDA_RECV_VOLUME, new SQLLongint(f.idaRecvVolume));
        row.setColumn(MEMBER_SUBSET_ID, new SQLInteger(f.memberSubsetId));
        row.setColumn(IS_SYSTEM_APPL, new SQLSmallint(f.isSystemAppl));
        row.setColumn(LOCK_TIMEOUT_VAL, new SQLLongint(f.lockTimeoutVal));
        row.setColumn(CURRENT_ISOLATION, new SQLChar(f.currentIsolation));
        row.setColumn(NUM_LOCKS_WAITING, new SQLLongint(f.numLocksWaiting));
        row.setColumn(UOW_CLIENT_IDLE_WAIT_TIME, new SQLLongint(f.uowClientIdleWaitTime));
        row.setColumn(ROWS_DELETED, new SQLLongint(f.rowsDeleted));
        row.setColumn(ROWS_INSERTED, new SQLLongint(f.rowsInserted));
        row.setColumn(ROWS_UPDATED, new SQLLongint(f.rowsUpdated));
        row.setColumn(TOTAL_HASH_JOINS, new SQLLongint(f.totalHashJoins));
        row.setColumn(TOTAL_HASH_LOOPS, new SQLLongint(f.totalHashLoops));
        row.setColumn(HASH_JOIN_OVERFLOWS, new SQLLongint(f.hashJoinOverflows));
        row.setColumn(HASH_JOIN_SMALL_OVERFLOWS, new SQLLongint(f.hashJoinSmallOverflows));
        row.setColumn(POST_SHRTHRESHOLD_HASH_JOINS, new SQLLongint(f.postShrthresholdHashJoins));
        row.setColumn(TOTAL_OLAP_FUNCS, new SQLLongint(f.totalOlapFuncs));
        row.setColumn(OLAP_FUNC_OVERFLOWS, new SQLLongint(f.olapFuncOverflows));
        row.setColumn(DYNAMIC_SQL_STMTS, new SQLLongint(f.dynamicSqlStmts));
        row.setColumn(STATIC_SQL_STMTS, new SQLLongint(f.staticSqlStmts));
        row.setColumn(FAILED_SQL_STMTS, new SQLLongint(f.failedSqlStmts));
        row.setColumn(SELECT_SQL_STMTS, new SQLLongint(f.selectSqlStmts));
        row.setColumn(UID_SQL_STMTS, new SQLLongint(f.uidSqlStmts));
        row.setColumn(DDL_SQL_STMTS, new SQLLongint(f.ddlSqlStmts));
        row.setColumn(MERGE_SQL_STMTS, new SQLLongint(f.mergeSqlStmts));
        row.setColumn(XQUERY_STMTS, new SQLLongint(f.xqueryStmts));
        row.setColumn(IMPLICIT_REBINDS, new SQLLongint(f.implicitRebinds));
        row.setColumn(BINDS_PRECOMPILES, new SQLLongint(f.bindsPrecompiles));
        row.setColumn(INT_ROWS_DELETED, new SQLLongint(f.intRowsDeleted));
        row.setColumn(INT_ROWS_INSERTED, new SQLLongint(f.intRowsInserted));
        row.setColumn(INT_ROWS_UPDATED, new SQLLongint(f.intRowsUpdated));
        row.setColumn(CALL_SQL_STMTS, new SQLLongint(f.callSqlStmts));
        row.setColumn(POOL_COL_L_READS, new SQLLongint(f.poolColLReads));
        row.setColumn(POOL_TEMP_COL_L_READS, new SQLLongint(f.poolTempColLReads));
        row.setColumn(POOL_COL_P_READS, new SQLLongint(f.poolColPReads));
        row.setColumn(POOL_TEMP_COL_P_READS, new SQLLongint(f.poolTempColPReads));
        row.setColumn(POOL_COL_LBP_PAGES_FOUND, new SQLLongint(f.poolColLbpPagesFound));
        row.setColumn(POOL_COL_WRITES, new SQLLongint(f.poolColWrites));
        row.setColumn(POOL_COL_GBP_L_READS, new SQLLongint(f.poolColGbpLReads));
        row.setColumn(POOL_COL_GBP_P_READS, new SQLLongint(f.poolColGbpPReads));
        row.setColumn(POOL_COL_GBP_INVALID_PAGES, new SQLLongint(f.poolColGbpInvalidPages));
        row.setColumn(POOL_COL_GBP_INDEP_PAGES_FOUND_IN_LBP, new SQLLongint(f.poolColGbpIndepPagesFoundInLbp));
        row.setColumn(POOL_QUEUED_ASYNC_COL_REQS, new SQLLongint(f.poolQueuedAsyncColReqs));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_COL_REQS, new SQLLongint(f.poolQueuedAsyncTempColReqs));
        row.setColumn(POOL_QUEUED_ASYNC_COL_PAGES, new SQLLongint(f.poolQueuedAsyncColPages));
        row.setColumn(POOL_QUEUED_ASYNC_TEMP_COL_PAGES, new SQLLongint(f.poolQueuedAsyncTempColPages));
        row.setColumn(POOL_FAILED_ASYNC_COL_REQS, new SQLLongint(f.poolFailedAsyncColReqs));
        row.setColumn(POOL_FAILED_ASYNC_TEMP_COL_REQS, new SQLLongint(f.poolFailedAsyncTempColReqs));
        row.setColumn(TOTAL_COL_TIME, new SQLLongint(f.totalColTime));
        row.setColumn(TOTAL_COL_PROC_TIME, new SQLLongint(f.totalColProcTime));
        row.setColumn(TOTAL_COL_EXECUTIONS, new SQLLongint(f.totalColExecutions));
        row.setColumn(CLIENT_IPADDR, new SQLVarchar(f.clientIpaddr));
        row.setColumn(SQL_REQS_SINCE_COMMIT, new SQLLongint(f.sqlReqsSinceCommit));
        row.setColumn(UOW_START_TIME, new SQLTimestamp(f.uowStartTime));
        row.setColumn(UOW_STOP_TIME, new SQLTimestamp(f.uowStopTime));
        row.setColumn(PREV_UOW_STOP_TIME, new SQLTimestamp(f.prevUowStopTime));
        row.setColumn(UOW_COMP_STATUS, new SQLVarchar(f.uowCompStatus));
        row.setColumn(NUM_ASSOC_AGENTS, new SQLLongint(f.numAssocAgents));
        row.setColumn(ASSOCIATED_AGENTS_TOP, new SQLLongint(f.associatedAgentsTop));
        row.setColumn(WORKLOAD_OCCURRENCE_STATE, new SQLVarchar(f.workloadOccurrenceState));
        row.setColumn(POST_THRESHOLD_HASH_JOINS, new SQLLongint(f.postThresholdHashJoins));
        row.setColumn(POOL_DATA_CACHING_TIER_L_READS, new SQLLongint(f.poolDataCachingTierLReads));
        row.setColumn(POOL_INDEX_CACHING_TIER_L_READS, new SQLLongint(f.poolIndexCachingTierLReads));
        row.setColumn(POOL_XDA_CACHING_TIER_L_READS, new SQLLongint(f.poolXdaCachingTierLReads));
        row.setColumn(POOL_COL_CACHING_TIER_L_READS, new SQLLongint(f.poolColCachingTierLReads));
        row.setColumn(POOL_DATA_CACHING_TIER_PAGE_WRITES, new SQLLongint(f.poolDataCachingTierPageWrites));
        row.setColumn(POOL_INDEX_CACHING_TIER_PAGE_WRITES, new SQLLongint(f.poolIndexCachingTierPageWrites));
        row.setColumn(POOL_XDA_CACHING_TIER_PAGE_WRITES, new SQLLongint(f.poolXdaCachingTierPageWrites));
        row.setColumn(POOL_COL_CACHING_TIER_PAGE_WRITES, new SQLLongint(f.poolColCachingTierPageWrites));
        row.setColumn(POOL_DATA_CACHING_TIER_PAGE_UPDATES, new SQLLongint(f.poolDataCachingTierPageUpdates));
        row.setColumn(POOL_INDEX_CACHING_TIER_PAGE_UPDATES, new SQLLongint(f.poolIndexCachingTierPageUpdates));
        row.setColumn(POOL_XDA_CACHING_TIER_PAGE_UPDATES, new SQLLongint(f.poolXdaCachingTierPageUpdates));
        row.setColumn(POOL_COL_CACHING_TIER_PAGE_UPDATES, new SQLLongint(f.poolColCachingTierPageUpdates));
        row.setColumn(POOL_CACHING_TIER_PAGE_READ_TIME, new SQLLongint(f.poolCachingTierPageReadTime));
        row.setColumn(POOL_CACHING_TIER_PAGE_WRITE_TIME, new SQLLongint(f.poolCachingTierPageWriteTime));
        row.setColumn(POOL_DATA_CACHING_TIER_PAGES_FOUND, new SQLLongint(f.poolDataCachingTierPagesFound));
        row.setColumn(POOL_INDEX_CACHING_TIER_PAGES_FOUND, new SQLLongint(f.poolIndexCachingTierPagesFound));
        row.setColumn(POOL_XDA_CACHING_TIER_PAGES_FOUND, new SQLLongint(f.poolXdaCachingTierPagesFound));
        row.setColumn(POOL_COL_CACHING_TIER_PAGES_FOUND, new SQLLongint(f.poolColCachingTierPagesFound));
        row.setColumn(POOL_DATA_CACHING_TIER_GBP_INVALID_PAGES, new SQLLongint(f.poolDataCachingTierGbpInvalidPages));
        row.setColumn(POOL_INDEX_CACHING_TIER_GBP_INVALID_PAGES, new SQLLongint(f.poolIndexCachingTierGbpInvalidPages));
        row.setColumn(POOL_XDA_CACHING_TIER_GBP_INVALID_PAGES, new SQLLongint(f.poolXdaCachingTierGbpInvalidPages));
        row.setColumn(POOL_COL_CACHING_TIER_GBP_INVALID_PAGES, new SQLLongint(f.poolColCachingTierGbpInvalidPages));
        row.setColumn(POOL_DATA_CACHING_TIER_GBP_INDEP_PAGES_FOUND, new SQLLongint(f.poolDataCachingTierGbpIndepPagesFound));
        row.setColumn(POOL_INDEX_CACHING_TIER_GBP_INDEP_PAGES_FOUND, new SQLLongint(f.poolIndexCachingTierGbpIndepPagesFound));
        row.setColumn(POOL_XDA_CACHING_TIER_GBP_INDEP_PAGES_FOUND, new SQLLongint(f.poolXdaCachingTierGbpIndepPagesFound));
        row.setColumn(POOL_COL_CACHING_TIER_GBP_INDEP_PAGES_FOUND, new SQLLongint(f.poolColCachingTierGbpIndepPagesFound));
        row.setColumn(TOTAL_HASH_GRPBYS, new SQLLongint(f.totalHashGrpbys));
        row.setColumn(HASH_GRPBY_OVERFLOWS, new SQLLongint(f.hashGrpbyOverflows));
        row.setColumn(POST_THRESHOLD_HASH_GRPBYS, new SQLLongint(f.postThresholdHashGrpbys));
        row.setColumn(EXECUTION_ID, new SQLVarchar(f.executionId));
        row.setColumn(POST_THRESHOLD_OLAP_FUNCS, new SQLLongint(f.postThresholdOlapFuncs));
        row.setColumn(POST_THRESHOLD_COL_VECTOR_CONSUMERS, new SQLLongint(f.postThresholdColVectorConsumers));
        row.setColumn(TOTAL_COL_VECTOR_CONSUMERS, new SQLLongint(f.totalColVectorConsumers));
        row.setColumn(ACTIVE_HASH_GRPBYS, new SQLLongint(f.activeHashGrpbys));
        row.setColumn(ACTIVE_HASH_JOINS, new SQLLongint(f.activeHashJoins));
        row.setColumn(ACTIVE_OLAP_FUNCS, new SQLLongint(f.activeOlapFuncs));
        row.setColumn(ACTIVE_PEAS, new SQLLongint(f.activePeas));
        row.setColumn(ACTIVE_PEDS, new SQLLongint(f.activePeds));
        row.setColumn(ACTIVE_SORT_CONSUMERS, new SQLLongint(f.activeSortConsumers));
        row.setColumn(ACTIVE_SORTS, new SQLLongint(f.activeSorts));
        row.setColumn(ACTIVE_COL_VECTOR_CONSUMERS, new SQLLongint(f.activeColVectorConsumers));
        row.setColumn(SORT_HEAP_ALLOCATED, new SQLLongint(f.sortHeapAllocated));
        row.setColumn(SORT_SHRHEAP_ALLOCATED, new SQLLongint(f.sortShrheapAllocated));
        row.setColumn(TOTAL_BACKUP_TIME, new SQLLongint(f.totalBackupTime));
        row.setColumn(TOTAL_BACKUP_PROC_TIME, new SQLLongint(f.totalBackupProcTime));
        row.setColumn(TOTAL_BACKUPS, new SQLLongint(f.totalBackups));
        row.setColumn(TOTAL_INDEX_BUILD_TIME, new SQLLongint(f.totalIndexBuildTime));
        row.setColumn(TOTAL_INDEX_BUILD_PROC_TIME, new SQLLongint(f.totalIndexBuildProcTime));
        row.setColumn(TOTAL_INDEXES_BUILT, new SQLLongint(f.totalIndexesBuilt));
        row.setColumn(EXT_TABLE_RECV_WAIT_TIME, new SQLLongint(f.extTableRecvWaitTime));
        row.setColumn(EXT_TABLE_RECVS_TOTAL, new SQLLongint(f.extTableRecvsTotal));
        row.setColumn(EXT_TABLE_RECV_VOLUME, new SQLLongint(f.extTableRecvVolume));
        row.setColumn(EXT_TABLE_READ_VOLUME, new SQLLongint(f.extTableReadVolume));
        row.setColumn(EXT_TABLE_SEND_WAIT_TIME, new SQLLongint(f.extTableSendWaitTime));
        row.setColumn(EXT_TABLE_SENDS_TOTAL, new SQLLongint(f.extTableSendsTotal));
        row.setColumn(EXT_TABLE_SEND_VOLUME, new SQLLongint(f.extTableSendVolume));
        row.setColumn(EXT_TABLE_WRITE_VOLUME, new SQLLongint(f.extTableWriteVolume));
        row.setColumn(FCM_TQ_RECV_WAITS_TOTAL, new SQLLongint(f.fcmTqRecvWaitsTotal));
        row.setColumn(FCM_MESSAGE_RECV_WAITS_TOTAL, new SQLLongint(f.fcmMessageRecvWaitsTotal));
        row.setColumn(FCM_TQ_SEND_WAITS_TOTAL, new SQLLongint(f.fcmTqSendWaitsTotal));
        row.setColumn(FCM_MESSAGE_SEND_WAITS_TOTAL, new SQLLongint(f.fcmMessageSendWaitsTotal));
        row.setColumn(FCM_SEND_WAITS_TOTAL, new SQLLongint(f.fcmSendWaitsTotal));
        row.setColumn(FCM_RECV_WAITS_TOTAL, new SQLLongint(f.fcmRecvWaitsTotal));
        row.setColumn(COL_VECTOR_CONSUMER_OVERFLOWS, new SQLLongint(f.colVectorConsumerOverflows));
        row.setColumn(TOTAL_COL_SYNOPSIS_TIME, new SQLLongint(f.totalColSynopsisTime));
        row.setColumn(TOTAL_COL_SYNOPSIS_PROC_TIME, new SQLLongint(f.totalColSynopsisProcTime));
        row.setColumn(TOTAL_COL_SYNOPSIS_EXECUTIONS, new SQLLongint(f.totalColSynopsisExecutions));
        row.setColumn(COL_SYNOPSIS_ROWS_INSERTED, new SQLLongint(f.colSynopsisRowsInserted));
        row.setColumn(LOB_PREFETCH_WAIT_TIME, new SQLLongint(f.lobPrefetchWaitTime));
        row.setColumn(LOB_PREFETCH_REQS, new SQLLongint(f.lobPrefetchReqs));
        row.setColumn(FED_ROWS_DELETED, new SQLLongint(f.fedRowsDeleted));
        row.setColumn(FED_ROWS_INSERTED, new SQLLongint(f.fedRowsInserted));
        row.setColumn(FED_ROWS_UPDATED, new SQLLongint(f.fedRowsUpdated));
        row.setColumn(FED_ROWS_READ, new SQLLongint(f.fedRowsRead));
        row.setColumn(FED_WAIT_TIME, new SQLLongint(f.fedWaitTime));
        row.setColumn(FED_WAITS_TOTAL, new SQLLongint(f.fedWaitsTotal));
        row.setColumn(APPL_SECTION_INSERTS, new SQLLongint(f.applSectionInserts));
        row.setColumn(APPL_SECTION_LOOKUPS, new SQLLongint(f.applSectionLookups));
        row.setColumn(CONNECTION_REUSABILITY_STATUS, new SQLSmallint(f.connectionReusabilityStatus));
        row.setColumn(REUSABILITY_STATUS_REASON, new SQLVarchar(f.reusabilityStatusReason));
        row.setColumn(ADM_OVERFLOWS, new SQLLongint(f.admOverflows));
        row.setColumn(ADM_BYPASS_ACT_TOTAL, new SQLLongint(f.admBypassActTotal));
    }

    public static SystemColumn[] buildCompileTimeColumnList() {
        return new SystemColumn[]{
                SystemColumnImpl.getColumn("APPLICATION_HANDLE", Types.BIGINT, true),
                SystemColumnImpl.getColumn("APPLICATION_NAME", Types.VARCHAR, true, 128),
                SystemColumnImpl.getColumn("APPLICATION_ID", Types.VARCHAR, true, 128),
                SystemColumnImpl.getColumn("MEMBER", Types.SMALLINT, true),
                SystemColumnImpl.getColumn("CLIENT_WRKSTNNAME", Types.VARCHAR, true, 255),
                SystemColumnImpl.getColumn("CLIENT_ACCTNG", Types.VARCHAR, true, 255),
                SystemColumnImpl.getColumn("CLIENT_USERID", Types.VARCHAR, true, 255),
                SystemColumnImpl.getColumn("CLIENT_APPLNAME", Types.VARCHAR, true, 255),
                SystemColumnImpl.getColumn("CLIENT_PID", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CLIENT_PRDID", Types.VARCHAR, true, 128),
                SystemColumnImpl.getColumn("CLIENT_PLATFORM", Types.VARCHAR, true, 12),
                SystemColumnImpl.getColumn("CLIENT_PROTOCOL", Types.VARCHAR, true, 10),
                SystemColumnImpl.getColumn("SYSTEM_AUTH_ID", Types.VARCHAR, true, 128),
                SystemColumnImpl.getColumn("SESSION_AUTH_ID", Types.VARCHAR, true, 128),
                SystemColumnImpl.getColumn("COORD_MEMBER", Types.SMALLINT, true),
                SystemColumnImpl.getColumn("CONNECTION_START_TIME", Types.TIMESTAMP, true),
                SystemColumnImpl.getColumn("ACT_ABORTED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACT_COMPLETED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACT_REJECTED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("AGENT_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("AGENT_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_DATA_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_INDEX_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_XDA_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_DATA_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_INDEX_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_XDA_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_READ_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_WRITE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CLIENT_IDLE_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DEADLOCKS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DIRECT_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DIRECT_READ_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DIRECT_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DIRECT_WRITE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DIRECT_READ_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DIRECT_WRITE_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_RECV_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_RECVS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_SEND_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_SENDS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_RECV_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_SEND_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IPC_RECV_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IPC_RECV_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IPC_RECVS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IPC_SEND_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IPC_SEND_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IPC_SENDS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_ESCALS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_TIMEOUTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_WAITS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOG_BUFFER_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("NUM_LOG_BUFFER_FULL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOG_DISK_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOG_DISK_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("NUM_LOCKS_HELD", Types.BIGINT, true),
                SystemColumnImpl.getColumn("RQSTS_COMPLETED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ROWS_MODIFIED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ROWS_READ", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ROWS_RETURNED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TCPIP_RECV_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TCPIP_SEND_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TCPIP_RECV_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TCPIP_RECVS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TCPIP_SEND_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TCPIP_SENDS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_APP_RQST_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_RQST_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("WLM_QUEUE_TIME_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("WLM_QUEUE_ASSIGNMENTS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_CPU_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("APP_RQSTS_COMPLETED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SECTION_SORT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SECTION_SORT_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SECTION_SORTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SORTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POST_THRESHOLD_SORTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POST_SHRTHRESHOLD_SORTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("SORT_OVERFLOWS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COMPILE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COMPILE_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COMPILATIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_IMPLICIT_COMPILE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_IMPLICIT_COMPILE_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_IMPLICIT_COMPILATIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SECTION_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SECTION_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_APP_SECTION_EXECUTIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_ACT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_ACT_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACT_RQSTS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_ROUTINE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_ROUTINE_INVOCATIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COMMIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COMMIT_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_APP_COMMITS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("INT_COMMITS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_ROLLBACK_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_ROLLBACK_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_APP_ROLLBACKS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("INT_ROLLBACKS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_RUNSTATS_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_RUNSTATS_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_RUNSTATS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_REORG_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_REORG_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_REORGS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_LOAD_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_LOAD_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_LOADS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CAT_CACHE_INSERTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CAT_CACHE_LOOKUPS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("PKG_CACHE_INSERTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("PKG_CACHE_LOOKUPS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("THRESH_VIOLATIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("NUM_LW_THRESH_EXCEEDED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_WAITS_GLOBAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_WAIT_TIME_GLOBAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_TIMEOUTS_GLOBAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_ESCALS_MAXLOCKS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_ESCALS_LOCKLIST", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOCK_ESCALS_GLOBAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("RECLAIM_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("SPACEMAPPAGE_RECLAIM_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CF_WAITS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CF_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_GBP_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_GBP_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_LBP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_GBP_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_GBP_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_LBP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_GBP_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_GBP_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_LBP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("AUDIT_EVENTS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("AUDIT_FILE_WRITES_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("AUDIT_FILE_WRITE_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("AUDIT_SUBSYSTEM_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("AUDIT_SUBSYSTEM_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CLIENT_HOSTNAME", Types.VARCHAR, true, 255),
                SystemColumnImpl.getColumn("CLIENT_PORT_NUMBER", Types.INTEGER, true),
                SystemColumnImpl.getColumn("DIAGLOG_WRITES_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DIAGLOG_WRITE_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_RECVS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_RECV_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_RECV_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_SENDS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_SEND_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_SEND_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_RECVS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_RECV_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_RECV_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_SENDS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_SEND_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_SEND_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LAST_EXECUTABLE_ID", Types.VARCHAR, true, 32),
                SystemColumnImpl.getColumn("LAST_REQUEST_TYPE", Types.VARCHAR, true, 32),
                SystemColumnImpl.getColumn("TOTAL_ROUTINE_USER_CODE_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_ROUTINE_USER_CODE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TQ_TOT_SEND_SPILLS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EVMON_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EVMON_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_EXTENDED_LATCH_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_EXTENDED_LATCH_WAITS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("INTRA_PARALLEL_STATE", Types.VARCHAR, true, 3),
                SystemColumnImpl.getColumn("TOTAL_STATS_FABRICATION_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_STATS_FABRICATION_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_STATS_FABRICATIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SYNC_RUNSTATS_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SYNC_RUNSTATS_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_SYNC_RUNSTATS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_DISP_RUN_QUEUE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_PEDS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DISABLED_PEDS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POST_THRESHOLD_PEDS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_PEAS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POST_THRESHOLD_PEAS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TQ_SORT_HEAP_REQUESTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TQ_SORT_HEAP_REJECTIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_DATA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_INDEX_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_XDA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_DATA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_INDEX_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_XDA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_OTHER_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_DATA_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_INDEX_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_XDA_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_DATA_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_INDEX_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_XDA_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_DATA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_INDEX_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_XDA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_TEMP_DATA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_TEMP_INDEX_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_TEMP_XDA_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_OTHER_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("PREFETCH_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("PREFETCH_WAITS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("APP_ACT_COMPLETED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("APP_ACT_ABORTED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("APP_ACT_REJECTED_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_CONNECT_REQUEST_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_CONNECT_REQUEST_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_CONNECT_REQUESTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_CONNECT_AUTHENTICATION_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_CONNECT_AUTHENTICATION_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_CONNECT_AUTHENTICATIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_GBP_INDEP_PAGES_FOUND_IN_LBP", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_GBP_INDEP_PAGES_FOUND_IN_LBP", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_GBP_INDEP_PAGES_FOUND_IN_LBP", Types.BIGINT, true),
                SystemColumnImpl.getColumn("COMM_EXIT_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("COMM_EXIT_WAITS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IDA_SEND_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IDA_SENDS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IDA_SEND_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IDA_RECV_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IDA_RECVS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IDA_RECV_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("MEMBER_SUBSET_ID", Types.INTEGER, true),
                SystemColumnImpl.getColumn("IS_SYSTEM_APPL", Types.SMALLINT, true),
                SystemColumnImpl.getColumn("LOCK_TIMEOUT_VAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CURRENT_ISOLATION", Types.CHAR, true, 2),
                SystemColumnImpl.getColumn("NUM_LOCKS_WAITING", Types.BIGINT, true),
                SystemColumnImpl.getColumn("UOW_CLIENT_IDLE_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ROWS_DELETED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ROWS_INSERTED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ROWS_UPDATED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_HASH_JOINS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_HASH_LOOPS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("HASH_JOIN_OVERFLOWS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("HASH_JOIN_SMALL_OVERFLOWS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POST_SHRTHRESHOLD_HASH_JOINS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_OLAP_FUNCS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("OLAP_FUNC_OVERFLOWS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DYNAMIC_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("STATIC_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FAILED_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("SELECT_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("UID_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("DDL_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("MERGE_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("XQUERY_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("IMPLICIT_REBINDS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("BINDS_PRECOMPILES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("INT_ROWS_DELETED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("INT_ROWS_INSERTED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("INT_ROWS_UPDATED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CALL_SQL_STMTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_COL_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_TEMP_COL_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_LBP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_GBP_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_GBP_P_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_GBP_INDEP_PAGES_FOUND_IN_LBP", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_COL_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_COL_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_COL_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_QUEUED_ASYNC_TEMP_COL_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_COL_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_FAILED_ASYNC_TEMP_COL_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COL_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COL_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COL_EXECUTIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CLIENT_IPADDR", Types.VARCHAR, true, 128),
                SystemColumnImpl.getColumn("SQL_REQS_SINCE_COMMIT", Types.BIGINT, true),
                SystemColumnImpl.getColumn("UOW_START_TIME", Types.TIMESTAMP, true),
                SystemColumnImpl.getColumn("UOW_STOP_TIME", Types.TIMESTAMP, true),
                SystemColumnImpl.getColumn("PREV_UOW_STOP_TIME", Types.TIMESTAMP, true),
                SystemColumnImpl.getColumn("UOW_COMP_STATUS", Types.VARCHAR, true, 14),
                SystemColumnImpl.getColumn("NUM_ASSOC_AGENTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ASSOCIATED_AGENTS_TOP", Types.BIGINT, true),
                SystemColumnImpl.getColumn("WORKLOAD_OCCURRENCE_STATE", Types.VARCHAR, true, 32),
                SystemColumnImpl.getColumn("POST_THRESHOLD_HASH_JOINS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_CACHING_TIER_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_CACHING_TIER_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_CACHING_TIER_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_CACHING_TIER_L_READS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_CACHING_TIER_PAGE_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_CACHING_TIER_PAGE_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_CACHING_TIER_PAGE_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_CACHING_TIER_PAGE_WRITES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_CACHING_TIER_PAGE_UPDATES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_CACHING_TIER_PAGE_UPDATES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_CACHING_TIER_PAGE_UPDATES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_CACHING_TIER_PAGE_UPDATES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_CACHING_TIER_PAGE_READ_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_CACHING_TIER_PAGE_WRITE_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_CACHING_TIER_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_CACHING_TIER_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_CACHING_TIER_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_CACHING_TIER_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_CACHING_TIER_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_CACHING_TIER_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_CACHING_TIER_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_CACHING_TIER_GBP_INVALID_PAGES", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_DATA_CACHING_TIER_GBP_INDEP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_INDEX_CACHING_TIER_GBP_INDEP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_XDA_CACHING_TIER_GBP_INDEP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POOL_COL_CACHING_TIER_GBP_INDEP_PAGES_FOUND", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_HASH_GRPBYS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("HASH_GRPBY_OVERFLOWS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POST_THRESHOLD_HASH_GRPBYS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXECUTION_ID", Types.VARCHAR, true, 128),
                SystemColumnImpl.getColumn("POST_THRESHOLD_OLAP_FUNCS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("POST_THRESHOLD_COL_VECTOR_CONSUMERS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COL_VECTOR_CONSUMERS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_HASH_GRPBYS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_HASH_JOINS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_OLAP_FUNCS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_PEAS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_PEDS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_SORT_CONSUMERS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_SORTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ACTIVE_COL_VECTOR_CONSUMERS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("SORT_HEAP_ALLOCATED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("SORT_SHRHEAP_ALLOCATED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_BACKUP_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_BACKUP_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_BACKUPS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_INDEX_BUILD_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_INDEX_BUILD_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_INDEXES_BUILT", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_RECV_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_RECVS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_RECV_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_READ_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_SEND_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_SENDS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_SEND_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("EXT_TABLE_WRITE_VOLUME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_RECV_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_RECV_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_TQ_SEND_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_MESSAGE_SEND_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_SEND_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FCM_RECV_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("COL_VECTOR_CONSUMER_OVERFLOWS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COL_SYNOPSIS_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COL_SYNOPSIS_PROC_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("TOTAL_COL_SYNOPSIS_EXECUTIONS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("COL_SYNOPSIS_ROWS_INSERTED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOB_PREFETCH_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("LOB_PREFETCH_REQS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FED_ROWS_DELETED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FED_ROWS_INSERTED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FED_ROWS_UPDATED", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FED_ROWS_READ", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FED_WAIT_TIME", Types.BIGINT, true),
                SystemColumnImpl.getColumn("FED_WAITS_TOTAL", Types.BIGINT, true),
                SystemColumnImpl.getColumn("APPL_SECTION_INSERTS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("APPL_SECTION_LOOKUPS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("CONNECTION_REUSABILITY_STATUS", Types.SMALLINT, true),
                SystemColumnImpl.getColumn("REUSABILITY_STATUS_REASON", Types.VARCHAR, true, 255),
                SystemColumnImpl.getColumn("ADM_OVERFLOWS", Types.BIGINT, true),
                SystemColumnImpl.getColumn("ADM_BYPASS_ACT_TOTAL", Types.BIGINT, true)
        };
    }

    @Override
    public List<ColumnDescriptor[]> getViewColumns(TableDescriptor view, UUID viewId) {
        List<ColumnDescriptor[]> cdsl = new ArrayList<>();

        // SNAPAPPL
        Collection<Object[]> colList = Lists.newArrayListWithCapacity(128);
        colList.add(new Object[]{"SNAPSHOT_TIMESTAMP", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"DB_NAME", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"AGENT_ID", Types.BIGINT, false, null});
        colList.add(new Object[]{"UOW_LOG_SPACE_USED", Types.BIGINT, false, null});
        colList.add(new Object[]{"ROWS_READ", Types.BIGINT, false, null});
        colList.add(new Object[]{"ROWS_WRITTEN", Types.BIGINT, false, null});
        colList.add(new Object[]{"INACT_STMTHIST_SZ", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_DATA_L_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_DATA_P_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_DATA_WRITES", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_INDEX_L_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_INDEX_P_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_INDEX_WRITES", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_TEMP_DATA_L_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_TEMP_DATA_P_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_TEMP_INDEX_L_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_TEMP_INDEX_P_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_TEMP_XDA_L_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_TEMP_XDA_P_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_XDA_L_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_XDA_P_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_XDA_WRITES", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_READ_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"POOL_WRITE_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"DIRECT_READS", Types.BIGINT, false, null});
        colList.add(new Object[]{"DIRECT_WRITES", Types.BIGINT, false, null});
        colList.add(new Object[]{"DIRECT_READ_REQS", Types.BIGINT, false, null});
        colList.add(new Object[]{"DIRECT_WRITE_REQS", Types.BIGINT, false, null});
        colList.add(new Object[]{"DIRECT_READ_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"DIRECT_WRITE_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"UNREAD_PREFETCH_PAGES", Types.BIGINT, false, null});
        colList.add(new Object[]{"LOCKS_HELD", Types.BIGINT, false, null});
        colList.add(new Object[]{"LOCK_WAITS", Types.BIGINT, false, null});
        colList.add(new Object[]{"LOCK_WAIT_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"LOCK_ESCALS", Types.BIGINT, false, null});
        colList.add(new Object[]{"X_LOCK_ESCALS", Types.BIGINT, false, null});
        colList.add(new Object[]{"DEADLOCKS", Types.BIGINT, false, null});
        colList.add(new Object[]{"TOTAL_SORTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"TOTAL_SORT_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"SORT_OVERFLOWS", Types.BIGINT, false, null});
        colList.add(new Object[]{"COMMIT_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"ROLLBACK_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"DYNAMIC_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"STATIC_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"FAILED_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"SELECT_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"DDL_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"UID_SQL_STMTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"INT_AUTO_REBINDS", Types.BIGINT, false, null});
        colList.add(new Object[]{"INT_ROWS_DELETED", Types.BIGINT, false, null});
        colList.add(new Object[]{"INT_ROWS_UPDATED", Types.BIGINT, false, null});
        colList.add(new Object[]{"INT_COMMITS", Types.BIGINT, false, null});
        colList.add(new Object[]{"INT_ROLLBACKS", Types.BIGINT, false, null});
        colList.add(new Object[]{"INT_DEADLOCK_ROLLBACKS", Types.BIGINT, false, null});
        colList.add(new Object[]{"ROWS_DELETED", Types.BIGINT, false, null});
        colList.add(new Object[]{"ROWS_INSERTED", Types.BIGINT, false, null});
        colList.add(new Object[]{"ROWS_UPDATED", Types.BIGINT, false, null});
        colList.add(new Object[]{"ROWS_SELECTED", Types.BIGINT, false, null});
        colList.add(new Object[]{"BINDS_PRECOMPILES", Types.BIGINT, false, null});
        colList.add(new Object[]{"OPEN_REM_CURS", Types.BIGINT, false, null});
        colList.add(new Object[]{"OPEN_REM_CURS_BLK", Types.BIGINT, false, null});
        colList.add(new Object[]{"REJ_CURS_BLK", Types.BIGINT, false, null});
        colList.add(new Object[]{"ACC_CURS_BLK", Types.BIGINT, false, null});
        colList.add(new Object[]{"SQL_REQS_SINCE_COMMIT", Types.BIGINT, false, null});
        colList.add(new Object[]{"LOCK_TIMEOUTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"INT_ROWS_INSERTED", Types.BIGINT, false, null});
        colList.add(new Object[]{"OPEN_LOC_CURS", Types.BIGINT, false, null});
        colList.add(new Object[]{"OPEN_LOC_CURS_BLK", Types.BIGINT, false, null});
        colList.add(new Object[]{"PKG_CACHE_LOOKUPS", Types.BIGINT, false, null});
        colList.add(new Object[]{"PKG_CACHE_INSERTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"CAT_CACHE_LOOKUPS", Types.BIGINT, false, null});
        colList.add(new Object[]{"CAT_CACHE_INSERTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"CAT_CACHE_OVERFLOWS", Types.BIGINT, false, null});
        colList.add(new Object[]{"NUM_AGENTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"AGENTS_STOLEN", Types.BIGINT, false, null});
        colList.add(new Object[]{"ASSOCIATED_AGENTS_TOP", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_PRIORITY", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_PRIORITY_TYPE", Types.VARCHAR, false, 16});
        colList.add(new Object[]{"PREFETCH_WAIT_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_SECTION_LOOKUPS", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_SECTION_INSERTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"LOCKS_WAITING", Types.BIGINT, false, null});
        colList.add(new Object[]{"TOTAL_HASH_JOINS", Types.BIGINT, false, null});
        colList.add(new Object[]{"TOTAL_HASH_LOOPS", Types.BIGINT, false, null});
        colList.add(new Object[]{"HASH_JOIN_OVERFLOWS", Types.BIGINT, false, null});
        colList.add(new Object[]{"HASH_JOIN_SMALL_OVERFLOWS", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_IDLE_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"UOW_LOCK_WAIT_TIME", Types.BIGINT, false, null});
        colList.add(new Object[]{"UOW_COMP_STATUS", Types.VARCHAR, false, 14});
        colList.add(new Object[]{"AGENT_USR_CPU_TIME_S", Types.BIGINT, false, null});
        colList.add(new Object[]{"AGENT_USR_CPU_TIME_MS", Types.BIGINT, false, null});
        colList.add(new Object[]{"AGENT_SYS_CPU_TIME_S", Types.BIGINT, false, null});
        colList.add(new Object[]{"AGENT_SYS_CPU_TIME_MS", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_CON_TIME", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"CONN_COMPLETE_TIME", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"LAST_RESET", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"UOW_START_TIME", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"UOW_STOP_TIME", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"PREV_UOW_STOP_TIME", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"UOW_ELAPSED_TIME_S", Types.BIGINT, false, null});
        colList.add(new Object[]{"UOW_ELAPSED_TIME_MS", Types.BIGINT, false, null});
        colList.add(new Object[]{"ELAPSED_EXEC_TIME_S", Types.BIGINT, false, null});
        colList.add(new Object[]{"ELAPSED_EXEC_TIME_MS", Types.BIGINT, false, null});
        colList.add(new Object[]{"INBOUND_COMM_ADDRESS", Types.VARCHAR, false, 128}); // lifted from 32
        colList.add(new Object[]{"LOCK_TIMEOUT_VAL", Types.BIGINT, false, null});
        colList.add(new Object[]{"PRIV_WORKSPACE_NUM_OVERFLOWS", Types.BIGINT, false, null});
        colList.add(new Object[]{"PRIV_WORKSPACE_SECTION_INSERTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"PRIV_WORKSPACE_SECTION_LOOKUPS", Types.BIGINT, false, null});
        colList.add(new Object[]{"PRIV_WORKSPACE_SIZE_TOP", Types.BIGINT, false, null});
        colList.add(new Object[]{"SHR_WORKSPACE_NUM_OVERFLOWS", Types.BIGINT, false, null});
        colList.add(new Object[]{"SHR_WORKSPACE_SECTION_INSERTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"SHR_WORKSPACE_SECTION_LOOKUPS", Types.BIGINT, false, null});
        colList.add(new Object[]{"SHR_WORKSPACE_SIZE_TOP", Types.BIGINT, false, null});
        colList.add(new Object[]{"DBPARTITIONNUM", Types.SMALLINT, false, null});
        colList.add(new Object[]{"CAT_CACHE_SIZE_TOP", Types.BIGINT, false, null});
        colList.add(new Object[]{"TOTAL_OLAP_FUNCS", Types.BIGINT, false, null});
        colList.add(new Object[]{"OLAP_FUNC_OVERFLOWS", Types.BIGINT, false, null});
        colList.add(new Object[]{"MEMBER", Types.SMALLINT, false, null});

        cdsl.add(buildViewColumns(colList, view, viewId));
        colList.clear();

        // SNAPAPPL_INFO
        colList.add(new Object[]{"SNAPSHOT_TIMESTAMP", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"AGENT_ID", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_STATUS", Types.VARCHAR, false, 32});  // lifted from 22
        colList.add(new Object[]{"CODEPAGE_ID", Types.BIGINT, false, null});
        colList.add(new Object[]{"NUM_ASSOC_AGENTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"COORD_NODE_NUM", Types.SMALLINT, false, null});
        colList.add(new Object[]{"AUTHORITY_LVL", Types.VARCHAR, false, 512});
        colList.add(new Object[]{"CLIENT_PID", Types.BIGINT, false, null});
        colList.add(new Object[]{"COORD_AGENT_PID", Types.BIGINT, false, null});
        colList.add(new Object[]{"STATUS_CHANGE_TIME", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"CLIENT_PLATFORM", Types.VARCHAR, false, 12});
        colList.add(new Object[]{"CLIENT_PROTOCOL", Types.VARCHAR, false, 10});
        colList.add(new Object[]{"TERRITORY_CODE", Types.SMALLINT, false, null});
        colList.add(new Object[]{"APPL_NAME", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"APPL_ID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"SEQUENCE_NO", Types.VARCHAR, false, 4});
        colList.add(new Object[]{"PRIMARY_AUTH_ID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"SESSION_AUTH_ID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"CLIENT_NNAME", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"CLIENT_PRDID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"INPUT_DB_ALIAS", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"CLIENT_DB_ALIAS", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"DB_NAME", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"DB_PATH", Types.VARCHAR, false, 1024});
        colList.add(new Object[]{"EXECUTION_ID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"CORR_TOKEN", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"TPMON_CLIENT_USERID", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"TPMON_CLIENT_WKSTN", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"TPMON_CLIENT_APP", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"TPMON_ACC_STR", Types.VARCHAR, false, 255});
        colList.add(new Object[]{"DBPARTITIONNUM", Types.SMALLINT, false, null});
        colList.add(new Object[]{"WORKLOAD_ID", Types.INTEGER, false, null});
        colList.add(new Object[]{"IS_SYSTEM_APPL", Types.SMALLINT, false, null});
        colList.add(new Object[]{"MEMBER", Types.SMALLINT, false, null});
        colList.add(new Object[]{"COORD_MEMBER", Types.SMALLINT, false, null});
        colList.add(new Object[]{"COORD_DBPARTITIONNUM", Types.SMALLINT, false, null});

        cdsl.add(buildViewColumns(colList, view, viewId));
        colList.clear();

        // APPLICATIONS
        colList.add(new Object[]{"SNAPSHOT_TIMESTAMP", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"CLIENT_DB_ALIAS", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"DB_NAME", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"AGENT_ID", Types.BIGINT, false, null});
        colList.add(new Object[]{"APPL_NAME", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"AUTHID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"APPL_ID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"APPL_STATUS", Types.VARCHAR, false, 32});  // lifted from 22
        colList.add(new Object[]{"STATUS_CHANGE_TIME", Types.TIMESTAMP, false, null});
        colList.add(new Object[]{"SEQUENCE_NO", Types.VARCHAR, false, 4});
        colList.add(new Object[]{"CLIENT_PRDID", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"CLIENT_PID", Types.BIGINT, false, null});
        colList.add(new Object[]{"CLIENT_PLATFORM", Types.VARCHAR, false, 12});
        colList.add(new Object[]{"CLIENT_PROTOCOL", Types.VARCHAR, false, 10});
        colList.add(new Object[]{"CLIENT_NNAME", Types.VARCHAR, false, 128});
        colList.add(new Object[]{"COORD_NODE_NUM", Types.SMALLINT, false, null});
        colList.add(new Object[]{"COORD_AGENT_PID", Types.BIGINT, false, null});
        colList.add(new Object[]{"NUM_ASSOC_AGENTS", Types.BIGINT, false, null});
        colList.add(new Object[]{"TPMON_CLIENT_USERID", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"TPMON_CLIENT_WKSTN", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"TPMON_CLIENT_APP", Types.VARCHAR, false, 256});
        colList.add(new Object[]{"TPMON_ACC_STR", Types.VARCHAR, false, 255});
        colList.add(new Object[]{"DBPARTITIONNUM", Types.SMALLINT, false, null});
        colList.add(new Object[]{"MEMBER", Types.SMALLINT, false, null});
        colList.add(new Object[]{"COORD_MEMBER", Types.SMALLINT, false, null});
        colList.add(new Object[]{"COORD_DBPARTITIONNUM", Types.SMALLINT, false, null});

        cdsl.add(buildViewColumns(colList, view, viewId));
        colList.clear();

        return cdsl;
    }

    private ColumnDescriptor[] buildViewColumns(Collection<Object[]> colList, TableDescriptor view, UUID viewId) {
        Collection<ColumnDescriptor> columnDescriptors = Lists.newArrayListWithCapacity(50);
        int colPos = 0;
        for (Object[] entry: colList) {
            colPos ++;
            if (entry[3] != null) {
                columnDescriptors.add(new ColumnDescriptor((String) entry[0], colPos, colPos, DataTypeDescriptor.getBuiltInDataTypeDescriptor((int) entry[1], (boolean) entry[2], (int) entry[3]),
                        null, null, view, viewId, 0, 0, 0));
            } else {
                columnDescriptors.add(new ColumnDescriptor((String) entry[0], colPos, colPos, DataTypeDescriptor.getBuiltInDataTypeDescriptor((int) entry[1], (boolean) entry[2]),
                        null, null, view, viewId, 0, 0, 0));
            }
        }

        ColumnDescriptor[] arr = new ColumnDescriptor[columnDescriptors.size()];
        arr = columnDescriptors.toArray(arr);
        return arr;
    }

    public static String SNAPAPPL_VIEW_SQL = "create view SNAPAPPL as \n" +
            "SELECT" +
            "  TIMESTAMP('1970-01-01 00:00:00') AS SNAPSHOT_TIMESTAMP," +
            "  'SPLICE' AS DB_NAME,             -- MON_GET_MEMORY_POOL\n" +
            "  APPLICATION_HANDLE AS AGENT_ID," +
            "  0 AS UOW_LOG_SPACE_USED,         -- MON_GET_UNIT_OF_WORK\n" +
            "  ROWS_READ," +
            "  ROWS_MODIFIED AS ROWS_WRITTEN," +
            "  0 AS INACT_STMTHIST_SZ,          -- MON_HEAP_SZ\n" +
            "  POOL_DATA_L_READS," +
            "  POOL_DATA_P_READS," +
            "  POOL_DATA_WRITES," +
            "  POOL_INDEX_L_READS," +
            "  POOL_INDEX_P_READS," +
            "  POOL_INDEX_WRITES," +
            "  POOL_TEMP_DATA_L_READS," +
            "  POOL_TEMP_DATA_P_READS," +
            "  POOL_TEMP_INDEX_L_READS," +
            "  POOL_TEMP_INDEX_P_READS," +
            "  POOL_TEMP_XDA_L_READS," +
            "  POOL_TEMP_XDA_P_READS," +
            "  POOL_XDA_L_READS," +
            "  POOL_XDA_P_READS," +
            "  POOL_XDA_WRITES," +
            "  POOL_READ_TIME," +
            "  POOL_WRITE_TIME," +
            "  DIRECT_READS," +
            "  DIRECT_WRITES," +
            "  DIRECT_READ_REQS," +
            "  DIRECT_WRITE_REQS," +
            "  DIRECT_READ_TIME," +
            "  DIRECT_WRITE_TIME," +
            "  0 AS UNREAD_PREFETCH_PAGES,       -- MON_GET_DATABASE\n" +
            "  NUM_LOCKS_HELD AS LOCKS_HELD," +
            "  LOCK_WAITS," +
            "  LOCK_WAIT_TIME," +
            "  LOCK_ESCALS," +
            "  0 AS X_LOCK_ESCALS,               -- source not clear\n" +
            "  DEADLOCKS," +
            "  TOTAL_SORTS," +
            "  TOTAL_SECTION_SORT_TIME + TOTAL_SECTION_SORT_PROC_TIME AS TOTAL_SORT_TIME,  -- may not be correct\n" +
            "  SORT_OVERFLOWS," +
            "  TOTAL_APP_COMMITS AS COMMIT_SQL_STMTS,        -- may not be correct\n" +
            "  TOTAL_APP_ROLLBACKS AS ROLLBACK_SQL_STMTS,    -- may not be correct\n" +
            "  DYNAMIC_SQL_STMTS," +
            "  STATIC_SQL_STMTS," +
            "  FAILED_SQL_STMTS," +
            "  SELECT_SQL_STMTS," +
            "  DDL_SQL_STMTS," +
            "  UID_SQL_STMTS," +
            "  IMPLICIT_REBINDS AS INT_AUTO_REBINDS," +
            "  INT_ROWS_DELETED," +
            "  INT_ROWS_UPDATED," +
            "  INT_COMMITS," +
            "  INT_ROLLBACKS," +
            "  0 AS INT_DEADLOCK_ROLLBACKS,      -- source not clear\n" +
            "  ROWS_DELETED," +
            "  ROWS_INSERTED," +
            "  ROWS_UPDATED," +
            "  ROWS_RETURNED AS ROWS_SELECTED," +
            "  BINDS_PRECOMPILES," +
            "  0 AS OPEN_REM_CURS,               -- source not clear\n" +
            "  0 AS OPEN_REM_CURS_BLK,           -- source not clear\n" +
            "  0 AS REJ_CURS_BLK,                -- source not clear\n" +
            "  0 AS ACC_CURS_BLK,                -- source not clear\n" +
            "  SQL_REQS_SINCE_COMMIT," +
            "  LOCK_TIMEOUTS," +
            "  INT_ROWS_INSERTED," +
            "  0 AS OPEN_LOC_CURS,               -- source not clear\n" +
            "  0 AS OPEN_LOC_CURS_BLK,           -- source not clear\n" +
            "  PKG_CACHE_LOOKUPS," +
            "  PKG_CACHE_INSERTS," +
            "  CAT_CACHE_LOOKUPS," +
            "  CAT_CACHE_INSERTS," +
            "  0 AS CAT_CACHE_OVERFLOWS,         -- MON_GET_DATABASE\n" +
            "  1 AS NUM_AGENTS,                  -- MON_GET_ACTIVITY\n" +
            "  0 AS AGENTS_STOLEN,               -- MON_GET_INSTANCE\n" +
            "  ASSOCIATED_AGENTS_TOP," +
            "  0 AS APPL_PRIORITY,               -- source not clear\n" +
            "  'FIXED_PRIORITY' AS APPL_PRIORITY_TYPE,  -- source not clear ('FIXED_PRIORITY' or 'DYNAMIC_PRIORITY')\n" +
            "  PREFETCH_WAIT_TIME," +
            "  APPL_SECTION_LOOKUPS," +
            "  APPL_SECTION_INSERTS," +
            "  NUM_LOCKS_WAITING AS LOCKS_WAITING," +
            "  TOTAL_HASH_JOINS," +
            "  TOTAL_HASH_LOOPS," +
            "  HASH_JOIN_OVERFLOWS," +
            "  HASH_JOIN_SMALL_OVERFLOWS," +
            "  0 AS APPL_IDLE_TIME,              -- source not clear\n" +
            "  0 ASUOW_LOCK_WAIT_TIME,           -- MON_GET_UNIT_OF_WORK.LOCK_WAIT_TIME\n" +
            "  UOW_COMP_STATUS," +
            "  0 AS AGENT_USR_CPU_TIME_S,        -- 0 if not available from OS\n" +
            "  0 AS AGENT_USR_CPU_TIME_MS,       -- 0 if not available from OS\n" +
            "  0 AS AGENT_SYS_CPU_TIME_S,        -- 0 if not available from OS\n" +
            "  0 AS AGENT_SYS_CPU_TIME_MS,       -- 0 if not available from OS\n" +
            "  CONNECTION_START_TIME AS APPL_CON_TIME," +
            "  TIMESTAMP('2200-01-01 00:00:00') AS CONN_COMPLETE_TIME,  -- source not clear\n" +
            "  TIMESTAMP('1970-01-01 00:00:00') AS LAST_RESET,  -- MON_GET_WORKLOAD_STATS\n" +
            "  UOW_START_TIME," +
            "  UOW_STOP_TIME," +
            "  PREV_UOW_STOP_TIME," +
            "  0 AS UOW_ELAPSED_TIME_S,          -- FLOOR(UOW_STOP_TIME - UOW_START_TIME) in seconds\n" +
            "  0 AS UOW_ELAPSED_TIME_MS,         -- (UOW_STOP_TIME - UOW_START_TIME), only milliseconds part\n" +
            "  0 AS ELAPSED_EXEC_TIME_S,         -- only available on z/OS, 0 for all other OSs\n" +
            "  0 AS ELAPSED_EXEC_TIME_MS,        -- only available on z/OS, 0 for all other OSs\n" +
            "  CLIENT_IPADDR AS INBOUND_COMM_ADDRESS,  -- should have CLIENT_PORT_NUMBER as well, but derby allows only integer to char with padding\n" +
            "  LOCK_TIMEOUT_VAL," +
            "  -1 AS PRIV_WORKSPACE_NUM_OVERFLOWS,   -- deprecated, DB2 returns invalid value\n" +
            "  -1 AS PRIV_WORKSPACE_SECTION_INSERTS, -- deprecated, DB2 returns invalid value\n" +
            "  -1 AS PRIV_WORKSPACE_SECTION_LOOKUPS, -- deprecated, DB2 returns invalid value\n" +
            "  -1 AS PRIV_WORKSPACE_SIZE_TOP,        -- deprecated, DB2 returns invalid value\n" +
            "  -1 AS SHR_WORKSPACE_NUM_OVERFLOWS,    -- deprecated, DB2 returns invalid value\n" +
            "  -1 AS SHR_WORKSPACE_SECTION_INSERTS,  -- deprecated, DB2 returns invalid value\n" +
            "  -1 AS SHR_WORKSPACE_SECTION_LOOKUPS,  -- deprecated, DB2 returns invalid value\n" +
            "  -1 AS SHR_WORKSPACE_SIZE_TOP,         -- deprecated, DB2 returns invalid value\n" +
            "  0 AS DBPARTITIONNUM,              -- 0 for Enterprise Server edition, #partitions otherwise\n" +
            "  0 AS CAT_CACHE_SIZE_TOP,          -- related to MON_GET_MEMORY_POOL.MEMORY_POOL_USED_HWM, not sure what's the equation\n" +
            "  TOTAL_OLAP_FUNCS," +
            "  OLAP_FUNC_OVERFLOWS," +
            "  MEMBER" +
            " FROM TABLE(SYSIBMADM.MON_GET_CONNECTION()) MON_GET_CONNECTION";

    public static String SNAPAPPL_INFO_VIEW_SQL = "create view SNAPAPPL_INFO as \n" +
            "SELECT" +
            "  TIMESTAMP('1970-01-01 00:00:00') AS SNAPSHOT_TIMESTAMP," +
            "  APPLICATION_HANDLE AS AGENT_ID," +
            "  WORKLOAD_OCCURRENCE_STATE AS APPL_STATUS,  -- partial result, need also MON_GET_AGENT.EVENT_STATE and .EVENT_TYPE\n" +
            "  -1 AS CODEPAGE_ID,         -- source not clear\n" +
            "  NUM_ASSOC_AGENTS," +
            "  MEMBER AS COORD_NODE_NUM,  -- deprecated\n" +
            "  '' AS AUTHORITY_LVL,       -- a bit map of authority (user, group, role, ...)\n" +
            "  CLIENT_PID," +
            "  -1 AS COORD_AGENT_PID,     -- an unique identifier generated by DB2 on Linux, or thread ID on other OSs\n" +
            "  TIMESTAMP('1970-01-01 00:00:00') AS STATUS_CHANGE_TIME,  -- source not clear\n" +
            "  CLIENT_PLATFORM," +
            "  CLIENT_PROTOCOL," +
            "  0 AS TERRITORY_CODE,       -- country code, 0 for DRDA AS connections\n" +
            "  APPLICATION_NAME AS APPL_NAME," +
            "  APPLICATION_ID AS APPL_ID," +
            "  '' AS SEQUENCE_NO,         -- increases when a unit of work completes, transaction ID = (APPL_ID, SEQUENCE_NO)\n" +
            "  '' AS PRIMARY_AUTH_ID,     -- PD_GET_DIAG_HIST\n" +
            "  SESSION_AUTH_ID," +
            "  'deprecated' AS CLIENT_NNAME,  -- deprecated\n" +
            "  CLIENT_PRDID," +
            "  '' INPUT_DB_ALIAS," +
            "  '' CLIENT_DB_ALIAS," +
            "  'SPLICE' AS DB_NAME," +
            "  '' AS DB_PATH,             -- MON_GET_DATABASE\n" +
            "  EXECUTION_ID," +
            "  '' AS CORR_TOKEN,          -- DRDA token, or APPLICATION_ID if not a DRDA connection\n" +
            "  CLIENT_USERID AS TPMON_CLIENT_USERID," +
            "  CLIENT_WRKSTNNAME AS TPMON_CLIENT_WKSTN," +
            "  CLIENT_APPLNAME AS TPMON_CLIENT_APP," +
            "  CLIENT_ACCTNG AS TPMON_ACC_STR," +
            "  0 AS DBPARTITIONNUM," +
            "  -1 AS WORKLOAD_ID,         -- MON_GET_WORKLOAD, MON_GET_ACTIVITY\n" +
            "  IS_SYSTEM_APPL," +
            "  MEMBER," +
            "  COORD_MEMBER," +
            "  0 AS COORD_DBPARTITIONNUM" +
            " FROM TABLE(SYSIBMADM.MON_GET_CONNECTION()) MON_GET_CONNECTION";

    public static String APPLICATIONS_VIEW_SQL = "create view APPLICATIONS as \n" +
            "SELECT" +
            "  SNAPSHOT_TIMESTAMP," +
            "  CLIENT_DB_ALIAS," +
            "  DB_NAME," +
            "  AGENT_ID," +
            "  APPL_NAME," +
            "  PRIMARY_AUTH_ID AS AUTHID," +
            "  APPL_ID," +
            "  APPL_STATUS," +
            "  STATUS_CHANGE_TIME," +
            "  SEQUENCE_NO," +
            "  CLIENT_PRDID," +
            "  CLIENT_PID," +
            "  CLIENT_PLATFORM," +
            "  CLIENT_PROTOCOL," +
            "  CLIENT_NNAME," +
            "  COORD_NODE_NUM," +
            "  COORD_AGENT_PID," +
            "  NUM_ASSOC_AGENTS," +
            "  TPMON_CLIENT_USERID," +
            "  TPMON_CLIENT_WKSTN," +
            "  TPMON_CLIENT_APP," +
            "  TPMON_ACC_STR," +
            "  DBPARTITIONNUM," +
            "  MEMBER," +
            "  COORD_MEMBER," +
            "  COORD_DBPARTITIONNUM" +
            " FROM SYSIBMADM.SNAPAPPL_INFO";
}
