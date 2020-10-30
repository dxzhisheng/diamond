/*
 * (C) 2007-2012 Alibaba Group Holding Limited.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2 as
 * published by the Free Software Foundation.
 * Authors:
 *   leiwen <chrisredfield1985@126.com> , boyan <killme2008@gmail.com>
 */
package com.taobao.diamond.io.watch;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.taobao.diamond.io.Path;
import com.taobao.diamond.io.watch.util.PathNode;


/**
 * WatchKey，表示一个注册的的凭证
 * 
 * @author boyan
 * @date 2010-5-4
 */
public class WatchKey {

    private volatile boolean valid;

    private final PathNode root;

    private List<WatchEvent<?>> changedEvents;

    private final Set<WatchEvent.Kind<?>> filterSet = new HashSet<WatchEvent.Kind<?>>();

    private final WatchService watcher;


    public WatchKey(final Path path, final WatchService watcher, boolean fireCreatedEventOnIndex,
            WatchEvent.Kind<?>... events) {
        valid = true;
        this.watcher = watcher;
        // 建立内存索引
        this.root = new PathNode(path, true);
        if (events != null) {
            for (WatchEvent.Kind<?> event : events) {
                filterSet.add(event);
            }
        }
        LinkedList<WatchEvent<?>> changedEvents = new LinkedList<WatchEvent<?>>();
        index(this.root, fireCreatedEventOnIndex, changedEvents);
        this.changedEvents = changedEvents;
    }


    /**
     * 索引目录
     * 
     * @param node
     */
    private void index(PathNode node, boolean fireCreatedEventOnIndex, LinkedList<WatchEvent<?>> changedEvents) {
        File file = node.getPath().getFile();
        if (!file.isDirectory()) {
            return;
        }
        File[] subFiles = file.listFiles();
        if (subFiles != null) {
            for (File subFile : subFiles) {
                PathNode subNode = new PathNode(new Path(subFile), false);
                if (fireCreatedEventOnIndex) {
                    changedEvents.add(new WatchEvent<Path>(StandardWatchEventKind.ENTRY_CREATE, 1, subNode.getPath()));
                }
                node.addChild(subNode);
                if (subNode.getPath().isDirectory()) {
                    index(subNode, fireCreatedEventOnIndex, changedEvents);
                }
            }
        }
    }


    public void cancel() {
        this.valid = false;
    }


    @Override
    public String toString() {
        return "WatchKey [root=" + root + ", valid=" + valid + "]";
    }


    public boolean isValid() {
        return valid && root != null;
    }


    public List<WatchEvent<?>> pollEvents() {
        if (changedEvents != null) {
            List<WatchEvent<?>> result = changedEvents;
            changedEvents = null;
            return result;
        }
        return null;
    }


    /**
     * 检测是否有变化
     * 
     * @return
     */
    boolean check() {
        //若监听单元中到变化事件列表不未空，表示此单元有变更事件
        if (this.changedEvents != null && this.changedEvents.size() > 0)
            return true;
        if (!this.valid)
            return false;
        List<WatchEvent<?>> list = new LinkedList<WatchEvent<?>>();
        //相比最初到监听path目录，是否有变更。
        if (check(root, list)) {
            //将收集到到变更事件列表引用于事件列表对象，返回存在事件变更
            this.changedEvents = list;
            return true;
        }
        else {
            return false;
        }
    }


    private boolean check(PathNode node, List<WatchEvent<?>> changedEvents) {
        Path nodePath = node.getPath();
        File nodeNewFile = new File(nodePath.getAbsolutePath());
        if (nodePath != null) {
            if (node.isRoot()) {
                //若是root且不存在，表明root被删除。因为check本方法多处调用，需要区分root和非root
                if (!nodeNewFile.exists())
                    //文件不存在，说明相比之前文件，此文件被删除了
                    return fireOnRootDeleted(changedEvents, nodeNewFile);
                else {
                    //若文件存在，需要进一步check其文件内或者子目录是否有变更
                    return checkNodeChildren(node, changedEvents, nodeNewFile);
                }
            }
            else {
                //因为check本方法多处调用，需要区分root和非root
                return checkNodeChildren(node, changedEvents, nodeNewFile);
            }
        }
        else
            throw new IllegalStateException("PathNode没有path");
    }


    private boolean checkNodeChildren(PathNode node, List<WatchEvent<?>> changedEvents, File nodeNewFile) {
        boolean changed = false;
        // 查看之前保存在内存中node结构对应在硬盘中的文件目录情况
        Iterator<PathNode> it = node.getChildren().iterator();
        // 保存内存node中的目录的现有名称path集合，用于判断是否有新增文件
        Set<String> childNameSet = new HashSet<String>();
        while (it.hasNext()) {
            PathNode child = it.next();
            Path childPath = child.getPath();
            childNameSet.add(childPath.getName());
            File childNewFile = new File(childPath.getAbsolutePath());
            // 1、判断文件是否还存在
            if (!childNewFile.exists() && filterSet.contains(StandardWatchEventKind.ENTRY_DELETE)) {
                //文件不存在，且监听filterSet类别中有删除场景，则在变更事件列表中添加删除变更事件
                changed = true;
                changedEvents.add(new WatchEvent<Path>(StandardWatchEventKind.ENTRY_DELETE, 1, childPath));
                it.remove();// 移除节点，从原来的node内存结构中删除此节点。
            }
            // 2、如果是文件，判断是否被修改
            if (childPath.isFile()) {
                if (checkFile(changedEvents, child, childNewFile) && !changed) {
                    changed = true;
                }

            }
            // 3、递归检测目录
            if (childPath.isDirectory()) {
                if (check(child, changedEvents) && !changed) {
                    changed = true;
                }
            }
        }

        // 查看是否有新增文件
        File[] newChildFiles = nodeNewFile.listFiles();
        if(newChildFiles!=null)
        for (File newChildFile : newChildFiles) {
            //判断磁盘中的文件夹是否在内存node结构里面。childNameSet上面存储了当前内存node目录结构
            if (!childNameSet.contains(newChildFile.getName())
                    && filterSet.contains(StandardWatchEventKind.ENTRY_CREATE)) {
                //若磁盘中某文件目录不在内存结构中，表明为新增。在变更事件列表中添加新创事件。
                changed = true;
                Path newChildPath = new Path(newChildFile);
                changedEvents.add(new WatchEvent<Path>(StandardWatchEventKind.ENTRY_CREATE, 1, newChildPath));
                PathNode newSubNode = new PathNode(newChildPath, false);
                node.addChild(newSubNode);// 新增子节点
                // 如果是目录，递归调用
                if (newChildFile.isDirectory()) {
                    checkNodeChildren(newSubNode, changedEvents, newChildFile);
                }
            }
        }
        return changed;
    }


    private boolean checkFile(List<WatchEvent<?>> changedEvents, PathNode child, File childNewFile) {
        boolean changed = false;
        // 查看文件是否被修改
        if (childNewFile.lastModified() != child.lastModified()
                && filterSet.contains(StandardWatchEventKind.ENTRY_MODIFY)) {
            changed = true;
            Path newChildPath = new Path(childNewFile);
            changedEvents.add(new WatchEvent<Path>(StandardWatchEventKind.ENTRY_MODIFY, 1, newChildPath));
            child.setPath(newChildPath);// 更新path
        }
        return changed;
    }


    private boolean fireOnRootDeleted(List<WatchEvent<?>> changedEvents, File nodeNewFile) {
        this.valid = false;
        if (filterSet.contains(StandardWatchEventKind.ENTRY_DELETE)) {
            changedEvents.add(new WatchEvent<Path>(StandardWatchEventKind.ENTRY_DELETE, 1, new Path(nodeNewFile)));
            return true;
        }
        return false;
    }


    public boolean reset() {
        if (!valid)
            return false;
        if (root == null)
            return false;
        return this.watcher.resetKey(this);
    }
}
