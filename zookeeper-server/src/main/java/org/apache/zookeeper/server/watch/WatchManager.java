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

package org.apache.zookeeper.server.watch;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.server.ServerCnxn;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ZooTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class manages watches. It allows watches to be associated with a string
 * and removes watchers and their watches in addition to managing triggers.
 *
 * 管理 Watcher
 */
public class WatchManager implements IWatchManager {

    /**
     * 日志
     */
    private static final Logger LOG = LoggerFactory.getLogger(WatchManager.class);

    /**
     * path -> watcher set
     */
    private final Map<String, Set<Watcher>> watchTable = new HashMap<>();

    /**
     * watcher -> path set
     */
    private final Map<Watcher, Set<String>> watch2Paths = new HashMap<>();

    /**
     * 监控模型管理
     */
    private final WatcherModeManager watcherModeManager = new WatcherModeManager();

    @Override
    public synchronized int size() {
        int result = 0;
        // 遍历 watchTable
        for (Set<Watcher> watches : watchTable.values()) {
            result += watches.size();
        }
        // 统计返回总 Watcher 数量
        return result;
    }

    private boolean isDeadWatcher(Watcher watcher) {
        // 是过期的 Watcher
        return watcher instanceof ServerCnxn && ((ServerCnxn) watcher).isStale();
    }

    @Override
    public boolean addWatch(String path, Watcher watcher) {
        // 添加 watch
        return addWatch(path, watcher, WatcherMode.DEFAULT_WATCHER_MODE);
    }

    @Override
    public synchronized boolean addWatch(String path, Watcher watcher, WatcherMode watcherMode) {
        // 判断 watch 是否死亡
        if (isDeadWatcher(watcher)) {
            LOG.debug("Ignoring addWatch with closed cnxn");
            return false;
        }

        // 通过 path 获取 watcher set
        Set<Watcher> list = watchTable.get(path);
        if (list == null) {
            // don't waste memory if there are few watches on a node
            // rehash when the 4th entry is added, doubling size thereafter
            // seems like a good compromise
            // 不存在 watcher set，创建并保存
            list = new HashSet<>(4);
            watchTable.put(path, list);
        }

        // 添加监控
        list.add(watcher);

        // 通过 watcher 获取 path set
        Set<String> paths = watch2Paths.get(watcher);
        if (paths == null) {
            // cnxns typically have many watches, so use default cap here
            // 不存在 path set ，创建并保存到 watch2Paths
            paths = new HashSet<>();
            watch2Paths.put(watcher, paths);
        }

        // 设置监控模型
        watcherModeManager.setWatcherMode(watcher, path, watcherMode);

        // 添加 path 到 watcher 对应的 path set
        return paths.add(path);
    }

    @Override
    public synchronized void removeWatcher(Watcher watcher) {
        // 移除监控
        Set<String> paths = watch2Paths.remove(watcher);
        if (paths == null) {
            // path set is null, immediate return
            return;
        }

        // 遍历路径
        for (String p : paths) {
            // 获取路径对应得 watcher set
            Set<Watcher> list = watchTable.get(p);
            if (list != null) {
                // watcher set 不为空，移除 watcher
                list.remove(watcher);
                if (list.isEmpty()) {
                    // 如果 watcher 为空，移除 watchTable 对应的 watcher
                    watchTable.remove(p);
                }
            }

            // 移除监控模型
            watcherModeManager.removeWatcher(watcher, p);
        }
    }

    @Override
    public WatcherOrBitSet triggerWatch(String path, EventType type) {
        // 触发监控
        return triggerWatch(path, type, null);
    }

    @Override
    public WatcherOrBitSet triggerWatch(String path, EventType type, WatcherOrBitSet supress) {
        // 创建监控事件
        WatchedEvent e = new WatchedEvent(type, KeeperState.SyncConnected, path);
        Set<Watcher> watchers = new HashSet<>();
        // 路径父迭代器
        PathParentIterator pathParentIterator = getPathParentIterator(path);
        synchronized (this) {
            // 迭代路径
            for (String localPath : pathParentIterator.asIterable()) {
                // 获取路径对应的监控
                Set<Watcher> thisWatchers = watchTable.get(localPath);
                if (thisWatchers == null || thisWatchers.isEmpty()) {
                    continue;
                }

                // watchers 不为null, 迭代处理 watcher
                Iterator<Watcher> iterator = thisWatchers.iterator();
                while (iterator.hasNext()) {
                    // 获取 watcher
                    Watcher watcher = iterator.next();

                    // 获取 watcher mode
                    WatcherMode watcherMode = watcherModeManager.getWatcherMode(watcher, localPath);
                    if (watcherMode.isRecursive()) {
                        // 是需要递归
                        if (type != EventType.NodeChildrenChanged) {
                            // 不是节点的孩子节点发生改变，添加 watcher
                            watchers.add(watcher);
                        }
                    } else if (!pathParentIterator.atParentPath()) {
                        // 不是父路径，添加 watcher
                        watchers.add(watcher);
                        if (!watcherMode.isPersistent()) {
                            // 不是持久监控，移除 watcher
                            iterator.remove();
                            Set<String> paths = watch2Paths.get(watcher);
                            if (paths != null) {
                                // 移除 watcher 对应的 paths 中对应的 path
                                paths.remove(localPath);
                            }
                        }
                    }
                }
                if (thisWatchers.isEmpty()) {
                    // 没有 watch, 移除 路径对应的 watcher set
                    watchTable.remove(localPath);
                }
            }
        }
        if (watchers.isEmpty()) {
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK, "No watchers for " + path);
            }
            return null;
        }

        for (Watcher w : watchers) {
            if (supress != null && supress.contains(w)) {
                // 压制的 watcher 不做处理
                continue;
            }

            // 处理监控
            w.process(e);
        }

        switch (type) {
            case NodeCreated:
                ServerMetrics.getMetrics().NODE_CREATED_WATCHER.add(watchers.size());
                break;

            case NodeDeleted:
                ServerMetrics.getMetrics().NODE_DELETED_WATCHER.add(watchers.size());
                break;

            case NodeDataChanged:
                ServerMetrics.getMetrics().NODE_CHANGED_WATCHER.add(watchers.size());
                break;

            case NodeChildrenChanged:
                ServerMetrics.getMetrics().NODE_CHILDREN_WATCHER.add(watchers.size());
                break;
            default:
                // Other types not logged.
                break;
        }

        // 返回
        return new WatcherOrBitSet(watchers);
    }

    @Override
    public synchronized String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(watch2Paths.size()).append(" connections watching ").append(watchTable.size()).append(" paths\n");

        int total = 0;
        for (Set<String> paths : watch2Paths.values()) {
            total += paths.size();
        }
        sb.append("Total watches:").append(total);

        return sb.toString();
    }

    /**
     * 保存监控信息到磁盘
     * @param pwriter the writer to dump the watches
     * @param byPath iff true output watches by paths, otw output
     */
    @Override
    public synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        if (byPath) {
            for (Entry<String, Set<Watcher>> e : watchTable.entrySet()) {
                pwriter.println(e.getKey());
                for (Watcher w : e.getValue()) {
                    pwriter.print("\t0x");
                    pwriter.print(Long.toHexString(((ServerCnxn) w).getSessionId()));
                    pwriter.print("\n");
                }
            }
        } else {
            for (Entry<Watcher, Set<String>> e : watch2Paths.entrySet()) {
                pwriter.print("0x");
                pwriter.println(Long.toHexString(((ServerCnxn) e.getKey()).getSessionId()));
                for (String path : e.getValue()) {
                    pwriter.print("\t");
                    pwriter.println(path);
                }
            }
        }
    }

    @Override
    public synchronized boolean containsWatcher(String path, Watcher watcher) {
        WatcherMode watcherMode = watcherModeManager.getWatcherMode(watcher, path);
        PathParentIterator pathParentIterator = getPathParentIterator(path);
        for (String localPath : pathParentIterator.asIterable()) {
            Set<Watcher> watchers = watchTable.get(localPath);
            if (!pathParentIterator.atParentPath()) {
                if (watchers != null) {
                    return true;    // at the leaf node, all watcher types match
                }
            }
            if (watcherMode.isRecursive()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean removeWatcher(String path, Watcher watcher) {
        Set<String> paths = watch2Paths.get(watcher);
        if (paths == null || !paths.remove(path)) {
            return false;
        }

        Set<Watcher> list = watchTable.get(path);
        if (list == null || !list.remove(watcher)) {
            return false;
        }

        if (list.isEmpty()) {
            watchTable.remove(path);
        }

        watcherModeManager.removeWatcher(watcher, path);

        return true;
    }

    @Override
    public synchronized WatchesReport getWatches() {
        Map<Long, Set<String>> id2paths = new HashMap<>();
        for (Entry<Watcher, Set<String>> e : watch2Paths.entrySet()) {
            Long id = ((ServerCnxn) e.getKey()).getSessionId();
            Set<String> paths = new HashSet<>(e.getValue());
            id2paths.put(id, paths);
        }
        return new WatchesReport(id2paths);
    }

    @Override
    public synchronized WatchesPathReport getWatchesByPath() {
        Map<String, Set<Long>> path2ids = new HashMap<>();
        for (Entry<String, Set<Watcher>> e : watchTable.entrySet()) {
            Set<Long> ids = new HashSet<>(e.getValue().size());
            path2ids.put(e.getKey(), ids);
            for (Watcher watcher : e.getValue()) {
                ids.add(((ServerCnxn) watcher).getSessionId());
            }
        }
        return new WatchesPathReport(path2ids);
    }

    @Override
    public synchronized WatchesSummary getWatchesSummary() {
        int totalWatches = 0;
        for (Set<String> paths : watch2Paths.values()) {
            totalWatches += paths.size();
        }
        return new WatchesSummary(watch2Paths.size(), watchTable.size(), totalWatches);
    }

    @Override
    public void shutdown() { /* do nothing */ }

    @Override
    public int getRecursiveWatchQty() {
        return watcherModeManager.getRecursiveQty();
    }

    private PathParentIterator getPathParentIterator(String path) {
        if (watcherModeManager.getRecursiveQty() == 0) {
            return PathParentIterator.forPathOnly(path);
        }
        return PathParentIterator.forAll(path);
    }
}
