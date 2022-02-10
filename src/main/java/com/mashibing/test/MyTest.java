package com.mashibing.test;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.apache.ibatis.io.Resources;
import com.mashibing.bean.Emp;
import com.mashibing.dao.EmpDao;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class MyTest {

    SqlSessionFactory sqlSessionFactory = null;

    @Before
    public void init(){
      // 根据全局配置文件创建出SqlSessionFactory
      // SqlSessionFactory:负责创建SqlSession对象的工厂
      // SqlSession:表示跟数据库建议的一次会话
      String resource = "mybatis-config.xml";
      InputStream inputStream = null;
      try {
        inputStream = Resources.getResourceAsStream(resource);
      } catch (IOException e) {
        e.printStackTrace();
      }
      sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
    }

    @Test
    public void test01() {

        // 获取数据库的会话,创建出数据库连接的会话对象（事务工厂，事务对象，执行器，如果有插件的话会进行插件的解析）
        SqlSession sqlSession = sqlSessionFactory.openSession();
        Emp empByEmpno = null;
        try {
            // 获取要调用的接口类,创建出对应的mapper的动态代理对象（mapperRegistry.knownMapper）
            EmpDao mapper = sqlSession.getMapper(EmpDao.class);
            // 调用方法开始执行
            empByEmpno = mapper.findEmpByEmpno(7369);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            sqlSession.close();
        }
        System.out.println(empByEmpno);
    }

  @Test
  public void test02() {
    // 根据全局配置文件创建出SqlSessionFactory
    // SqlSessionFactory:负责创建SqlSession对象的工厂
    // SqlSession:表示跟数据库建议的一次会话
    String resource = "mybatis-config.xml";
    InputStream inputStream = null;
    try {
      inputStream = Resources.getResourceAsStream(resource);
    } catch (IOException e) {
      e.printStackTrace();
    }
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
    // 获取数据库的会话,创建出数据库连接的会话对象（事务工厂，事务对象，执行器，如果有插件的话会进行插件的解析）
    SqlSession sqlSession = sqlSessionFactory.openSession();
    Emp empByEmpno = null;
    try {
      // 获取要调用的接口类,创建出对应的mapper的动态代理对象（mapperRegistry.knownMapper）
      EmpDao mapper = sqlSession.getMapper(EmpDao.class);
      // 调用方法开始执行
      empByEmpno = mapper.findEmpByEmpnoAndEname(7369,"SMITH");
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      sqlSession.close();
    }
    System.out.println(empByEmpno);
  }

  @Test
  public void test03(){
    SqlSession sqlSession = sqlSessionFactory.openSession();
    EmpDao mapper = sqlSession.getMapper(EmpDao.class);
    int zhangsan = mapper.insert(new Emp(1111, "zhangsan"));
    System.out.println(zhangsan);
    sqlSession.commit();
    sqlSession.close();
  }

  @Test
  public void test04(){
    SqlSession sqlSession = sqlSessionFactory.openSession();
    EmpDao mapper = sqlSession.getMapper(EmpDao.class);
    int zhangsan = mapper.update(new Emp(1111, "lisi"));
    System.out.println(zhangsan);
    sqlSession.commit();
    sqlSession.close();
  }


  @Test
  public void test05(){
    SqlSession sqlSession = sqlSessionFactory.openSession();
    EmpDao mapper = sqlSession.getMapper(EmpDao.class);
    int zhangsan = mapper.delete(1111);
    System.out.println(zhangsan);
    sqlSession.commit();
    sqlSession.close();
  }

//  @Test
//  public void testPageHelper(){
//    // 设置分页参数
//    PageHelper.startPage(1,2);
//    String resource = "mybatis-config.xml";
//    InputStream inputStream = null;
//    try {
//      inputStream = Resources.getResourceAsStream(resource);
//    } catch (IOException e) {
//      e.printStackTrace();
//    }
//    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
//    // 获取数据库的会话
//    SqlSession sqlSession = sqlSessionFactory.openSession();
//    EmpDao mapper = sqlSession.getMapper(EmpDao.class);
//    List<Emp> empList = mapper.selectAll();
//    for (Emp emp : empList) {
//      System.out.println(emp);
//    }
//
//    PageInfo<Emp> pageInfo = new PageInfo<>(empList);
//    System.out.println("总条数"+pageInfo.getTotal());
//    System.out.println("总页数"+pageInfo.getPages());
//    System.out.println("当前页"+pageInfo.getPageNum());
//    System.out.println("每页显示长度"+pageInfo.getPageSize());
//    System.out.println("是否第一页"+pageInfo.isIsFirstPage());
//    System.out.println("是否最后一页"+pageInfo.isIsLastPage());
//  }
}
