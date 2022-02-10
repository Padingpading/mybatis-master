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
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.ibatis.util.MapUtil;

public class DefaultReflectorFactory implements ReflectorFactory {
  // 是否开启对Reflector对象的缓存
  private boolean classCacheEnabled = true;
  // 使用ConcurrentHashMap集合实现对Reflector对象的缓存
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<>();

  public DefaultReflectorFactory() {
  }

  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  // 为指定的class创建Reflector对象，并将对象缓存到map中
  @Override
  public Reflector findForClass(Class<?> type) {
    // 检测是否开启缓存
    if (classCacheEnabled) {
      // synchronized (type) removed see issue #461
      // 创建Reflector对象，并放入缓存
      return MapUtil.computeIfAbsent(reflectorMap, type, Reflector::new);
    } else {
      // 未开启缓存，在直接创建并返回对象
      return new Reflector(type);
    }
  }

}
