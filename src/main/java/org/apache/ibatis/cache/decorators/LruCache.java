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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;

/**
 * 最近最少使用缓存
 * 基于 LinkedHashMap 覆盖其 removeEldestEntry 方法实现。
 *
 * Lru (least recently used) cache decorator.
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {

  // 被装饰的底层cache对象
  private final Cache delegate;
  // 有序的hashmap，用于记录key最近的使用情况
  private Map<Object, Object> keyMap;
  // 记录最少被使用的缓存项的key
  private Object eldestKey;

  public LruCache(Cache delegate) {
    this.delegate = delegate;
    setSize(1024);
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(final int size) {
    // 重新设置缓存大小时，会重置keyMap字段，注意LinkedHashMap构造函数的第三个参数，true表示该LinkedHashMap记录的顺序是access-order
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;

      // 当调用LinkedHashMap.put方法，会调用此方法
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        // 如果已达到缓存上线，则更新eldestKey字段，后面会删除该项
        if (tooBig) {
          // 把eldestKey存入实例变量
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  @Override
  public void putObject(Object key, Object value) {
    // 添加缓存项
    delegate.putObject(key, value);
    // 删除最久未使用的缓存项
    cycleKeyList(key);
  }

  @Override
  public Object getObject(Object key) {
    // 修改LinkedHashMap中记录的顺序
    keyMap.get(key); // touch
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyMap.clear();
  }

  private void cycleKeyList(Object key) {
    keyMap.put(key, key);
    // eldestKey不为空，表示已经达到缓存上限
    if (eldestKey != null) {
      // 删除最久未使用的缓存项
      delegate.removeObject(eldestKey);
      eldestKey = null;
    }
  }

}
