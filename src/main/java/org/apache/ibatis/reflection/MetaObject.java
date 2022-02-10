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

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.BeanWrapper;
import org.apache.ibatis.reflection.wrapper.CollectionWrapper;
import org.apache.ibatis.reflection.wrapper.MapWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;

/**
 * 元对象,各种get，set方法有点ognl表达式的味道
 * 可以参考MetaObjectTest来跟踪调试，基本上用到了reflection包下所有的类
 *
 * @author Clinton Begin
 */
public class MetaObject {

  // 原始javaBean对象
  private final Object originalObject;
  // 对象包装器
  private final ObjectWrapper objectWrapper;
  // 负责实例化originalObject的工厂对象
  private final ObjectFactory objectFactory;
  // 负责创建ObjectWrapper的工厂对象
  private final ObjectWrapperFactory objectWrapperFactory;
  // 用于创建并缓存Reflector对象的工厂对象
  private final ReflectorFactory reflectorFactory;

  private MetaObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    // 初始化上述字段
    this.originalObject = object;
    this.objectFactory = objectFactory;
    this.objectWrapperFactory = objectWrapperFactory;
    this.reflectorFactory = reflectorFactory;

    if (object instanceof ObjectWrapper) {
      // 如果对象本身已经是ObjectWrapper型，则直接赋给objectWrapper
      this.objectWrapper = (ObjectWrapper) object;
    } else if (objectWrapperFactory.hasWrapperFor(object)) {
      // 若ObjectWrapperFactory能够为该原始对象创建对应的ObjectWrapper对象，则优先使用ObjectWrapperFactory,而
      // DefaultObjectWrapperFactory.hasWrapperFor始终返回false，用户可以自定义ObjectWrapperFactory实现进行扩展
      this.objectWrapper = objectWrapperFactory.getWrapperFor(this, object);
    } else if (object instanceof Map) {
      // 若原始对象为map对象，则创建MapWrapper对象
      this.objectWrapper = new MapWrapper(this, (Map) object);
    } else if (object instanceof Collection) {
      // 若原始对象是Collection类型，则创建CollectionWrapper对象
      this.objectWrapper = new CollectionWrapper(this, (Collection) object);
    } else {
      // 若原始对象是普通的javaBean对象，则创建BeanWrapper对象
      this.objectWrapper = new BeanWrapper(this, object);
    }
  }

  // MetaObject的构造方法是private修饰的，只能通过forObject这个静态方法创建MetaObject对象
  public static MetaObject forObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    if (object == null) {
      // 若object为null，则统一返回SystemMetaObject.NULL_META_OBJECT
      return SystemMetaObject.NULL_META_OBJECT;
    } else {
      return new MetaObject(object, objectFactory, objectWrapperFactory, reflectorFactory);
    }
  }

  public ObjectFactory getObjectFactory() {
    return objectFactory;
  }

  public ObjectWrapperFactory getObjectWrapperFactory() {
    return objectWrapperFactory;
  }

  public ReflectorFactory getReflectorFactory() {
    return reflectorFactory;
  }

  public Object getOriginalObject() {
    return originalObject;
  }

  //--------以下方法都是委派给ObjectWrapper------
  //查找属性
  public String findProperty(String propName, boolean useCamelCaseMapping) {
    return objectWrapper.findProperty(propName, useCamelCaseMapping);
  }

  //取得getter的名字列表
  public String[] getGetterNames() {
    return objectWrapper.getGetterNames();
  }

  //取得setter的名字列表
  public String[] getSetterNames() {
    return objectWrapper.getSetterNames();
  }

  //取得setter的类型列表
  public Class<?> getSetterType(String name) {
    return objectWrapper.getSetterType(name);
  }

  //取得getter的类型列表
  public Class<?> getGetterType(String name) {
    return objectWrapper.getGetterType(name);
  }

  //是否有指定的setter
  public boolean hasSetter(String name) {
    return objectWrapper.hasSetter(name);
  }

  //是否有指定的getter
  public boolean hasGetter(String name) {
    return objectWrapper.hasGetter(name);
  }


  public Object getValue(String name) {
    // 解析属性表达式
    PropertyTokenizer prop = new PropertyTokenizer(name);
    // 处理子表达式
    if (prop.hasNext()) {
      // 根据PropertyTokenizer解析后制定的属性，创建相应的MetaObject对象
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        //如果上层就是null了，那就结束，返回null
        return null;
      } else {
        // 递归处理子表达式
        return metaValue.getValue(prop.getChildren());
      }
    } else {
      // 通过ObjectWrapper获取指定的属性值
      return objectWrapper.get(prop);
    }
  }

  //设置值
  public void setValue(String name, Object value) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        if (value == null) {
          //如果上层就是null了，还得看有没有儿子，没有那就结束
          // don't instantiate child path if value is null
          return;
        } else {
          //否则还得new一个，委派给ObjectWrapper.instantiatePropertyValue
          metaValue = objectWrapper.instantiatePropertyValue(name, prop, objectFactory);
        }
      }
      //递归调用setValue
      metaValue.setValue(prop.getChildren(), value);
    } else {
      //到了最后一层了，所以委派给ObjectWrapper.set
      objectWrapper.set(prop, value);
    }
  }

  //为某个属性生成元对象
  public MetaObject metaObjectForProperty(String name) {
    // 获取指定的属性
    Object value = getValue(name);
    // 创建该属性对象相应的MetaObject对象
    return MetaObject.forObject(value, objectFactory, objectWrapperFactory, reflectorFactory);
  }

  public ObjectWrapper getObjectWrapper() {
    return objectWrapper;
  }

  //是否是集合
  public boolean isCollection() {
    return objectWrapper.isCollection();
  }

  //添加属性
  public void add(Object element) {
    objectWrapper.add(element);
  }

  //添加属性
  public <E> void addAll(List<E> list) {
    objectWrapper.addAll(list);
  }

}
