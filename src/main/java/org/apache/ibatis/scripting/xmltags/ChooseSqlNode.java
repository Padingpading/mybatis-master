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

import java.util.List;

/**
 * choose SQL节点
 *
 * @author Clinton Begin
 */
public class ChooseSqlNode implements SqlNode {
  // otherwise节点对应的SqlNode
  private final SqlNode defaultSqlNode;
  // when节点对应的IfSqlNode集合
  private final List<SqlNode> ifSqlNodes;

  public ChooseSqlNode(List<SqlNode> ifSqlNodes, SqlNode defaultSqlNode) {
    this.ifSqlNodes = ifSqlNodes;
    this.defaultSqlNode = defaultSqlNode;
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 遍历ifSqlNode集合并调用其中SqlNode对象的apply方法
    for (SqlNode sqlNode : ifSqlNodes) {
      if (sqlNode.apply(context)) {
        return true;
      }
    }
    // 调用defaultSqlNode.apply方法
    if (defaultSqlNode != null) {
      defaultSqlNode.apply(context);
      return true;
    }
    // 如果连otherwise都没有，返回false
    return false;
  }
}
