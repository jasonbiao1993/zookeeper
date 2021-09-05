/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.BadArgumentsException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.MultiOperationRecord;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.common.StringUtils;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.proto.CheckVersionRequest;
import org.apache.zookeeper.proto.CreateRequest;
import org.apache.zookeeper.proto.CreateTTLRequest;
import org.apache.zookeeper.proto.DeleteRequest;
import org.apache.zookeeper.proto.ReconfigRequest;
import org.apache.zookeeper.proto.SetACLRequest;
import org.apache.zookeeper.proto.SetDataRequest;
import org.apache.zookeeper.server.ZooKeeperServer.ChangeRecord;
import org.apache.zookeeper.server.ZooKeeperServer.PrecalculatedDigest;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.auth.ServerAuthenticationProvider;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumOracleMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.CloseSessionTxn;
import org.apache.zookeeper.txn.CreateContainerTxn;
import org.apache.zookeeper.txn.CreateSessionTxn;
import org.apache.zookeeper.txn.CreateTTLTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This request processor is generally at the start of a RequestProcessor
 * change. It sets up any transactions associated with requests that change the
 * state of the system. It counts on ZooKeeperServer to update
 * outstandingRequests, so that it can take into account transactions that are
 * in the queue to be applied when generating a transaction.
 *
 * 通常请求处理链中的第一个请求
 */
public class PrepRequestProcessor extends ZooKeeperCriticalThread implements RequestProcessor {

    // 日志
    private static final Logger LOG = LoggerFactory.getLogger(PrepRequestProcessor.class);

    /**
     *  仅用作测试使用
     * this is only for testing purposes.
     * should never be used otherwise
     */
    private static boolean failCreate = false;

    /**
     *已提交请求队列
     */
    LinkedBlockingQueue<Request> submittedRequests = new LinkedBlockingQueue<Request>();

    /**
     * 下一个处理器
     */
    private final RequestProcessor nextProcessor;
    private final boolean digestEnabled;
    private DigestCalculator digestCalculator;

    /**
     * Zookeeper服务器
     */
    ZooKeeperServer zks;

    public enum DigestOpCode {
        NOOP, ADD, REMOVE, UPDATE;
    }

    public PrepRequestProcessor(ZooKeeperServer zks, RequestProcessor nextProcessor) {
        super(
            "ProcessThread(sid:" + zks.getServerId()
            + " cport:" + zks.getClientPort()
            + "):", zks.getZooKeeperServerListener());
        this.nextProcessor = nextProcessor;
        this.zks = zks;
        this.digestEnabled = ZooKeeperServer.isDigestEnabled();
        if (this.digestEnabled) {
            this.digestCalculator = new DigestCalculator();
        }
    }

    /**
     * method for tests to set failCreate
     * @param b
     */
    public static void setFailCreate(boolean b) {
        failCreate = b;
    }
    @Override
    public void run() {
        LOG.info(String.format("PrepRequestProcessor (sid:%d) started, reconfigEnabled=%s", zks.getServerId(), zks.reconfigEnabled));
        try {
            // 死循环，一直处理数据
            while (true) {
                ServerMetrics.getMetrics().PREP_PROCESSOR_QUEUE_SIZE.add(submittedRequests.size());
                // 获取请求，submittedRequests 为null,则会阻塞处理
                Request request = submittedRequests.take();
                ServerMetrics.getMetrics().PREP_PROCESSOR_QUEUE_TIME
                    .add(Time.currentElapsedTime() - request.prepQueueStartTime);
                // 是否可追踪
                if (LOG.isTraceEnabled()) {
                    long traceMask = ZooTrace.CLIENT_REQUEST_TRACE_MASK;
                    if (request.type == OpCode.ping) {
                        traceMask = ZooTrace.CLIENT_PING_TRACE_MASK;
                    }
                    ZooTrace.logRequest(LOG, traceMask, 'P', request, "");
                }

                // 死亡的请求无需处理
                if (Request.requestOfDeath == request) {
                    break;
                }

                request.prepStartTime = Time.currentElapsedTime();
                pRequest(request);
            }
        } catch (Exception e) {
            handleException(this.getName(), e);
        }
        LOG.info("PrepRequestProcessor exited loop!");
    }

    private ChangeRecord getRecordForPath(String path) throws KeeperException.NoNodeException {
        ChangeRecord lastChange = null;
        // 同步块
        synchronized (zks.outstandingChanges) {
            // 先从 outstandingChangesForPath 队列中获取
            lastChange = zks.outstandingChangesForPath.get(path);
            if (lastChange == null) {
                // 若 outstandingChangesForPath 不存在，从数据库中获取
                DataNode n = zks.getZKDatabase().getNode(path);
                if (n != null) {
                    // 节点存在
                    Set<String> children;
                    synchronized (n) {
                        children = n.getChildren();
                    }

                    // 新生 ChangeRecord
                    lastChange = new ChangeRecord(-1, path, n.stat, children.size(), zks.getZKDatabase().aclForNode(n));

                    if (digestEnabled) {
                        lastChange.precalculatedDigest = new PrecalculatedDigest(
                                digestCalculator.calculateDigest(path, n), 0);
                    }
                    lastChange.data = n.getData();
                }
            }
        }
        if (lastChange == null || lastChange.stat == null) {
            throw new KeeperException.NoNodeException(path);
        }
        return lastChange;
    }

    private ChangeRecord getOutstandingChange(String path) {
        synchronized (zks.outstandingChanges) {
            return zks.outstandingChangesForPath.get(path);
        }
    }

    protected void addChangeRecord(ChangeRecord c) {
        synchronized (zks.outstandingChanges) {
            zks.outstandingChanges.add(c);
            zks.outstandingChangesForPath.put(c.path, c);
            ServerMetrics.getMetrics().OUTSTANDING_CHANGES_QUEUED.add(1);
        }
    }

    /**
     * Grab current pending change records for each op in a multi-op.
     *
     * This is used inside MultiOp error code path to rollback in the event
     * of a failed multi-op.
     *
     * @param multiRequest
     * @return a map that contains previously existed records that probably need to be
     *         rolled back in any failure.
     */
    private Map<String, ChangeRecord> getPendingChanges(MultiOperationRecord multiRequest) {
        Map<String, ChangeRecord> pendingChangeRecords = new HashMap<String, ChangeRecord>();

        for (Op op : multiRequest) {
            // 获取 path 对应的路径
            String path = op.getPath();
            // 获取 path 对应的 ChangeRecord
            ChangeRecord cr = getOutstandingChange(path);
            // only previously existing records need to be rolled back.
            if (cr != null) {
                // 添加 path -> ChangeRecord 映射到 pendingChangeRecords
                pendingChangeRecords.put(path, cr);
            }

            /*
             * ZOOKEEPER-1624 - We need to store for parent's ChangeRecord
             * of the parent node of a request. So that if this is a
             * sequential node creation request, rollbackPendingChanges()
             * can restore previous parent's ChangeRecord correctly.
             *
             * Otherwise, sequential node name generation will be incorrect
             * for a subsequent request.
             */
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1 || path.indexOf('\0') != -1) {
                continue;
            }
            // 获取父路径
            String parentPath = path.substring(0, lastSlash);

            // 获取父路径对应的 ChangeRecord
            ChangeRecord parentCr = getOutstandingChange(parentPath);
            if (parentCr != null) {
                // 添加 parentPath -> parentCr 映射到 pendingChangeRecords
                pendingChangeRecords.put(parentPath, parentCr);
            }
        }

        return pendingChangeRecords;
    }

    /**
     * Rollback pending changes records from a failed multi-op.
     *
     * If a multi-op fails, we can't leave any invalid change records we created
     * around. We also need to restore their prior value (if any) if their prior
     * value is still valid.
     *
     * @param zxid
     * @param pendingChangeRecords
     */
    void rollbackPendingChanges(long zxid, Map<String, ChangeRecord> pendingChangeRecords) {
        synchronized (zks.outstandingChanges) {
            // Grab a list iterator starting at the END of the list so we can iterate in reverse
            Iterator<ChangeRecord> iter = zks.outstandingChanges.descendingIterator();
            while (iter.hasNext()) {
                ChangeRecord c = iter.next();
                if (c.zxid == zxid) {
                    iter.remove();
                    // Remove all outstanding changes for paths of this multi.
                    // Previous records will be added back later.
                    zks.outstandingChangesForPath.remove(c.path);
                } else {
                    break;
                }
            }

            // we don't need to roll back any records because there is nothing left.
            if (zks.outstandingChanges.isEmpty()) {
                return;
            }

            long firstZxid = zks.outstandingChanges.peek().zxid;

            for (ChangeRecord c : pendingChangeRecords.values()) {
                // Don't apply any prior change records less than firstZxid.
                // Note that previous outstanding requests might have been removed
                // once they are completed.
                if (c.zxid < firstZxid) {
                    continue;
                }

                // add previously existing records back.
                zks.outstandingChangesForPath.put(c.path, c);
            }
        }
    }

    /**
     * Performs basic validation of a path for a create request.
     * Throws if the path is not valid and returns the parent path.
     * @throws BadArgumentsException
     */
    private String validatePathForCreate(String path, long sessionId) throws BadArgumentsException {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1 || path.indexOf('\0') != -1 || failCreate) {
            LOG.info("Invalid path {} with session 0x{}", path, Long.toHexString(sessionId));
            throw new KeeperException.BadArgumentsException(path);
        }
        return path.substring(0, lastSlash);
    }

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param type
     * @param zxid
     * @param request
     * @param record
     */
    protected void pRequest2Txn(int type, long zxid, Request request, Record record, boolean deserialize) throws KeeperException, IOException, RequestProcessorException {
        if (request.getHdr() == null) {
            // 新生事务头
            request.setHdr(new TxnHeader(request.sessionId, request.cxid, zxid,
                    Time.currentWallTime(), type));
        }

        // 确定类型
        switch (type) {
        case OpCode.create:
        case OpCode.create2:
        case OpCode.createTTL:
        case OpCode.createContainer: {
            // 创建节点操作
            pRequest2TxnCreate(type, request, record, deserialize);
            break;
        }
        case OpCode.deleteContainer: {
            String path = new String(request.request.array(), UTF_8);
            String parentPath = getParentPathAndValidate(path);
            ChangeRecord nodeRecord = getRecordForPath(path);
            if (nodeRecord.childCount > 0) {
                throw new KeeperException.NotEmptyException(path);
            }
            if (EphemeralType.get(nodeRecord.stat.getEphemeralOwner()) == EphemeralType.NORMAL) {
                throw new KeeperException.BadVersionException(path);
            }
            ChangeRecord parentRecord = getRecordForPath(parentPath);
            request.setTxn(new DeleteTxn(path));
            parentRecord = parentRecord.duplicate(request.getHdr().getZxid());
            parentRecord.childCount--;
            parentRecord.stat.setPzxid(request.getHdr().getZxid());
            parentRecord.precalculatedDigest = precalculateDigest(
                    DigestOpCode.UPDATE, parentPath, parentRecord.data, parentRecord.stat);
            addChangeRecord(parentRecord);

            nodeRecord = new ChangeRecord(request.getHdr().getZxid(), path, null, -1, null);
            nodeRecord.precalculatedDigest = precalculateDigest(DigestOpCode.REMOVE, path);
            setTxnDigest(request, nodeRecord.precalculatedDigest);
            addChangeRecord(nodeRecord);
            break;
        }
        case OpCode.delete:
            // 删除节点请求
            // 检查会话，检查会话持有者是否未该 Owner
            zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
            DeleteRequest deleteRequest = (DeleteRequest) record;
            if (deserialize) {
                // 反序列化，将 ByteBuffer 转化为 Record
                ByteBufferInputStream.byteBuffer2Record(request.request, deleteRequest);
            }
            String path = deleteRequest.getPath();

            // 获取节点的父节点路径
            String parentPath = getParentPathAndValidate(path);
            // 获取父节点的Record
            ChangeRecord parentRecord = getRecordForPath(parentPath);
            // 检查 ACL 列表
            zks.checkACL(request.cnxn, parentRecord.acl, ZooDefs.Perms.DELETE, request.authInfo, path, null);

            // 获取节点的 Record
            ChangeRecord nodeRecord = getRecordForPath(path);
            checkAndIncVersion(nodeRecord.stat.getVersion(), deleteRequest.getVersion(), path);
            if (nodeRecord.childCount > 0) {
                throw new KeeperException.NotEmptyException(path);
            }

            // 新生删除事务
            request.setTxn(new DeleteTxn(path));
            parentRecord = parentRecord.duplicate(request.getHdr().getZxid());
            parentRecord.childCount--;
            parentRecord.stat.setPzxid(request.getHdr().getZxid());
            parentRecord.precalculatedDigest = precalculateDigest(
                    DigestOpCode.UPDATE, parentPath, parentRecord.data, parentRecord.stat);

            //  将parentRecord添加至outstandingChanges和outstandingChangesForPath中
            addChangeRecord(parentRecord);

            nodeRecord = new ChangeRecord(request.getHdr().getZxid(), path, null, -1, null);
            nodeRecord.precalculatedDigest = precalculateDigest(DigestOpCode.REMOVE, path);
            setTxnDigest(request, nodeRecord.precalculatedDigest);

            // 将新生成的ChangeRecord(包含了StatPersisted信息)添加至outstandingChanges和outstandingChangesForPath中
            addChangeRecord(nodeRecord);
            break;
        case OpCode.setData:
            // 设置数据请求
            // 检查会话，检查会话持有者是否未改 Owner
            zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
            SetDataRequest setDataRequest = (SetDataRequest) record;
            if (deserialize) {
                // 反序列化，将ByteBuffer转化为Record
                ByteBufferInputStream.byteBuffer2Record(request.request, setDataRequest);
            }

            // 获取节点路径
            path = setDataRequest.getPath();
            validatePath(path, request.sessionId);

            // 获取节点的 Record
            nodeRecord = getRecordForPath(path);

            // 检查 ACL 列表
            zks.checkACL(request.cnxn, nodeRecord.acl, ZooDefs.Perms.WRITE, request.authInfo, path, null);

            // 节点当前版本号
            zks.checkQuota(path, nodeRecord.data, setDataRequest.getData(), OpCode.setData);
            // 获取请求版本号
            int newVersion = checkAndIncVersion(nodeRecord.stat.getVersion(), setDataRequest.getVersion(), path);
            // 新生设置数据事务
            request.setTxn(new SetDataTxn(path, setDataRequest.getData(), newVersion));
            nodeRecord = nodeRecord.duplicate(request.getHdr().getZxid());
            nodeRecord.stat.setVersion(newVersion);
            nodeRecord.stat.setMtime(request.getHdr().getTime());
            nodeRecord.stat.setMzxid(zxid);
            nodeRecord.data = setDataRequest.getData();
            nodeRecord.precalculatedDigest = precalculateDigest(
                    DigestOpCode.UPDATE, path, nodeRecord.data, nodeRecord.stat);
            setTxnDigest(request, nodeRecord.precalculatedDigest);

            // nodeRecord 添加至outstandingChanges和outstandingChangesForPath中
            addChangeRecord(nodeRecord);
            break;
        case OpCode.reconfig:
            if (!zks.isReconfigEnabled()) {
                LOG.error("Reconfig operation requested but reconfig feature is disabled.");
                throw new KeeperException.ReconfigDisabledException();
            }

            if (ZooKeeperServer.skipACL) {
                LOG.warn("skipACL is set, reconfig operation will skip ACL checks!");
            }

            zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
            LeaderZooKeeperServer lzks;
            try {
                lzks = (LeaderZooKeeperServer) zks;
            } catch (ClassCastException e) {
                // standalone mode - reconfiguration currently not supported
                throw new KeeperException.UnimplementedException();
            }
            QuorumVerifier lastSeenQV = lzks.self.getLastSeenQuorumVerifier();
            // check that there's no reconfig in progress
            if (lastSeenQV.getVersion() != lzks.self.getQuorumVerifier().getVersion()) {
                throw new KeeperException.ReconfigInProgress();
            }
            ReconfigRequest reconfigRequest = (ReconfigRequest) record;
            long configId = reconfigRequest.getCurConfigId();

            if (configId != -1 && configId != lzks.self.getLastSeenQuorumVerifier().getVersion()) {
                String msg = "Reconfiguration from version "
                             + configId
                             + " failed -- last seen version is "
                             + lzks.self.getLastSeenQuorumVerifier().getVersion();
                throw new KeeperException.BadVersionException(msg);
            }

            String newMembers = reconfigRequest.getNewMembers();

            if (newMembers != null) { //non-incremental membership change
                LOG.info("Non-incremental reconfig");

                // Input may be delimited by either commas or newlines so convert to common newline separated format
                newMembers = newMembers.replaceAll(",", "\n");

                try {
                    Properties props = new Properties();
                    props.load(new StringReader(newMembers));
                    request.qv = QuorumPeerConfig.parseDynamicConfig(props, lzks.self.getElectionType(), true, false, lastSeenQV.getOraclePath());
                    request.qv.setVersion(request.getHdr().getZxid());
                } catch (IOException | ConfigException e) {
                    throw new KeeperException.BadArgumentsException(e.getMessage());
                }
            } else { //incremental change - must be a majority quorum system
                LOG.info("Incremental reconfig");

                List<String> joiningServers = null;
                String joiningServersString = reconfigRequest.getJoiningServers();
                if (joiningServersString != null) {
                    joiningServers = StringUtils.split(joiningServersString, ",");
                }

                List<String> leavingServers = null;
                String leavingServersString = reconfigRequest.getLeavingServers();
                if (leavingServersString != null) {
                    leavingServers = StringUtils.split(leavingServersString, ",");
                }

                if (!(lastSeenQV instanceof QuorumMaj) && !(lastSeenQV instanceof QuorumOracleMaj)) {
                    String msg = "Incremental reconfiguration requested but last configuration seen has a non-majority quorum system";
                    LOG.warn(msg);
                    throw new KeeperException.BadArgumentsException(msg);
                }
                Map<Long, QuorumServer> nextServers = new HashMap<Long, QuorumServer>(lastSeenQV.getAllMembers());
                try {
                    if (leavingServers != null) {
                        for (String leaving : leavingServers) {
                            long sid = Long.parseLong(leaving);
                            nextServers.remove(sid);
                        }
                    }
                    if (joiningServers != null) {
                        for (String joiner : joiningServers) {
                            // joiner should have the following format: server.x = server_spec;client_spec
                            String[] parts = StringUtils.split(joiner, "=").toArray(new String[0]);
                            if (parts.length != 2) {
                                throw new KeeperException.BadArgumentsException("Wrong format of server string");
                            }
                            // extract server id x from first part of joiner: server.x
                            Long sid = Long.parseLong(parts[0].substring(parts[0].lastIndexOf('.') + 1));
                            QuorumServer qs = new QuorumServer(sid, parts[1]);
                            if (qs.clientAddr == null || qs.electionAddr == null || qs.addr == null) {
                                throw new KeeperException.BadArgumentsException("Wrong format of server string - each server should have 3 ports specified");
                            }

                            // check duplication of addresses and ports
                            for (QuorumServer nqs : nextServers.values()) {
                                if (qs.id == nqs.id) {
                                    continue;
                                }
                                qs.checkAddressDuplicate(nqs);
                            }

                            nextServers.remove(qs.id);
                            nextServers.put(qs.id, qs);
                        }
                    }
                } catch (ConfigException e) {
                    throw new KeeperException.BadArgumentsException("Reconfiguration failed");
                }

                if (lastSeenQV instanceof QuorumMaj) {
                    request.qv = new QuorumMaj(nextServers);
                } else {
                    request.qv = new QuorumOracleMaj(nextServers, lastSeenQV.getOraclePath());
                }

                request.qv.setVersion(request.getHdr().getZxid());
            }
            if (QuorumPeerConfig.isStandaloneEnabled() && request.qv.getVotingMembers().size() < 2) {
                String msg = "Reconfig failed - new configuration must include at least 2 followers";
                LOG.warn(msg);
                throw new KeeperException.BadArgumentsException(msg);
            } else if (request.qv.getVotingMembers().size() < 1) {
                String msg = "Reconfig failed - new configuration must include at least 1 follower";
                LOG.warn(msg);
                throw new KeeperException.BadArgumentsException(msg);
            }

            if (!lzks.getLeader().isQuorumSynced(request.qv)) {
                String msg2 = "Reconfig failed - there must be a connected and synced quorum in new configuration";
                LOG.warn(msg2);
                throw new KeeperException.NewConfigNoQuorum();
            }

            nodeRecord = getRecordForPath(ZooDefs.CONFIG_NODE);
            zks.checkACL(request.cnxn, nodeRecord.acl, ZooDefs.Perms.WRITE, request.authInfo, null, null);
            SetDataTxn setDataTxn = new SetDataTxn(ZooDefs.CONFIG_NODE, request.qv.toString().getBytes(), -1);
            request.setTxn(setDataTxn);
            nodeRecord = nodeRecord.duplicate(request.getHdr().getZxid());
            nodeRecord.stat.setVersion(-1);
            nodeRecord.stat.setMtime(request.getHdr().getTime());
            nodeRecord.stat.setMzxid(zxid);
            nodeRecord.data = setDataTxn.getData();
            // Reconfig is currently a noop from digest computation
            // perspective since config node is not covered by the digests.
            nodeRecord.precalculatedDigest = precalculateDigest(
                    DigestOpCode.NOOP, ZooDefs.CONFIG_NODE, nodeRecord.data, nodeRecord.stat);
            setTxnDigest(request, nodeRecord.precalculatedDigest);
            addChangeRecord(nodeRecord);

            break;
        case OpCode.setACL:
            // 设置 ACL 请求
            // 检查会话，检查会话持有者是否为改Owner
            zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
            SetACLRequest setAclRequest = (SetACLRequest) record;
            if (deserialize) {
                // 反序列化，将ByteBuffer转化为Record
                ByteBufferInputStream.byteBuffer2Record(request.request, setAclRequest);
            }
            // 获取节点路径
            path = setAclRequest.getPath();
            validatePath(path, request.sessionId);
            // 移除重复的ACL项
            List<ACL> listACL = fixupACL(path, request.authInfo, setAclRequest.getAcl());
            // 获取节点的Record
            nodeRecord = getRecordForPath(path);
            zks.checkACL(request.cnxn, nodeRecord.acl, ZooDefs.Perms.ADMIN, request.authInfo, path, listACL);
            // 获取新版本
            newVersion = checkAndIncVersion(nodeRecord.stat.getAversion(), setAclRequest.getVersion(), path);
            // 设置请求事务
            request.setTxn(new SetACLTxn(path, listACL, newVersion));
            // 拷贝
            nodeRecord = nodeRecord.duplicate(request.getHdr().getZxid());
            nodeRecord.stat.setAversion(newVersion);
            nodeRecord.precalculatedDigest = precalculateDigest(
                    DigestOpCode.UPDATE, path, nodeRecord.data, nodeRecord.stat);
            setTxnDigest(request, nodeRecord.precalculatedDigest);
            // 将 nodeRecord 添加至outstandingChanges和outstandingChangesForPath中
            addChangeRecord(nodeRecord);
            break;
        case OpCode.createSession:
            // 创建会话请求
            // 将请求request缓存区rewind
            request.request.rewind();
            // 获取缓冲区大小
            int to = request.request.getInt();
            // 创建会话事务
            request.setTxn(new CreateSessionTxn(to));
            // 再次将request缓存区rewind
            request.request.rewind();
            // only add the global session tracker but not to ZKDb
            // 只添加全局会话跟踪器而不添加到 ZKDb
            zks.sessionTracker.trackSession(request.sessionId, to);
            // 设置会话owner
            zks.setOwner(request.sessionId, request.getOwner());
            break;
        case OpCode.closeSession:
            // We don't want to do this check since the session expiration thread
            // queues up this operation without being the session owner.
            // this request is the last of the session so it should be ok
            //zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
            // 关闭会话请求
            long startTime = Time.currentElapsedTime();
            synchronized (zks.outstandingChanges) {
                // need to move getEphemerals into zks.outstandingChanges
                // synchronized block, otherwise there will be a race
                // condition with the on flying deleteNode txn, and we'll
                // delete the node again here, which is not correct
                Set<String> es = zks.getZKDatabase().getEphemerals(request.sessionId);
                // 遍历所有的 outstandingChanges 队列所有的 ChangeRecord
                for (ChangeRecord c : zks.outstandingChanges) {
                    if (c.stat == null) {
                        // Doing a delete
                        // 若 stat = null, 直接移除
                        es.remove(c.path);
                    } else if (c.stat.getEphemeralOwner() == request.sessionId) {
                        // 若临时节点属于改会话，将其添加到es中
                        es.add(c.path);
                    }
                }

                // 遍历 es, 进行删除操作
                for (String path2Delete : es) {
                    if (digestEnabled) {
                        parentPath = getParentPathAndValidate(path2Delete);
                        parentRecord = getRecordForPath(parentPath);
                        parentRecord = parentRecord.duplicate(request.getHdr().getZxid());
                        parentRecord.stat.setPzxid(request.getHdr().getZxid());
                        parentRecord.precalculatedDigest = precalculateDigest(
                                DigestOpCode.UPDATE, parentPath, parentRecord.data, parentRecord.stat);
                        // 对父节点添加更新改变
                        addChangeRecord(parentRecord);
                    }
                    nodeRecord = new ChangeRecord(
                            request.getHdr().getZxid(), path2Delete, null, 0, null);
                    nodeRecord.precalculatedDigest = precalculateDigest(
                            DigestOpCode.REMOVE, path2Delete);
                    // 对子节点添加移除改变
                    addChangeRecord(nodeRecord);
                }
                if (ZooKeeperServer.isCloseSessionTxnEnabled()) {
                    // 设置关闭事务请求
                    request.setTxn(new CloseSessionTxn(new ArrayList<String>(es)));
                }
                // 关闭会话
                zks.sessionTracker.setSessionClosing(request.sessionId);
            }
            ServerMetrics.getMetrics().CLOSE_SESSION_PREP_TIME.add(Time.currentElapsedTime() - startTime);
            break;
        case OpCode.check:
            // 检查请求
            // 检查回话，检查会话持有者是否未该Owner
            zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
            CheckVersionRequest checkVersionRequest = (CheckVersionRequest) record;
            if (deserialize) {
                // 反序列化，将 ByteBuffer 转化为Record
                ByteBufferInputStream.byteBuffer2Record(request.request, checkVersionRequest);
            }

            // 获取节点路径
            path = checkVersionRequest.getPath();
            validatePath(path, request.sessionId);
            // 获取节点的 Record
            nodeRecord = getRecordForPath(path);
            // 检查访问列表
            zks.checkACL(request.cnxn, nodeRecord.acl, ZooDefs.Perms.READ, request.authInfo, path, null);
            // 添加检查事务请求
            request.setTxn(new CheckVersionTxn(
                path,
                checkAndIncVersion(nodeRecord.stat.getVersion(), checkVersionRequest.getVersion(), path)));
            break;
        default:
            LOG.warn("unknown type {}", type);
            break;
        }

        // If the txn is not going to mutate anything, like createSession,
        // we just set the current tree digest in it
        if (request.getTxnDigest() == null && digestEnabled) {
            setTxnDigest(request);
        }
    }

    private void pRequest2TxnCreate(int type, Request request, Record record, boolean deserialize) throws IOException, KeeperException {
        if (deserialize) {
            // 反序列化，将ByteBuffer转化为Record
            ByteBufferInputStream.byteBuffer2Record(request.request, record);
        }

        int flags;
        String path;
        List<ACL> acl;
        byte[] data;
        long ttl;
        if (type == OpCode.createTTL) {
            CreateTTLRequest createTtlRequest = (CreateTTLRequest) record;
            flags = createTtlRequest.getFlags();
            path = createTtlRequest.getPath();
            acl = createTtlRequest.getAcl();
            data = createTtlRequest.getData();
            ttl = createTtlRequest.getTtl();
        } else {
            CreateRequest createRequest = (CreateRequest) record;
            flags = createRequest.getFlags();
            path = createRequest.getPath();
            acl = createRequest.getAcl();
            data = createRequest.getData();
            ttl = -1;
        }
        // 获取创建模型
        CreateMode createMode = CreateMode.fromFlag(flags);
        validateCreateRequest(path, createMode, request, ttl);

        // 获取节点的父路径
        String parentPath = validatePathForCreate(path, request.sessionId);

        // 获取修正的 ACL
        List<ACL> listACL = fixupACL(path, request.authInfo, acl);

        // 获取父节点的 Record
        ChangeRecord parentRecord = getRecordForPath(parentPath);

        // 检查 ACL 列表
        zks.checkACL(request.cnxn, parentRecord.acl, ZooDefs.Perms.CREATE, request.authInfo, path, listACL);

        // 获取父节点Record的子节点版本号
        int parentCVersion = parentRecord.stat.getCversion();

        // 判断是否未顺序节点
        if (createMode.isSequential()) {
            // 在路径后面添加一串字符串
            path = path + String.format(Locale.ENGLISH, "%010d", parentCVersion);
        }
        // 校验路径
        validatePath(path, request.sessionId);
        try {
            if (getRecordForPath(path) != null) {
                throw new KeeperException.NodeExistsException(path);
            }
        } catch (KeeperException.NoNodeException e) {
            // ignore this one
        }
        boolean ephemeralParent = EphemeralType.get(parentRecord.stat.getEphemeralOwner()) == EphemeralType.NORMAL;
        if (ephemeralParent) {
            // 父节点为临时节点，报错
            throw new KeeperException.NoChildrenForEphemeralsException(path);
        }

        // 新的子节点版本号
        int newCversion = parentRecord.stat.getCversion() + 1;
        zks.checkQuota(path, null, data, OpCode.create);

        // 设置新事务
        if (type == OpCode.createContainer) {
            request.setTxn(new CreateContainerTxn(path, data, listACL, newCversion));
        } else if (type == OpCode.createTTL) {
            request.setTxn(new CreateTTLTxn(path, data, listACL, newCversion, ttl));
        } else {
            request.setTxn(new CreateTxn(path, data, listACL, createMode.isEphemeral(), newCversion));
        }

        TxnHeader hdr = request.getHdr();

        // 设置是否临时节点值
        long ephemeralOwner = 0;
        if (createMode.isContainer()) {
            ephemeralOwner = EphemeralType.CONTAINER_EPHEMERAL_OWNER;
        } else if (createMode.isTTL()) {
            ephemeralOwner = EphemeralType.TTL.toEphemeralOwner(ttl);
        } else if (createMode.isEphemeral()) {
            ephemeralOwner = request.sessionId;
        }
        StatPersisted s = DataTree.createStat(hdr.getZxid(), hdr.getTime(), ephemeralOwner);
        parentRecord = parentRecord.duplicate(request.getHdr().getZxid());
        parentRecord.childCount++;
        // 设置新的子节点版本号
        parentRecord.stat.setCversion(newCversion);
        parentRecord.stat.setPzxid(request.getHdr().getZxid());
        parentRecord.precalculatedDigest = precalculateDigest(
                DigestOpCode.UPDATE, parentPath, parentRecord.data, parentRecord.stat);

        // 将 ParentRecord 添加至 outstandingChanges 和 outstandingChangesForPath
        addChangeRecord(parentRecord);
        ChangeRecord nodeRecord = new ChangeRecord(
                request.getHdr().getZxid(), path, s, 0, listACL);
        nodeRecord.data = data;
        nodeRecord.precalculatedDigest = precalculateDigest(
                DigestOpCode.ADD, path, nodeRecord.data, s);
        setTxnDigest(request, nodeRecord.precalculatedDigest);

        // 将新生成的 ChangeRecord 包含 StatPersisted 添加至 outstandingChanges 和 outstandingChangesForPath
        addChangeRecord(nodeRecord);
    }

    private void validatePath(String path, long sessionId) throws BadArgumentsException {
        try {
            PathUtils.validatePath(path);
        } catch (IllegalArgumentException ie) {
            LOG.info("Invalid path {} with session 0x{}, reason: {}", path, Long.toHexString(sessionId), ie.getMessage());
            throw new BadArgumentsException(path);
        }
    }

    private String getParentPathAndValidate(String path) throws BadArgumentsException {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1 || path.indexOf('\0') != -1 || zks.getZKDatabase().isSpecialPath(path)) {
            throw new BadArgumentsException(path);
        }
        return path.substring(0, lastSlash);
    }

    private static int checkAndIncVersion(int currentVersion, int expectedVersion, String path) throws KeeperException.BadVersionException {
        if (expectedVersion != -1 && expectedVersion != currentVersion) {
            throw new KeeperException.BadVersionException(path);
        }
        return currentVersion + 1;
    }

    /**
     * This method will be called inside the ProcessRequestThread, which is a
     * singleton, so there will be a single thread calling this code.
     *
     * @param request
     */
    protected void pRequest(Request request) throws RequestProcessorException {
        // LOG.info("Prep>>> cxid = " + request.cxid + " type = " +
        // request.type + " id = 0x" + Long.toHexString(request.sessionId));
        // 将请求的hdr和txn设置为null
        request.setHdr(null);
        request.setTxn(null);

        if (!request.isThrottled()) {
          pRequestHelper(request);
        }

        // 给请求
        request.zxid = zks.getZxid();
        long timeFinishedPrepare = Time.currentElapsedTime();
        ServerMetrics.getMetrics().PREP_PROCESS_TIME.add(timeFinishedPrepare - request.prepStartTime);

        // 传递个下一个处理器处理
        nextProcessor.processRequest(request);
        ServerMetrics.getMetrics().PROPOSAL_PROCESS_TIME.add(Time.currentElapsedTime() - timeFinishedPrepare);
    }

    /**
     * This method is a helper to pRequest method
     *
     * @param request
     */
    private void pRequestHelper(Request request) throws RequestProcessorException {
        try {
            // 请求类型
            switch (request.type) {
            case OpCode.createContainer:
            case OpCode.create:
            case OpCode.create2:
                // 创建节点请求
                CreateRequest create2Request = new CreateRequest();
                // 处理请求
                pRequest2Txn(request.type, zks.getNextZxid(), request, create2Request, true);
                break;
            case OpCode.createTTL:
                CreateTTLRequest createTtlRequest = new CreateTTLRequest();
                pRequest2Txn(request.type, zks.getNextZxid(), request, createTtlRequest, true);
                break;
            case OpCode.deleteContainer:
            case OpCode.delete:
                // 删除节点请求
                DeleteRequest deleteRequest = new DeleteRequest();
                pRequest2Txn(request.type, zks.getNextZxid(), request, deleteRequest, true);
                break;
            case OpCode.setData:
                // 设置数据请求
                SetDataRequest setDataRequest = new SetDataRequest();
                pRequest2Txn(request.type, zks.getNextZxid(), request, setDataRequest, true);
                break;
            case OpCode.reconfig:
                ReconfigRequest reconfigRequest = new ReconfigRequest();
                ByteBufferInputStream.byteBuffer2Record(request.request, reconfigRequest);
                pRequest2Txn(request.type, zks.getNextZxid(), request, reconfigRequest, true);
                break;
            case OpCode.setACL:
                // 设置 ACL 请求
                SetACLRequest setAclRequest = new SetACLRequest();
                pRequest2Txn(request.type, zks.getNextZxid(), request, setAclRequest, true);
                break;
            case OpCode.check:
                // 版本检查
                CheckVersionRequest checkRequest = new CheckVersionRequest();
                pRequest2Txn(request.type, zks.getNextZxid(), request, checkRequest, true);
                break;
            case OpCode.multi:
                // 多重请求
                MultiOperationRecord multiRequest = new MultiOperationRecord();
                try {
                    // 将 ByteBuffer 转化为 Record
                    ByteBufferInputStream.byteBuffer2Record(request.request, multiRequest);
                } catch (IOException e) {
                    // 出现异常则重新生成 txn 头
                    request.setHdr(new TxnHeader(request.sessionId, request.cxid, zks.getNextZxid(), Time.currentWallTime(), OpCode.multi));
                    throw e;
                }
                List<Txn> txns = new ArrayList<Txn>();
                //Each op in a multi-op must have the same zxid!
                long zxid = zks.getNextZxid();
                KeeperException ke = null;

                //Store off current pending change records in case we need to rollback
                // 存储当前挂起的更改记录，以防我们需要回滚
                Map<String, ChangeRecord> pendingChanges = getPendingChanges(multiRequest);
                request.setHdr(new TxnHeader(request.sessionId, request.cxid, zxid,
                        Time.currentWallTime(), request.type));

                //  遍历请求
                for (Op op : multiRequest) {
                    Record subrequest = op.toRequestRecord();
                    int type;
                    Record txn;

                    /* If we've already failed one of the ops, don't bother
                     * trying the rest as we know it's going to fail and it
                     * would be confusing in the logfiles.
                     */
                    if (ke != null) {
                        // 发生异常
                        type = OpCode.error;
                        txn = new ErrorTxn(Code.RUNTIMEINCONSISTENCY.intValue());
                    } else {
                        /* Prep the request and convert to a Txn */
                        try {
                            // 将 request 转换为 txn
                            pRequest2Txn(op.getType(), zxid, request, subrequest, false);
                            type = op.getType();
                            txn = request.getTxn();
                        } catch (KeeperException e) {
                            // 转换发生异常
                            ke = e;

                            // 设置请求头类型
                            type = OpCode.error;
                            // 设置请求的txn
                            txn = new ErrorTxn(e.code().intValue());

                            if (e.code().intValue() > Code.APIERROR.intValue()) {
                                LOG.info("Got user-level KeeperException when processing {} aborting"
                                         + " remaining multi ops. Error Path:{} Error:{}",
                                         request.toString(),
                                         e.getPath(),
                                         e.getMessage());
                            }

                            // 设置异常
                            request.setException(e);

                            /* Rollback change records from failed multi-op */
                            // 从多重操作中回滚更改记录
                            rollbackPendingChanges(zxid, pendingChanges);
                        }
                    }

                    // TODO: I don't want to have to serialize it here and then
                    //       immediately deserialize in next processor. But I'm
                    //       not sure how else to get the txn stored into our list.

                    // 序列化
                    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos);
                        txn.serialize(boa, "request");
                        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
                        txns.add(new Txn(type, bb.array()));
                    }
                }

                // 设置请求的 xn
                request.setTxn(new MultiTxn(txns));
                if (digestEnabled) {
                    setTxnDigest(request);
                }

                break;

            //create/close session don't require request record
            case OpCode.createSession:
            case OpCode.closeSession:
                // 创建或者关闭会话
                if (!request.isLocalSession()) {
                    pRequest2Txn(request.type, zks.getNextZxid(), request, null, true);
                }
                break;

            //All the rest don't need to create a Txn - just verify session 其余的不需要创建 Txn - 只需验证会话
            case OpCode.sync:
            case OpCode.exists:
            case OpCode.getData:
            case OpCode.getACL:
            case OpCode.getChildren:
            case OpCode.getAllChildrenNumber:
            case OpCode.getChildren2:
            case OpCode.ping:
            case OpCode.setWatches:
            case OpCode.setWatches2:
            case OpCode.checkWatches:
            case OpCode.removeWatches:
            case OpCode.getEphemerals:
            case OpCode.multiRead:
            case OpCode.addWatch:
            case OpCode.whoAmI:
                zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
                break;
            default:
                LOG.warn("unknown type {}", request.type);
                break;
            }
        } catch (KeeperException e) {
            if (request.getHdr() != null) {
                request.getHdr().setType(OpCode.error);
                request.setTxn(new ErrorTxn(e.code().intValue()));
            }

            if (e.code().intValue() > Code.APIERROR.intValue()) {
                LOG.info(
                    "Got user-level KeeperException when processing {} Error Path:{} Error:{}",
                    request.toString(),
                    e.getPath(),
                    e.getMessage());
            }
            request.setException(e);
        } catch (Exception e) {
            // log at error level as we are returning a marshalling
            // error to the user
            LOG.error("Failed to process {}", request, e);

            StringBuilder sb = new StringBuilder();
            ByteBuffer bb = request.request;
            if (bb != null) {
                bb.rewind();
                while (bb.hasRemaining()) {
                    sb.append(Integer.toHexString(bb.get() & 0xff));
                }
            } else {
                sb.append("request buffer is null");
            }

            LOG.error("Dumping request buffer: 0x{}", sb.toString());
            if (request.getHdr() != null) {
                request.getHdr().setType(OpCode.error);
                request.setTxn(new ErrorTxn(Code.MARSHALLINGERROR.intValue()));
            }
        }
    }

    private static List<ACL> removeDuplicates(final List<ACL> acls) {
        if (acls == null || acls.isEmpty()) {
            return Collections.emptyList();
        }

        // This would be done better with a Set but ACL hashcode/equals do not
        // allow for null values
        final ArrayList<ACL> retval = new ArrayList<>(acls.size());
        for (final ACL acl : acls) {
            if (!retval.contains(acl)) {
                retval.add(acl);
            }
        }
        return retval;
    }

    private void validateCreateRequest(String path, CreateMode createMode, Request request, long ttl) throws KeeperException {
        if (createMode.isTTL() && !EphemeralType.extendedEphemeralTypesEnabled()) {
            throw new KeeperException.UnimplementedException();
        }
        try {
            EphemeralType.validateTTL(createMode, ttl);
        } catch (IllegalArgumentException e) {
            throw new BadArgumentsException(path);
        }
        if (createMode.isEphemeral()) {
            // Exception is set when local session failed to upgrade
            // so we just need to report the error
            if (request.getException() != null) {
                throw request.getException();
            }
            zks.sessionTracker.checkGlobalSession(request.sessionId, request.getOwner());
        } else {
            zks.sessionTracker.checkSession(request.sessionId, request.getOwner());
        }
    }

    /**
     * This method checks out the acl making sure it isn't null or empty,
     * it has valid schemes and ids, and expanding any relative ids that
     * depend on the requestor's authentication information.
     *
     * @param authInfo list of ACL IDs associated with the client connection
     * @param acls list of ACLs being assigned to the node (create or setACL operation)
     * @return verified and expanded ACLs
     * @throws KeeperException.InvalidACLException
     */
    public static List<ACL> fixupACL(String path, List<Id> authInfo, List<ACL> acls) throws KeeperException.InvalidACLException {
        // check for well formed ACLs
        // This resolves https://issues.apache.org/jira/browse/ZOOKEEPER-1877
        // 移除重复的 ACL 项
        List<ACL> uniqacls = removeDuplicates(acls);
        if (uniqacls == null || uniqacls.size() == 0) {
            throw new KeeperException.InvalidACLException(path);
        }
        List<ACL> rv = new ArrayList<>();
        for (ACL a : uniqacls) {
            LOG.debug("Processing ACL: {}", a);
            if (a == null) {
                throw new KeeperException.InvalidACLException(path);
            }
            Id id = a.getId();
            if (id == null || id.getScheme() == null) {
                throw new KeeperException.InvalidACLException(path);
            }
            if (id.getScheme().equals("world") && id.getId().equals("anyone")) {
                rv.add(a);
            } else if (id.getScheme().equals("auth")) {
                // This is the "auth" id, so we have to expand it to the
                // authenticated ids of the requestor
                boolean authIdValid = false;
                for (Id cid : authInfo) {
                    ServerAuthenticationProvider ap = ProviderRegistry.getServerProvider(cid.getScheme());
                    if (ap == null) {
                        LOG.error("Missing AuthenticationProvider for {}", cid.getScheme());
                    } else if (ap.isAuthenticated()) {
                        authIdValid = true;
                        rv.add(new ACL(a.getPerms(), cid));
                    }
                }
                if (!authIdValid) {
                    throw new KeeperException.InvalidACLException(path);
                }
            } else {
                ServerAuthenticationProvider ap = ProviderRegistry.getServerProvider(id.getScheme());
                if (ap == null || !ap.isValid(id.getId())) {
                    throw new KeeperException.InvalidACLException(path);
                }
                rv.add(a);
            }
        }
        return rv;
    }

    public void processRequest(Request request) {
        request.prepQueueStartTime = Time.currentElapsedTime();
        // 将请求提交至队列中
        submittedRequests.add(request);
        ServerMetrics.getMetrics().PREP_PROCESSOR_QUEUED.add(1);
    }

    public void shutdown() {
        LOG.info("Shutting down");
        submittedRequests.clear();
        submittedRequests.add(Request.requestOfDeath);
        nextProcessor.shutdown();
    }

    /**
     * Calculate the node digest and tree digest after the change.
     *
     * @param type the type of operations about the digest change
     * @param path the path of the node
     * @param data the data of the node
     * @param s the stat of the node
     *
     * @return PrecalculatedDigest the pair of node and tree digest
     */
    private PrecalculatedDigest precalculateDigest(DigestOpCode type, String path,
            byte[] data, StatPersisted s) throws KeeperException.NoNodeException {

        if (!digestEnabled) {
            return null;
        }

        long prevNodeDigest;
        long newNodeDigest;

        switch (type) {
            case ADD:
                prevNodeDigest = 0;
                newNodeDigest = digestCalculator.calculateDigest(path, data, s);
                break;
            case REMOVE:
                prevNodeDigest = getRecordForPath(path).precalculatedDigest.nodeDigest;
                newNodeDigest = 0;
                break;
            case UPDATE:
                prevNodeDigest = getRecordForPath(path).precalculatedDigest.nodeDigest;
                newNodeDigest = digestCalculator.calculateDigest(path, data, s);
                break;
            case NOOP:
                newNodeDigest = prevNodeDigest = 0;
                break;
            default:
                return null;
        }
        long treeDigest = getCurrentTreeDigest() - prevNodeDigest + newNodeDigest;
        return new PrecalculatedDigest(newNodeDigest, treeDigest);
    }

    private PrecalculatedDigest precalculateDigest(
            DigestOpCode type, String path) throws KeeperException.NoNodeException {
        return precalculateDigest(type, path, null, null);
    }

    /**
     * Query the current tree digest from DataTree or outstandingChanges list.
     *
     * @return current tree digest
     */
    private long getCurrentTreeDigest() {
        long digest;
        synchronized (zks.outstandingChanges) {
            if (zks.outstandingChanges.isEmpty()) {
                digest = zks.getZKDatabase().getDataTree().getTreeDigest();
                LOG.debug("Digest got from data tree is: {}", digest);
            } else {
                digest = zks.outstandingChanges.peekLast().precalculatedDigest.treeDigest;
                LOG.debug("Digest got from outstandingChanges is: {}", digest);
            }
        }
        return digest;
    }

    private void setTxnDigest(Request request) {
        request.setTxnDigest(new TxnDigest(digestCalculator.getDigestVersion(), getCurrentTreeDigest()));
    }

    private void setTxnDigest(Request request, PrecalculatedDigest preCalculatedDigest) {
        if (preCalculatedDigest == null) {
            return;
        }
        request.setTxnDigest(new TxnDigest(digestCalculator.getDigestVersion(), preCalculatedDigest.treeDigest));
    }
}
