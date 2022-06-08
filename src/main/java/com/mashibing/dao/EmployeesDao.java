package com.mashibing.dao;


import com.mashibing.bean.Emp;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface EmployeesDao {

    public Emp findEmpByEmpno(Integer empno);

    public List<Emp> selectAll();

    public Emp findEmpByEmpnoAndEname(@Param("empno") Integer empno, @Param("ename") String ename);

    public int insert(Emp emp);

    public int update(Emp emp);

    public int delete(Integer empno);
}
