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
package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

public class ParamNameResolver {

  public static final String GENERIC_NAME_PREFIX = "param";

  private final boolean useActualParamName;

  /**方法参数的映射
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   */
  private final SortedMap<Integer, String> names;

  private boolean hasParamAnnotation;
  //   是一个工具类，主要用来解析接口方法的参数的，也就是方法参数中参数的位置，名字之间的索引关系，
  public ParamNameResolver(Configuration config, Method method) {
    this.useActualParamName = config.isUseActualParamName();
    // 获取参数列表中每个参数的类型
    final Class<?>[] paramTypes = method.getParameterTypes();
    // 获取参数列表上的注解
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    // 该集合用于记录参数索引与参数名称的对应关系
    final SortedMap<Integer, String> map = new TreeMap<>();
    int paramCount = paramAnnotations.length;
    // get names from @Param annotations
    // 遍历方法所有参数
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      if (isSpecialParameter(paramTypes[paramIndex])) {
        // skip special parameters
        // 如果参数是RowBounds类型或ResultHandler类型，则跳过对该参数的分析
        continue;
      }
      String name = null;
      // 遍历该参数对应的注解集合
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        if (annotation instanceof Param) {
          // @Param注解出现过一次，就将hasParamAnnotation初始化为true
          hasParamAnnotation = true;
          // 获取@Param注解指定的参数名称
          name = ((Param) annotation).value();
          break;
        }
      }
      if (name == null) {
        // @Param was not specified.
        // 该参数没有对应的@Param注解，则根据配置决定是否使用参数实际名称作为其名称
        if (useActualParamName) {
          name = getActualParamName(method, paramIndex);
        }
        if (name == null) {
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71
          // 使用参数的索引作为其名称
          name = String.valueOf(map.size());
        }
      }
      // 记录到map中保存
      map.put(paramIndex, name);
    }
    // 初始化name集合
    names = Collections.unmodifiableSortedMap(map);
  }

  private String getActualParamName(Method method, int paramIndex) {
    return ParamNameUtil.getParamNames(method).get(paramIndex);
  }

  // 过滤RowBounds和ResultHandler两种类型的参数
  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   *
   * @return the names
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.
   * Multiple parameters are named using the naming rule.
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   *
   * @param args
   *          the args
   * @return the named params
   */
  public Object getNamedParams(Object[] args) {
    final int paramCount = names.size();
    // 无参数，返回null
    if (args == null || paramCount == 0) {
      // 如果没参数
      return null;
      // 未使用@Param且只有一个参数
    } else if (!hasParamAnnotation && paramCount == 1) {
      // 如果只有一个参数
      Object value = args[names.firstKey()];
      //参数标记  单个参数可能是 Map、List、Array、Object
      return wrapToMapIfCollection(value, useActualParamName ? names.get(0) : null);
      // 处理使用@Param注解指定了参数名称或者多个参数的情况
    } else {
      // param这个map记录了参数名称与实参之间的对应关系，ParamMap继承了HashMap，如果向paramMap中添加已经存在的key，会报错，
      //多参数数据绑定
      final Map<String, Object> param = new ParamMap<>();
      //1、param1、param2..... value
      //2、解析出的请求参数字段  字段simplename 或者@param注解。
      int i = 0;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
        // 将参数名称与实参对应关系记录到param中
        param.put(entry.getValue(), args[entry.getKey()]);
        // add generic param names (param1, param2, ...)
        // 为参数创建param+索引格式的默认参数名称，并添加到param集合中
        final String genericParamName = GENERIC_NAME_PREFIX + (i + 1);
        // ensure not to overwrite parameter named with @Param
        // 如果@param注解指定的参数名称就是param+索引格式的，则不需要添加
        if (!names.containsValue(genericParamName)) {
          // 再加一个#{param1},#{param2}...参数
          //你可以传递多个参数给一个映射器方法。如果你这样做了,
          //默认情况下它们将会以它们在参数列表中的位置来命名,比如:#{param1},#{param2}等。
          //如果你想改变参数的名称(只在多参数情况下) ,那么你可以在参数上使用@Param(“paramName”)注解。
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      return param;
    }
  }

  /**封装参数名和参数值,参数标记
   *
   * Wrap to a {@link ParamMap} if object is {@link Collection} or array.
   *
   * @param object a parameter object
   * @param actualParamName an actual parameter name
   *                        (If specify a name, set an object to {@link ParamMap} with specified name)
   * @return a {@link ParamMap}
   * @since 3.5.5
   */
  public static Object wrapToMapIfCollection(Object object, String actualParamName) {
    //集合
    if (object instanceof Collection) {
      //参数若是Collection型，做collection标记
      ParamMap<Object> map = new ParamMap<>();
      map.put("collection", object);
      if (object instanceof List) {
        //参数若是List型，做list标记
        map.put("list", object);
      }
      Optional.ofNullable(actualParamName).ifPresent(name -> map.put(name, object));
      return map;
    } else if (object != null && object.getClass().isArray()) {
      //参数若是数组型，，做array标记
      ParamMap<Object> map = new ParamMap<>();
      map.put("array", object);
      Optional.ofNullable(actualParamName).ifPresent(name -> map.put(name, object));
      return map;
    }
    //参数若不是集合型，直接返回原来值
    return object;
  }

}
