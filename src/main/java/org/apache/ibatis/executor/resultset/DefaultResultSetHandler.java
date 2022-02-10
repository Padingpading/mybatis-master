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
package org.apache.ibatis.executor.resultset;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.util.MapUtil;

/**
 * 默认Map结果处理器
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERRED = new Object();

  // 关联的Executor、Configuration、MappedStatement、RowBounds对象
  private final Executor executor;
  private final Configuration configuration;
  private final MappedStatement mappedStatement;
  private final RowBounds rowBounds;
  private final ParameterHandler parameterHandler;
  // 用户指定用于处理结果集的ResultHandler对象
  private final ResultHandler<?> resultHandler;
  private final BoundSql boundSql;
  private final TypeHandlerRegistry typeHandlerRegistry;
  // 对象工厂
  private final ObjectFactory objectFactory;
  // 反射工厂
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  // Cached Automappings
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  private boolean useConstructorMappings;

  private static class PendingRelation {
    public MetaObject metaObject;
    public ResultMapping propertyMapping;
  }

  private static class UnMappedColumnAutoMapping {
    private final String column;
    private final String property;
    private final TypeHandler<?> typeHandler;
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    // 获取用户传入的实际参数，并为其创建相应的MetaObject对象
    final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    // 获取BoundSql.parameterMappings集合，其中记录了参数相关信息
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    // 遍历所有参数信息
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      // 只处理OUT|INOUT
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        // 如果存在输出类型的参数，则解析参数值，并设置到parameterObject中
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          // 如果指定该输出参数为ResultSet类型，则需要进行映射
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          // 使用TypeHandler获取参数值，并设置到parameterObject中
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  // 处理游标(OUT参数)
  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      // 获取映射使用的ResultMap对象
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      // 将结果集封装成ResultSetWrapper
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
      // 创建用于保存映射结果对象的DefaultResultHandler对象
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        // 通过handleRowValues方法完成映射操作，并将结果对象保存到DefaultResultHandler中
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        // 将映射得到的结果对象保存到parameterObject中
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

    // 该集合用于保存映射结果得到的结果对象
    final List<Object> multipleResults = new ArrayList<>();

    int resultSetCount = 0;
    // 获取第一个ResultSet对象
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 获取MappedStatement.resultMaps集合
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    int resultMapCount = resultMaps.size();
    // 如果集合集不为空，则resultMaps集合不能为空，否则抛出异常
    validateResultMapsCount(rsw, resultMapCount);
    // 遍历resultMaps集合
    while (rsw != null && resultMapCount > resultSetCount) {
      // 获取该结果集对应的ResultMap对象
      ResultMap resultMap = resultMaps.get(resultSetCount);
      // 根据ResultMap中定义的映射规则对ResultSet进行映射，并将映射的结果对象添加到multipleResult集合中保存
      handleResultSet(rsw, resultMap, multipleResults, null);
      // 获取下一个结果集
      rsw = getNextResultSet(stmt);
      // 清空nestedResultObjects集合
      cleanUpAfterHandlingResultSet();
      // 递增resultSetCount
      resultSetCount++;
    }

    // 获取MappedStatement.resultSets属性，该属性对多结果集的情况使用，该属性将列出语句执行后返回的结果集，并给每个结果集一个名称，名称是逗号分隔的，
    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
        // 根据resultSet的名称，获取未处理的ResultMapping
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          // 根据ResultMap对象映射结果集
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        // 获取下一个结果集
        rsw = getNextResultSet(stmt);
        // 清空nestedResultObjects集合
        cleanUpAfterHandlingResultSet();
        // 递增resultSetCount
        resultSetCount++;
      }
    }

    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    // 获取结果集并封装成ResultSetWrapper对象
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 获取映射使用的ResultMap对象集合
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    // 边界检测，只能映射一个结果集，所以只能存在一个ResultMap对象
    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    // 使用第一个ResultMap对象
    ResultMap resultMap = resultMaps.get(0);
    // 将ResultSetWrapper对象、映射使用的ResultMap对象一级控制映射的起止位置的RowBounds对象封装成DefaultCursor对象
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    // 获取ResultSet对象
    ResultSet rs = stmt.getResultSet();
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      // 检测是否还有待处理的ResultSet
      if (stmt.getMoreResults()) {
        rs = stmt.getResultSet();
      } else {
        // 没有待处理的ResultSet
        if (stmt.getUpdateCount() == -1) {
          // no more results. Must be no resultset
          break;
        }
      }
    }
    // 将结果集封装成ResultSetWrapper对象
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    try {
      // 检测JDBC是否支持多结果集
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
        // 检测是否还有待处理的结果集，若存在，则封装成ResultSetWrapper对象并返回
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          ResultSet rs = stmt.getResultSet();
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }

  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  // 处理结果集
  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      if (parentMapping != null) {
        // 处理多结果集中的嵌套映射
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else {
        if (resultHandler == null) {
          // 如果用户未指定处理映射结果对象的ResultHandler对象，则使用DefaultResultHandler作为默认的ResultHandler对象
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          // 对ResultSet进行映射，并将映射得到的结果对象添加到DefaultResultHandler对象中暂存
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          // 将DefaultResultHandler中保存的结果对象添加到multipleResults集合中
          multipleResults.add(defaultResultHandler.getResultList());
        } else {
          // 使用用户指定的ResultHandler对象处理结果对象
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      // issue #228 (close resultsets)
      // 调用ResultSet.close方法关闭结果集
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 针对存在嵌套ResultMap的情况
    if (resultMap.hasNestedResultMaps()) {
      // 检测是否允许在嵌套映射中使用RowBound
      ensureNoRowBounds();
      checkResultHandler();
      // 检测是否允许在嵌套映射中使用用户自定义的ResultHandler
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      // 针对不含嵌套映射的简单映射的处理
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  private void ensureNoRowBounds() {
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
          + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {
    // 默认上下文对象
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    ResultSet resultSet = rsw.getResultSet();
    // 根据RowBounds中的offset定位到指定的记录
    skipRows(resultSet, rowBounds);
    // 检测已经处理的行数是否已经达到上限（RowBounds,limit）以及ResultSet中是否还有可处理的记录
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      // 根据该行记录以及ResultMap.discriminator，决定映射使用的ResultMap
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // 根据最终确定的ResultMap对ResultSet中的该行记录进行映射，得到映射后的结果对象
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      // 将映射创建的结果对象添加到ResultHandler.resultList中保存
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
    }
  }

  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    if (parentMapping != null) {
      // 嵌套查询或者嵌套映射，将结果对保存到福对象对应的属性中
      linkToParents(rs, parentMapping, rowValue);
    } else {
      // 普通映射，将结果对象保存到ResultHandler中
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    // 递增DefaultResultContext.resultCount,该值用于检测处理的记录行数是否已经达到上下（在RowBounds。limit字段中记录了该上限）,之后将结果对象保存到
    // DefaultResultContext.resultObject字段中
    resultContext.nextResultObject(rowValue);
    // 将结果对象添加到ResultHandler.resultList中保存
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    // 检测DefaultResultContext.stopped字段和检测映射行数是否达到了RowBounds.limit的限制
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    // 根据ResultSet的类型进行定位
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        // 直接定位到offset指定的记录
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      // 通过多次调用ResultSet.next方法移动到指定的记录
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        if (!rs.next()) {
          break;
        }
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //

  // 核心，取得一行的值
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    // 实例化ResultLoaderMap(延迟加载器)
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    // 创建该行记录映射之后得到的结果对象，该结果对象的类型由ResultMap节点的type属性指定
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      // 创建上述结果对象相应的MetaObject对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      // 成功映射任意属性，则foundValues为true,否则foundValues为false
      boolean foundValues = this.useConstructorMappings;
      // 检测是否需要进行自动映射
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        // 自动映射ResultMap中未明确指定的列
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      // 映射ResultMap中明确指定需要映射的列
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      foundValues = lazyLoader.size() > 0 || foundValues;
      // 如果没有成功映射任何属性，则根据mybatis-config.xml中的returnInstanceForEmptyRow配置决定返回空的结果对象还是返回null
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    // 检测外层对象是否已经存在
    if (rowValue != null) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      // 将外层对象添加到ancestorObjects集合中
      putAncestor(rowValue, resultMapId);
      // 处理嵌套映射
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      // 将外层对象从ancestorObjects集合中移除
      ancestorObjects.remove(resultMapId);
    } else {
      // 延迟加载
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      // 创建外层对象
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        // 更新foundValues,
        boolean foundValues = this.useConstructorMappings;
        // 自动映射
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        // 映射ResultMap中明确指定的字段
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        // 将外层对象添加到ancestorObjects集合中
        putAncestor(rowValue, resultMapId);
        // 处理嵌套映射
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        // 将外层对象从ancestorObjects集合中移除
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        // 将外层对象保存到nestedResultObjects集合中
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    // 获取ResultMap中的autoMapping属性值
    if (resultMap.getAutoMapping() != null) {
      return resultMap.getAutoMapping();
    } else {
      // 检测是否为嵌套查询或是嵌套映射
      if (isNested) {
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 获取该ResultMap中明确需要进行映射的列名集合
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    boolean foundValues = false;
    // 获取ResultMap.propertyResultMappings集合，其中记录了映射使用的所有ResultMapping对象
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    for (ResultMapping propertyMapping : propertyMappings) {
      // 处理列前缀
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
        // 该属性需要使用一个嵌套ResultMap进行映射，忽略column属性
        column = null;
      }
      // 下面的逻辑主要处理三种场景
      // 场景1：column是{prop1=col1,prop2=col2}这种形式的，一般与嵌套查询配合使用，表示将col1和col2的列值传递给内层嵌套查询作为参数
      // 场景2：基本类型的属性映射
      // 场景3：多结果集的场景处理，改属性来自于另一个结果集
      if (propertyMapping.isCompositeResult() // 场景1
          || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) // 场景2
          || propertyMapping.getResultSet() != null) { // 场景3
        // 通过getPropertyMappingValue方法完成映射，并得到属性值
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        // issue #541 make property optional
        // 获取属性名称
        final String property = propertyMapping.getProperty();
        if (property == null) {
          continue;
        } else if (value == DEFERRED) {
          // DEFERRED表示的是占位符对象
          foundValues = true;
          continue;
        }
        if (value != null) {
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          // 设置属性值
          metaObject.setValue(property, value);
        }
      }
    }
    return foundValues;
  }

  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 嵌套查询
    if (propertyMapping.getNestedQueryId() != null) {
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
      // 多结果集的处理
    } else if (propertyMapping.getResultSet() != null) {
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      // 返回占位符对象
      return DEFERRED;
    } else {
      // 获取ResultMapping中记录的TypeHandler对象
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      // 使用TypeHandler对象获取属性值
      return typeHandler.getResult(rs, column);
    }
  }

  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // 自动映射的缓存key
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    // autoMappingsCache缓存未命中
    if (autoMapping == null) {
      autoMapping = new ArrayList<>();
      // 从ResultSetWrapper中获取未映射的列名集合
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      for (String columnName : unmappedColumnNames) {
        // 生成属性名称
        String propertyName = columnName;
        // 如果列名以列前缀开头，则属性名称为列名去除前缀删除的部分
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            continue;
          }
        }
        // 在结果对象中查找指定的属性名
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        // 检测是否存在该属性的setter方法
        if (property != null && metaObject.hasSetter(property)) {
          if (resultMap.getMappedProperties().contains(property)) {
            continue;
          }
          final Class<?> propertyType = metaObject.getSetterType(property);
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            // 查找对应的typeHandler对象
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            // 创建UnMappedColumnAutoMapping对象，并添加到autoMapping集合中
            autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            configuration.getAutoMappingUnknownColumnBehavior()
                .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      // 将autoMapping添加到缓存中保存
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  // 自动映射
  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // 获取ResultSet中存在，但ResultMap中没有明确映射的列所对应的UnMappedColumnAutoMapping集合，如果ResultMap中设置的resultType为HashMap的话，则全部的列都会在这里获取到
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    boolean foundValues = false;
    if (!autoMapping.isEmpty()) {
      // 遍历autoMapping集合
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        // 使用TypeHandler获取自动映射的列值
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        if (value != null) {
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          // 将自动映射的属性值设置到结果对象中
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    // 创建CacheKey对象
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    // 获取pendingRelations集合中parentKey对应的PendingRelation对象
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      // 遍历PendingRelations集合
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          // 将当前记录的结果对象添加到外层对象的相应属性中
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    // 为指定结果集创建CacheKey对象
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    // 创建PendingRelation对象
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    // 将PendingRelation对象添加到PendingRelations集合缓存
    List<PendingRelation> relations = MapUtil.computeIfAbsent(pendingRelations, cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);
    // 在nextResultMaps集合记录指定属性对应的结果集名称以及对应的ResultMapping对象
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      // 如果同名的结果集对应不同的resultMapping，则抛出异常
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    CacheKey cacheKey = new CacheKey();
    // 添加ResultMapping
    cacheKey.update(resultMapping);
    if (columns != null && names != null) {
      // 按照都好切分列名
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
        // 查询该行记录对应列的值
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          // 添加列名和列值
          cacheKey.update(namesArray[i]);
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  // 创建结果对象
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    // 标识是否使用构造函数创建该结果对象
    this.useConstructorMappings = false; // reset previous mapping result
    // 记录构造函数的参数类型
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    // 记录构造函数的实参
    final List<Object> constructorArgs = new ArrayList<>();
    // 创建该行记录对应的结果对象，该方法是该步骤的核心
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    // 如果包含嵌套查询，且配置了延迟加载，则创建代理对象
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    // 记录是否使用构造器创建对象
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    return resultObject;
  }

  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
      throws SQLException {
    // 获取ResultMap中记录的type属性，也就是该行记录最终映射成的结果对象类型
    final Class<?> resultType = resultMap.getType();
    // 创建该类型对应的MetaClass对象
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    // 获取ResultMap中记录的constructor节点信息，如果该集合不为空，则可以通过该集合确定相应Java类中的唯一构造函数
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    // 结果集只有一列，且存在TypeHandler对象可以将该列转换成ResultType类型的值
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      // 先查找相应的TypeHandler对象，再使用TypeHandler对象将该记录转换成Java类型的值
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
    } else if (!constructorMappings.isEmpty()) {
      // ResultMap中记录了constructor节点的信息，则通过反射方法调用构造方法，创建结果对象
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      // 使用默认的无参构造函数，则直接使用ObjectFactory创建对象
      return objectFactory.create(resultType);
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      // 通过自动映射的方式查找合适的构造方法并创建结果对象
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
    }
    // 初始化失败，抛出异常
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    boolean foundValues = false;
    // 遍历constructorMappings集合，该过程中会使用constructorArgTypes集合记录的构造参数类型，使用constructArgs集合记录构造函数实参
    for (ResultMapping constructorMapping : constructorMappings) {
      // 获取当前构造参数的类型
      final Class<?> parameterType = constructorMapping.getJavaType();
      final String column = constructorMapping.getColumn();
      final Object value;
      try {
        if (constructorMapping.getNestedQueryId() != null) {
          // 存在嵌套查询，需要处理该查询，然后才能得到实参，
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
        } else if (constructorMapping.getNestedResultMapId() != null) {
          // 存在嵌套映射，需要先处理嵌套映射，才能到得实现
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
        } else {
          // 直接获取该列的值，然后通过TypeHandler对象的转换，得到构造函数的实参
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      // 记录当前构造参数的类型
      constructorArgTypes.add(parameterType);
      // 记录当前构造参数的实际值
      constructorArgs.add(value);
      foundValues = value != null || foundValues;
    }
    // 通过ObjectFactory调用匹配的构造函数，创建结果对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
    final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
    if (defaultConstructor != null) {
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
    } else {
      // 遍历全部的构造方法
      for (Constructor<?> constructor : constructors) {
        if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
        }
      }
    }
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
    boolean foundValues = false;
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      // 获取构造函数的参数类型
      Class<?> parameterType = constructor.getParameterTypes()[i];
      // ResultSet中的列名
      String columnName = rsw.getColumnNames().get(i);
      // 查找对应的TypeHandler，并获取该列的值
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
      // 记录构造函数的参数类型和参数值
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      // 更新foundValues值
      foundValues = value != null || foundValues;
    }
    // 使用ObjectFactory调用对应的构造方法，创建结果对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
    if (constructors.length == 1) {
      return constructors[0];
    }

    for (final Constructor<?> constructor : constructors) {
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    return null;
  }

  private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    // 获取构造函数的参数类型
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (parameterTypes.length != jdbcTypes.size()) {
      return false;
    }
    for (int i = 0; i < parameterTypes.length; i++) {
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    final Class<?> resultType = resultMap.getType();
    final String columnName;
    if (!resultMap.getResultMappings().isEmpty()) {
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      final ResultMapping mapping = resultMappingList.get(0);
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      columnName = rsw.getColumnNames().get(0);
    }
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    // 获取嵌套查询的id以及对应的MappedStatement对象
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    // 获取传递给嵌套查询的参数值
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      // 获取嵌套查询对应的BoundSql对象和相应的CacheKey对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      // 获取嵌套查询结果集经过映射后的目标类型
      final Class<?> targetType = constructorMapping.getJavaType();
      // 创建ResultLoader对象，并调用loadResult方法执行嵌套查询，得到相应的构造方法参数值
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      value = resultLoader.loadResult();
    }
    return value;
  }

  // 得到嵌套查询值
  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    // 获取嵌套查询的id和对应的MappedStatement对象
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    final String property = propertyMapping.getProperty();
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    // 获取传递给嵌套查询的参数类型和参数值
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      // 获取嵌套查询对应的BoundSql对象和相应的CacheKey对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      // 获取嵌套查询结果集经过映射后的目标类型
      final Class<?> targetType = propertyMapping.getJavaType();
      // 检测缓存中是否存在该嵌套查询的结果对象
      if (executor.isCached(nestedQuery, key)) {
        // 创建DeferredLoad对象，并通过该DeferredLoad对象从缓存中加载结果对象
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        // 返回DEFERRED标识
        value = DEFERRED;
      } else {
        // 创建嵌套查询相应的ResultLoader对象
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
        if (propertyMapping.isLazy()) {
          // 如果该属性配置了延迟加载，则将其添加到ResultLoadMap中，等待真正使用时再执行嵌套查询并得到结果对象
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          value = DEFERRED;
        } else {
          // 没有配置延迟加载，则直接调用ResultLoader.loadResult方法执行嵌套查询，并映射得到结果对象
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final Object parameterObject = instantiateParameterObject(parameterType);
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        foundValues = true;
      }
    }
    return foundValues ? parameterObject : null;
  }

  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<>();
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    // 记录已经处理过的ResultMap的id
    Set<String> pastDiscriminators = new HashSet<>();
    // 获取ResultMap中的Discriminator对象
    Discriminator discriminator = resultMap.getDiscriminator();
    while (discriminator != null) {
      // 获取记录中对应列的值，其中会使用相应的TypeHandler对象将该列值转换成Java类型
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      // 根据该列值获取对应的ResultMap的id
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      if (configuration.hasResultMap(discriminatedMapId)) {
        // 根据上述获取的id查找相应的ResultMap对象
        resultMap = configuration.getResultMap(discriminatedMapId);
        // 记录当前Discriminator对象
        Discriminator lastDiscriminator = discriminator;
        // 获取ResultMap对象中的Discrimination
        discriminator = resultMap.getDiscriminator();
        // 检测Discriminator是否出现了环形引用
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          break;
        }
      } else {
        break;
      }
    }
    // 该ResultMap对象为映射最终使用的ResultMap
    return resultMap;
  }

  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    final ResultMapping resultMapping = discriminator.getResultMapping();
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 创建DefaultResultContext对象
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    ResultSet resultSet = rsw.getResultSet();
    // 定位到指定的记录行
    skipRows(resultSet, rowBounds);
    Object rowValue = previousRowValue;
    // 检测是否能继续映射结果集中剩余的记录行
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      // 通过resolveDiscriminatedResultMap方法决定映射使用的ResultMap对象
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      // 为该行记录生成CacheKey
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      // 根据CacheKey查找nestedResultObjects集合
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      // 检测resultOrdered属性
      if (mappedStatement.isResultOrdered()) {
        // 主结果对象发生变化
        if (partialObject == null && rowValue != null) {
          // 清空nestedResultObjects集合
          nestedResultObjects.clear();
          // 调用storeObject方法保存主结果对象
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        // 完成该行记录的映射返回结果对象，将结果对象添加到nestedResultObjects集合中
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        // 完成该行记录的映射返回结果对象，将结果对象添加到nestedResultObjects集合中
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        // 调用storeObject方法保存结果对象
        if (partialObject == null) {
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    // 对resultOrdered属性为true时的特殊处理，调用storeObject方法保存结果对象
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    // 遍历全部ResultMapping对象，处理其中的嵌套映射
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      // 获取resultMapping.nestedResultMapId
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      // 检测nestedResultMapId和resultSet两个字段的值
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          // 获取列前缀
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          // 确定嵌套映射使用的ResultMap对象
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          // 处理循环引用的情况
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              // 若是循环引用，则不用执行下面的路径创建新对象，而是重用之前的对象
              continue;
            }
          }
          // 为嵌套对象创建CacheKey
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          // 查找nestedResultObjects集合中是否有相同的Key的嵌套对象
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          // 初始化外层对象中国Collection类型的属性
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          // 根据notNullColumn属性检测结果集中的空值
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            // 完成嵌套映射，并生成嵌套对象
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            if (rowValue != null && !knownValue) {
              // 将嵌套对象保存到外层对象的相应属性中
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    } else if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    // 创建CacheKey对象
    final CacheKey cacheKey = new CacheKey();
    // 将ResultMap的id作为CacheKey的一部分
    cacheKey.update(resultMap.getId());
    // 查找ResultMapping对象集合
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    // 没有找到任何ResultMapping
    if (resultMappings.isEmpty()) {
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        // 由结果集中的所有列名以及当前记录行的所有值一起构成CacheKey对象
        createRowKeyForMap(rsw, cacheKey);
      } else {
        // 由结果集中未映射的列名以及它们在当前记录行中的对应列值一起构成CacheKey对象
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      // 由resultMappings集合中的列名以及它们在当前记录行中相应的列值一起构成CacheKey
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    // 如果通过上面的查找没有找人任何列参与构成CacheKey，则返回NullCacheKey对象
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    // 边界检查
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      // 与外层对象的CacheKey合并，形成嵌套对象最终的CacheKey
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    // idResultMappings集合中记录idArgs和id节点对应的ResultMapping对象
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      // propertyResultMappings集合记录了id*节点之外的ResultMapping对象
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    // 遍历所有resultMappings集合
    for (ResultMapping resultMapping : resultMappings) {
      if (resultMapping.isSimple()) {
        // 获取该列的名称
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        // 获取该列相应的TypeHandler对象
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        // 获取映射的列名
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          // 获取列值
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            // 将列名和列值添加到CacheKey对象中
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    List<String> columnNames = rsw.getColumnNames();
    for (String columnName : columnNames) {
      final String value = rsw.getResultSet().getString(columnName);
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    // 检测外层对象的指定属性是否为Collection类型，如果是且未初始化，则初始化该集合属性并返回
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    // 根据属性是否为集合类型，调用MetaObject的相应方法，将嵌套对象记录到外层对象的相应属性中
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    // 获取指定的属性名称和当前属性值
    final String propertyName = resultMapping.getProperty();
    Object propertyValue = metaObject.getValue(propertyName);
    // 检测该属性是否已初始化
    if (propertyValue == null) {
      // 获取属性的Java类型
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        // 指定类型为集合类型
        if (objectFactory.isCollection(type)) {
          // 通过ObjectFactory创建该类型的集合对象，并进行相应设置
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      // 指定属性是集合类型且已经初始化，则返回该属性值
      return propertyValue;
    }
    return null;
  }

  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    if (rsw.getColumnNames().size() == 1) {
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
