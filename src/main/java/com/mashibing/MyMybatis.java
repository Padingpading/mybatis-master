package com.mashibing;


import org.apache.ibatis.annotations.Select;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class MyMybatis {

  public static void main(String[] args) {
    EmpMapper  empMapper = (EmpMapper) Proxy.newProxyInstance(MyMybatis.class.getClassLoader(), new Class[]{EmpMapper.class}, new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        // 通过参数来完成替换功能，因此需要先去解析参数
        Map<String, Object> stringObjectMap = parseArgs(method, args);
        // 获取方法上的注解
        Select select = method.getAnnotation(Select.class);
        if (select!=null){
          //核心逻辑处理
          String[] value = select.value();
          String sql = value[0];
          sql = parseSql(sql,stringObjectMap);
          System.out.println(sql);
        }
        // 数据库操作
        // jdbc解析
        // 结果集的映射处理
        // ResultSet一个值一个值封装到对象中去
        return null;
      }
    });
    empMapper.selectEmpList(7369,"SMITH");
  }

  public static String parseSql(String sql,Map<String,Object> map){

    StringBuilder builder = new StringBuilder();
    for(int i = 0;i<sql.length();i++){
      char c = sql.charAt(i);
      if (c == '#'){
        int index = i+1;
        char nextChar = sql.charAt(index);
        if (nextChar!='{'){
          throw new RuntimeException("sql语句有错误,不是以{开头的");
        }
        StringBuilder argBuilder = new StringBuilder();
        i = parseSqlArgs(argBuilder,sql,index);
        String argName = argBuilder.toString();
        Object value = map.get(argName);
        builder.append(value.toString());
        continue;
      }
      builder.append(c);
    }
    return builder.toString();
  }

  public static int parseSqlArgs(StringBuilder argBuilder,String sql,int index){
    index++;
    for (;index<sql.length();index++){
      char c = sql.charAt(index);
      if (c!='}'){
        argBuilder.append(c);
        continue;
      }
      if(c=='}'){
        return index;
      }
    }
    throw new RuntimeException("sql语句错误，没有以}结尾");
  }

  public static Map<String,Object> parseArgs(Method method,Object[] args){
    Map<String,Object> map = new HashMap<String,Object>();
    // 获取方法的参数
    Parameter[] parameters = method.getParameters();
    int index[] = {0};
    Arrays.asList(parameters).forEach(parameter -> {
      String name = parameter.getName();
      map.put(name,args[index[0]]);
      index[0]++;
    });
    return map;
  }
}
