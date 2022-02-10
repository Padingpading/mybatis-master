package com.mashibing.reflector;

public class Person {

  private Integer id;
  private String name;

  public Person(Integer id){
    this.id = id;
  }

  public Person(Integer id, String name) {
    this.id = id;
    this.name = name;
  }
}
