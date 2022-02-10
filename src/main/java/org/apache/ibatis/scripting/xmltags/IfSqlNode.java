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
package org.apache.ibatis.scripting.xmltags;

/**
 * if SQL节点
 *
 * @author Clinton Begin
 */
public class IfSqlNode implements SqlNode {

  // ExpressionEvaluator对象用于解析if节点的test表达式的值
  private final ExpressionEvaluator evaluator;
  // 记录了if节点中的test表达式
  private final String test;
  // 记录了if节点的子节点
  private final SqlNode contents;

  public IfSqlNode(SqlNode contents, String test) {
    this.test = test;
    this.contents = contents;
    this.evaluator = new ExpressionEvaluator();
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 检测test属性中记录的表达式
    if (evaluator.evaluateBoolean(test, context.getBindings())) {
      // test表达式为true，则执行子节点的apply方法
      contents.apply(context);
      return true;
    }
    // test表达式是否为true
    return false;
  }

}
