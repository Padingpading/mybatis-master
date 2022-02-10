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

import java.util.Map;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

/**
 * foreach SQL节点
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  public static final String ITEM_PREFIX = "__frch_";

  // 用于判断循环的终止条件
  private final ExpressionEvaluator evaluator;
  // 迭代的集合表达式
  private final String collectionExpression;
  // 记录了该ForeachSqlNode节点的子节点
  private final SqlNode contents;
  // 在循环开始前要添加的字符串
  private final String open;
  // 在循环结束后要添加的字符串
  private final String close;
  // 循环过程中，每项之间的分隔符
  private final String separator;
  // index是当前迭代的次数，item的值是本次迭代的元素
  private final String item;
  private final String index;
  // 配置对象
  private final Configuration configuration;

  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
    this.evaluator = new ExpressionEvaluator();
    this.collectionExpression = collectionExpression;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    // 获取参数信息
    Map<String, Object> bindings = context.getBindings();
    // 解析集合表达式对应的实际参数
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
    // 检测集合长度
    if (!iterable.iterator().hasNext()) {
      return true;
    }
    boolean first = true;
    // 在循环开始之前，调用appendSql方法添加open指定的字符串
    applyOpen(context);
    int i = 0;
    for (Object o : iterable) {
      // 记录当前DynamicContext对象
      DynamicContext oldContext = context;
      // 创建PrefixedContext，并让context指向该PrefixedContext对象
      if (first || separator == null) {
        // 如果是集合的第一项，则将PrefixedContext.prefix初始化为空字符串
        context = new PrefixedContext(context, "");
      } else {
        // 如果指定了分隔符，则PrefixedContext.prefix初始化为指定分隔符
        context = new PrefixedContext(context, separator);
      }
      // uniqueNumber从0开始，每次递增1，用于转换生成新的#{}占位符名称
      int uniqueNumber = context.getUniqueNumber();
      // Issue #709
      if (o instanceof Map.Entry) {
        @SuppressWarnings("unchecked")
        // 如果集合是Map类型，将集合中的key和value添加到DynamicContext.bindings集合中保存
        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        applyIndex(context, mapEntry.getKey(), uniqueNumber);
        applyItem(context, mapEntry.getValue(), uniqueNumber);
      } else {
        // 将集合中的索引和元素添加到DynamicContext.bindings集合中保存
        applyIndex(context, i, uniqueNumber);
        applyItem(context, o, uniqueNumber);
      }
      // 调用子节点的apply方法进行处理
      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
      if (first) {
        first = !((PrefixedContext) context).isPrefixApplied();
      }
      // 还原成原来的context
      context = oldContext;
      i++;
    }
    // 循环结束后，调用appendSql方法添加close指定的字符串
    applyClose(context);
    context.getBindings().remove(item);
    context.getBindings().remove(index);
    return true;
  }

  private void applyIndex(DynamicContext context, Object o, int i) {
    if (index != null) {
      // key为index，value为集合元素
      context.bind(index, o);
      // 为index添加前缀和后缀形成新的key
      context.bind(itemizeItem(index, i), o);
    }
  }

  private void applyItem(DynamicContext context, Object o, int i) {
    if (item != null) {
      // key为item，value为集合元素
      context.bind(item, o);
      // 为item添加前缀和后缀形成新的key
      context.bind(itemizeItem(item, i), o);
    }
  }

  private void applyOpen(DynamicContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  private void applyClose(DynamicContext context) {
    if (close != null) {
      context.appendSql(close);
    }
  }

  private static String itemizeItem(String item, int i) {
    // 添加前缀和i后缀
    return ITEM_PREFIX + item + "_" + i;
  }

  //被过滤的动态上下文
  private static class FilteredDynamicContext extends DynamicContext {
    // DynamicContext对象
    private final DynamicContext delegate;
    // 对应集合项在集合中的索引位置
    private final int index;
    // 对应集合项的index
    private final String itemIndex;
    // 对应集合项的item
    private final String item;

    public FilteredDynamicContext(Configuration configuration,DynamicContext delegate, String itemIndex, String item, int i) {
      super(configuration, null);
      this.delegate = delegate;
      this.index = i;
      this.itemIndex = itemIndex;
      this.item = item;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public void appendSql(String sql) {
      // 创建GenericTokenParser解析器
      GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
        // 对item进行处理
        String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
        if (itemIndex != null && newContent.equals(content)) {
          // 对itemIndex进行处理
          newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
        }
        return "#{" + newContent + "}";
      });

      // 将解析后的SQL语句片段追加到delegate中保存
      delegate.appendSql(parser.parse(sql));
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

  }


  //前缀上下文
  private class PrefixedContext extends DynamicContext {
    // 底层封装的DynamicContext对象
    private final DynamicContext delegate;
    // 指定的前缀
    private final String prefix;
    // 是否已经处理过前缀
    private boolean prefixApplied;

    public PrefixedContext(DynamicContext delegate, String prefix) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public void appendSql(String sql) {
      // 判断是否需要追加前缀
      if (!prefixApplied && sql != null && sql.trim().length() > 0) {
        // 追加前缀
        delegate.appendSql(prefix);
        // 表示已经处理过前缀
        prefixApplied = true;
      }
      // 追加sql片段
      delegate.appendSql(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }
  }

}
