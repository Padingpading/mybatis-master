package com.mashibing.resulthandler;

import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;

import java.util.Map;

/**
 * @author libin
 * @description
 * @date 2022/6/7
 */
public class EmpResultHandler implements ResultHandler {
  @Override
  public void handleResult(ResultContext context) {
    Map<String,Object> resultObject = (Map<String, Object>)context.getResultObject();
  }
}
