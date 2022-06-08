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
package org.apache.ibatis.cache;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.cache.decorators.TransactionalCache;
import org.apache.ibatis.util.MapUtil;

/**
 * 事务缓存管理器，被CachingExecutor使用
 *
 * @author Clinton Begin
 */
public class TransactionalCacheManager {

  // 这里创建了一个Map,代表就是一个事务缓存管理器存在多个暂存区
  // Key为【缓存区】value为【暂存区】
  // key是从CachingExecutor中传递过来的，从MapperStatement获取的，因为MapperStatement是在Mybatis初始化的时候就已经加载好的
  // 所以多个线程共用1份
  // 综合下来就体现了：key为【缓存区】多个线程共用，value为缓存区，为会话私有的
  private final Map<Cache, TransactionalCache> transactionalCaches = new HashMap<>();

  public void clear(Cache cache) {
    getTransactionalCache(cache).clear();
  }

  // 得到某个TransactionalCache的值
  public Object getObject(Cache cache, CacheKey key) {
    return getTransactionalCache(cache).getObject(key);
  }

  public void putObject(Cache cache, CacheKey key, Object value) {
    getTransactionalCache(cache).putObject(key, value);
  }

  // 提交时全部提交
  public void commit() {
    for (TransactionalCache txCache : transactionalCaches.values()) {
      txCache.commit();
    }
  }

  // 回滚时全部回滚
  public void rollback() {
    for (TransactionalCache txCache : transactionalCaches.values()) {
      txCache.rollback();
    }
  }

  private TransactionalCache getTransactionalCache(Cache cache) {
    // 创建TransactionalCache对象并添加到TransactionalCaches集合中
    return MapUtil.computeIfAbsent(transactionalCaches, cache, TransactionalCache::new);
  }

}
