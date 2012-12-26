/*
 * Copyright (c) 2008-2012, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.queue;

import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.QueueStoreConfig;
import com.hazelcast.nio.Data;
import com.hazelcast.nio.DataSerializable;
import com.sun.tools.javac.util.Pair;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

/**
 * User: ali
 * Date: 11/22/12
 * Time: 11:00 AM
 */
public class QueueContainer implements DataSerializable {

    private final LinkedList<QueueItem> itemQueue = new LinkedList<QueueItem>();

    private int partitionId;

    private QueueConfig config;

    private String name;

    private QueueService queueService;

    private long idGen = 0;

    private final QueueStoreWrapper store = new QueueStoreWrapper();

    public QueueContainer(String name) {
        this.name = name;
    }

    public QueueContainer(QueueService queueService, int partitionId, QueueConfig config, String name, boolean fromBackup) {
        this(name);
        this.queueService = queueService;
        this.partitionId = partitionId;
        setConfig(config);
        if (!fromBackup && store.isEnabled()){
            Set<Long> keys = store.loadAllKeys();
            for (Long key : keys) {
                QueueItem item = new QueueItem(key);
                itemQueue.offer(item);
                idGen++;
            }
        }
    }

    public QueueItem offer(Data data) {
        QueueItem item = new QueueItem(idGen++, data);
        if(itemQueue.offer(item)){
            return item;
        }
        return null;
    }

    public int size() {
        return itemQueue.size();
    }

    public void clear() {
        itemQueue.clear();
    }

    public QueueItem poll() {
        QueueItem item = itemQueue.poll();
        if (item != null && item.getData() == null) {
            item.setData(store.load(item.getItemId()));
        }
        return item;
    }

    public void pollBackup(){
        itemQueue.poll();
    }

    /**
     * iterates all items, checks equality with data
     * This method does not trigger store load.
     *
     * @param data
     * @return
     */
    public long remove(Data data) {
        Iterator<QueueItem> iter = itemQueue.iterator();
        while (iter.hasNext()) {
            QueueItem item = iter.next();
            if (item.equals(data)) {
                iter.remove();
                return item.getItemId();
            }
        }
        return -1;
    }

    public Data peek() {
        QueueItem item = itemQueue.peek();
        if (item == null) {
            return null;
        }
        Data data = item.getData();
        if (store.isEnabled() && data == null){
            data = store.load(item.getItemId());
            item.setData(data);
        }
        return data;
    }

    /**
     * This method does not trigger store load.
     * @param dataSet
     * @return
     */
    public boolean contains(List<Data> dataSet) {
        Set<QueueItem> set = new HashSet<QueueItem>(dataSet.size());
        for (Data data : dataSet) {
            set.add(new QueueItem(-1, data));
        }
        return itemQueue.containsAll(set);
    }

    /**
     * This method triggers store load.
     *
     * @return
     */
    public List<QueueItem> itemList() {
        List<QueueItem> itemList = new ArrayList<QueueItem>(itemQueue.size());
        for (QueueItem item : itemQueue) {
            Data data = item.getData();
            if (store.isEnabled() && data == null){
                data = store.load(item.getItemId());
                item.setData(data);
            }
            itemList.add(item);
        }
        return itemList;
    }

    public Pair<Set<Long>, List<Data>> drain(int maxSize){
        if (maxSize < 0 || maxSize > itemQueue.size()){
            maxSize = itemQueue.size();
        }
        Set<Long> keySet = new HashSet<Long>(maxSize);
        List<Data> dataList = new ArrayList<Data>(maxSize);
        for (int i=0; i < maxSize; i++){
            QueueItem item = poll();
            keySet.add(item.getItemId());
            dataList.add(item.getData());
        }
        return new Pair<Set<Long>, List<Data>>(keySet, dataList);
    }

    public void drainFromBackup(int maxSize){
        if (maxSize < 0){
            itemQueue.clear();
            return;
        }
        for (int i=0; i<maxSize; i++){
            pollBackup();
        }

    }

    public List<QueueItem> addAll(List<Data> dataSet){
        List<QueueItem> itemSet = new ArrayList<QueueItem>(dataSet.size());
        for (Data data: dataSet){
            QueueItem item = offer(data);
            if (item != null){
                itemSet.add(item);
            }
        }
        return itemSet;
    }

    /**
     * This method triggers store load
     *
     * @param dataList
     * @param retain
     * @return
     */
    public Map<Long, Data> compareCollection(List<Data> dataList, boolean retain){
        Iterator<QueueItem> iter = itemQueue.iterator();
        Map<Long, Data> keySet = new HashMap<Long, Data>();
        while (iter.hasNext()){
            QueueItem item = iter.next();
            Data data = item.getData();
            if (data == null && store.isEnabled()){
                data = store.load(item.getItemId());
                item.setData(data);
            }
            boolean contains = dataList.contains(data);
            if ((retain && !contains) || (!retain && contains)){
                keySet.put(item.getItemId(), data);
                iter.remove();
            }
        }
        return keySet;
    }

    public void compareCollectionBackup(Set<Long> keySet){
        Iterator<QueueItem> iter = itemQueue.iterator();
        while (iter.hasNext()){
            QueueItem item = iter.next();
            if (keySet.contains(item.getItemId())){
                iter.remove();
            }
        }
    }

    public int getPartitionId() {
        return partitionId;
    }

    public QueueConfig getConfig() {
        return config;
    }

    public boolean isStoreAsync(){
        return store.isAsync();
    }

    public QueueStoreWrapper getStore() {
        return store;
    }

    public void setConfig(QueueConfig config) {
        this.config = new QueueConfig(config);
        QueueStoreConfig storeConfig = config.getQueueStoreConfig();
        store.setConfig(storeConfig);
    }

    public void writeData(DataOutput out) throws IOException {
        out.writeInt(partitionId);
        out.writeInt(itemQueue.size());
        Iterator<QueueItem> iterator = itemQueue.iterator();
        while (iterator.hasNext()) {
            QueueItem item = iterator.next();
            item.writeData(out);
        }
    }

    public void readData(DataInput in) throws IOException {
        partitionId = in.readInt();
        int size = in.readInt();
        for (int j = 0; j < size; j++) {
            QueueItem item = new QueueItem();
            item.readData(in);
            itemQueue.offer(item);
            idGen++;
        }
    }

}