package com.mashibing.a_parse;

import org.apache.ibatis.parsing.PropertyParser;

import java.util.Properties;


/**
 * 获取属性-属性值。
 */
public class VariableTokenHandler_test {
  public static void main(String[] args) {
    Properties properties = new Properties();
    properties.setProperty("username","Padingpading");
    PropertyParser.VariableTokenHandler handler = new PropertyParser.VariableTokenHandler(properties);
    String username = handler.handleToken("username");
    System.out.println(username);
  }
}
