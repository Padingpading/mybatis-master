package com.mashibing.b_reflection;

import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.Reflector;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.junit.Test;


/**
 * @author libin
 * @description
 * @date 2022/2/10
 */
public class ReflectorTest {
  public static void main(String[] args) {
  }

  @Test
  public void testReflector() {
    Reflector reflector = new Reflector(User.class);
  }

  /**
   * 查询属性
   */
  @Test
  public void testFindProperty() {
    ReflectorFactory reflectorFactory = new DefaultReflectorFactory();
    MetaClass meta = MetaClass.forClass(User.class, reflectorFactory);
    //查询属性名
    String name = meta.findProperty("name");

    //查询属性名
    boolean name1 = meta.hasGetter("name");
    System.out.println(name);
  }
}
