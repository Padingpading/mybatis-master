/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Deque;
import java.util.LinkedList;

import org.apache.ibatis.cache.Cache;

/**
 * 软引用缓存,核心是SoftReference
 * Soft Reference cache decorator
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class SoftCache implements Cache {
  // 在SoftCache中，最近使用的一部分缓存项不会被GC回收，这就是通过将其value添加到hardLinksToAvoidGarbageCollection集合中实现的
  private final Deque<Object> hardLinksToAvoidGarbageCollection;
  // 引用队列，用于记录已经被GC回收的缓存项对应的SoftEntry对象
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
  // 底层被装饰的底层Cache对象
  private final Cache delegate;
  // 强连接的个数，默认是256
  private int numberOfHardLinks;

  public SoftCache(Cache delegate) {
    this.delegate = delegate;
    //默认链表可以存256元素
    this.numberOfHardLinks = 256;
    this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
    this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    removeGarbageCollectedItems();
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.numberOfHardLinks = size;
  }

  @Override
  public void putObject(Object key, Object value) {
    // 清除已经被GC回收的缓存项
    removeGarbageCollectedItems();
    // 向缓存中添加缓存项
    delegate.putObject(key, new SoftEntry(key, value, queueOfGarbageCollectedEntries));
  }

  @Override
  public Object getObject(Object key) {
    Object result = null;
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    // 从缓存中查找对应的缓存项
    SoftReference<Object> softReference = (SoftReference<Object>) delegate.getObject(key);
    // 检测缓存中是否有对应的缓存项
    if (softReference != null) {
      // 获取SoftReference引用的value
      result = softReference.get();
      // 已经被GC回收
      if (result == null) {
        // 从缓存中清除对应的缓存项
        delegate.removeObject(key);
        // 未被GC回收
      } else {
        // See #586 (and #335) modifications need more than a read lock
        synchronized (hardLinksToAvoidGarbageCollection) {
          // 缓存项的value添加到hardLinksToAvoidGarbageCollection集合中保存
          hardLinksToAvoidGarbageCollection.addFirst(result);
          if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
            // 超过numberOfHardLinks，将最老的缓存项从hardLinksToAvoidGarbageCollection集合中清除
            hardLinksToAvoidGarbageCollection.removeLast();
          }
        }
      }
    }
    return result;
  }

  @Override
  public Object removeObject(Object key) {
    removeGarbageCollectedItems();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    synchronized (hardLinksToAvoidGarbageCollection) {
      // 清理强引用集合
      hardLinksToAvoidGarbageCollection.clear();
    }
    // 清理被GC回收的缓存项
    removeGarbageCollectedItems();
    // 清除底层delegate缓存中的缓存项
    delegate.clear();
  }

  private void removeGarbageCollectedItems() {
    SoftEntry sv;
    // 遍历queueOfGarbageCollectedEntries集合
    while ((sv = (SoftEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      // 将已经被GC回收的value对象对应的缓存项清除
      delegate.removeObject(sv.key);
    }
  }

  private static class SoftEntry extends SoftReference<Object> {
    private final Object key;

    SoftEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      // 指向value的引用是软引用，且关联了引用队列
      super(value, garbageCollectionQueue);
      // 强引用
      this.key = key;
    }
  }

}
