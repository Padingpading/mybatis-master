package com.mashibing.e_objectFactory;

import org.apache.ibatis.reflection.factory.DefaultObjectFactory;

import java.util.List;

public class MyObjectFactory extends DefaultObjectFactory {

  @Override
  public <T> T create(Class<T> type) {
    return super.create(type);
  }

  //DefaultObjectFactory的create(Class type)方法也会调用此方法，所以只需要在此方法中添加逻辑即可
  @Override
  public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    T ret = super.create(type, constructorArgTypes, constructorArgs);
    if (User.class.isAssignableFrom(type)) {
      User entity = (User) ret;
    }
    return ret;
  }
}
