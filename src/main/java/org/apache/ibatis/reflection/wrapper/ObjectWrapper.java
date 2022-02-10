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
package org.apache.ibatis.reflection.wrapper;

import java.util.List;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * 对象包装器,抽象了对象的属性信息，定义了一系列查询对象属性信息的方法以及更新属性的方法
 *
 * @author Clinton Begin
 */
public interface ObjectWrapper {

  // 如果ObjectWrapper中封装的是普通的bean对象，则调用相应属性的相应getter方法，如果封装的是集合类，则获取指定key或下标对应的value值
  Object get(PropertyTokenizer prop);

  // 如果ObjectWrapper中封装的是普通的bean对象，则调用相应属性的相应setter方法，如果封装的是集合类，则获取指定key或下标对应的value值
  void set(PropertyTokenizer prop, Object value);

  // 查找属性表达式指定的属性，第二个参数表示是否忽略属性表达式中的下划线
  String findProperty(String name, boolean useCamelCaseMapping);

  //取得getter的名字列表
  String[] getGetterNames();

  //取得setter的名字列表
  String[] getSetterNames();

  // 解析属性表达式指定属性的setter方法的参数类型
  Class<?> getSetterType(String name);

  // 解析属性表达式指定属性的getter方法的返回值类型
  Class<?> getGetterType(String name);

  //是否有指定的setter
  boolean hasSetter(String name);

  //是否有指定的getter
  boolean hasGetter(String name);

  // 为属性表达式指定的属性创建相应的MetaObject对象
  MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

  // 封装的对象是否为Collection类型
  boolean isCollection();

  // 调用Collection对象的add方法
  void add(Object element);

  // 调用Collection对象的addAll方法
  <E> void addAll(List<E> element);

}
