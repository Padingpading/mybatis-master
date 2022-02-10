package com.mashibing.reflector;

import org.apache.logging.log4j.core.util.JsonUtils;

public class Student {


//  public Student(Integer id,String name){
//    System.out.println(id+"--"+name);
//  }

  public Integer getId(){
    System.out.println("运行get方法");
    return 6;
  }

  public void setId(Integer id){
    System.out.println("运行set方法");
    System.out.println(id);
  }

  public String getName(){
    return "zhangsan";
  }
}
