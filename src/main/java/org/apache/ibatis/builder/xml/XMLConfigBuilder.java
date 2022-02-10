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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * XML配置构建器，建造者模式，继承baseBuilder
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  // 标识是否已经解析过mybatis-config.xml配置文件
  private boolean parsed;
  // 用于解析mybatis-config.xml配置文件的XPathParser对象
  private final XPathParser parser;
  // 标识<environment>配置的名称
  private String environment;
  // ReflectorFactory负责创建和缓存Reflector对象
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  // 构造函数，转换成XPathParser再去调用构造函数
  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    // 构造一个需要验证，XMLMapperEntityResolver的XPathParser
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  // 上面的6个构造函数最后都会合流到这个函数，传入XPathParser
  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // 调用父类初始化configuration
    super(new Configuration());
    // 错误上下文设置成SQL Mapper Configuration(xml文件配置)
    ErrorContext.instance().resource("SQL Mapper Configuration");
    // 将Properties全部设置到configuration里面去
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  // 解析配置
  public Configuration parse() {
    // 根据parsed变量的值判断是否已经完成了对mybatis-config.xml配置文件的解析
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // 在mybatis-config.xml配置文件中查找<configuration>节点，并开始解析
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  // 解析配置
  private void parseConfiguration(XNode root) {
    try {
      // issue #117 read properties first
      // 解析properties
      propertiesElement(root.evalNode("properties"));
      // 解析settings
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      // 设置vfsImpl字段
      loadCustomVfs(settings);
      loadCustomLogImpl(settings);
      // 解析类型别名
      typeAliasesElement(root.evalNode("typeAliases"));
      // 解析插件
      pluginElement(root.evalNode("plugins"));
      // 对象工厂
      objectFactoryElement(root.evalNode("objectFactory"));
      // 对象包装工厂
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      // 反射工厂
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);//设置具体的属性到configuration对象
      // read it after objectFactory and objectWrapperFactory issue #631
      // 环境
      environmentsElement(root.evalNode("environments"));
      // databaseIdProvider
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      // 类型处理器
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 映射器
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    // 解析settings子节点的name和value属性，并返回properties对象
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    // 创建configuration对应的MetaClass对象
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    // 检测Configuration中是否定义了key指定属性的setter方法
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }

  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      // 处理全部子节点
      for (XNode child : parent.getChildren()) {
        // 处理package节点
        if ("package".equals(child.getName())) {
          // 获取指定的包名
          String typeAliasPackage = child.getStringAttribute("name");
          // 通过TypeAliasRegistry扫描指定包中所有的类，并解析@Alias注解，完成别名注册
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
          // 处理typeAlias节点
        } else {
          // 获取指定的别名
          String alias = child.getStringAttribute("alias");
          // 获取别名对应的类型
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            // 根据Class名字来注册类型别名
            // 调用TypeAliasRegistry.registerAlias
            if (alias == null) {
              // 扫描@Alias注解，完成注册
              typeAliasRegistry.registerAlias(clazz);
            } else {
              // 注册别名
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }
  // 插件
  // MyBatis 允许你在某一点拦截已映射语句执行的调用。默认情况下,MyBatis 允许使用插件来拦截方法调用
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      // 遍历全部子节点
      for (XNode child : parent.getChildren()) {
        // 获取plugins节点的interceptor属性
        String interceptor = child.getStringAttribute("interceptor");
        // 获取plugins节点下的properties配置的信息，并形成properties对象
        Properties properties = child.getChildrenAsProperties();
        // 实例化Interceptor对象
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
        // 设置Interceptor的属性
        interceptorInstance.setProperties(properties);
        // 记录interceptor对象
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  // 对象工厂,可以自定义对象创建的方式,
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取objectFactory节点的type属性
      String type = context.getStringAttribute("type");
      // 获取objectFactory节点下配置的信息，并形成Properties对象
      Properties properties = context.getChildrenAsProperties();
      // 进行别名解析后，实例化自定义objectFactory实现
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置自定义objectFactory的属性，完成初始化的相关操作
      factory.setProperties(properties);
      // 将自定义ObjectFactory对象记录到Configuration对象的objectFactory字段中
      configuration.setObjectFactory(factory);
    }
  }

  // 对象包装工厂
  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {

      // 解析properties的子节点的name和value属性，并记录到Properties中
      Properties defaults = context.getChildrenAsProperties();
      // 解析properties的resource和url属性，这两个属性用于确定properties配置文件的位置
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      // resource和url不能同时存在，否则抛出异常
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      // 与Configuration对象中的variables集合合并
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      // 更新XPathParser和Configuration的variables字段
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  //6.设置
  //这些是极其重要的调整, 它们会修改 MyBatis 在运行时的行为方式
  //<settings>
  //  <setting name="cacheEnabled" value="true"/>
  //  <setting name="lazyLoadingEnabled" value="true"/>
  //  <setting name="multipleResultSetsEnabled" value="true"/>
  //  <setting name="useColumnLabel" value="true"/>
  //  <setting name="useGeneratedKeys" value="false"/>
  //  <setting name="enhancementEnabled" value="false"/>
  //  <setting name="defaultExecutorType" value="SIMPLE"/>
  //  <setting name="defaultStatementTimeout" value="25000"/>
  //  <setting name="safeRowBoundsEnabled" value="false"/>
  //  <setting name="mapUnderscoreToCamelCase" value="false"/>
  //  <setting name="localCacheScope" value="SESSION"/>
  //  <setting name="jdbcTypeForNull" value="OTHER"/>
  //  <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
  //</settings>
  private void settingsElement(Properties props) {
    // 如何自动映射列到字段/属性
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    // 自动映射不知道的列
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    // 缓存
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    // proxyFactory (CGLIB | JAVASSIST)
    // 延迟加载的核心技术就是用代理模式，CGLIB/JAVASSIST两者选一
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    // 延迟加载
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    // 延迟加载时，每种属性是否还要按需加载
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    // 允不允许多种结果集从一个单独 的语句中返回
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    // 使用列标签代替列名
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    // 允许 JDBC 支持生成的键
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    // 配置默认的执行器
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    // 超时时间
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    // 默认获取的结果条数
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    // 默认结果集合的类型
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    // 是否将DB字段自动映射到驼峰式Java属性（A_COLUMN-->aColumn）
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    // 嵌套语句上使用RowBounds
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    // 默认用session级别的缓存
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    // 为null值设置jdbctype
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    // Object的哪些方法将触发延迟加载
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    // 使用安全的ResultHandler
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    // 动态SQL生成语言所使用的脚本语言
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    // 枚举类型处理器
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    // 当结果集中含有Null值时是否执行映射对象的setter或者Map对象的put方法。此设置对于原始类型如int,boolean等无效。
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    // 是否使用实际参数名称
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    // logger名字的前缀
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    // 配置工厂
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
    configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
  }

  //7.环境
  //	<environments default="development">
  //	  <environment id="development">
  //	    <transactionManager type="JDBC">
  //	      <property name="..." value="..."/>
  //	    </transactionManager>
  //	    <dataSource type="POOLED">
  //	      <property name="driver" value="${driver}"/>
  //	      <property name="url" value="${url}"/>
  //	      <property name="username" value="${username}"/>
  //	      <property name="password" value="${password}"/>
  //	    </dataSource>
  //	  </environment>
  //	</environments>
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      // 未指定XMLConfigBuilder.environment字段，则使用default属性
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      // 遍历子节点
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        // 与XmlConfigBuilder.environment字段匹配
        if (isSpecifiedEnvironment(id)) {
          // 创建TransactionFactory
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 创建DataSourceFactory和DataSource
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          // 创建Environment
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          // 将Environment对象记录到Configuration.environment字段中
          configuration.setEnvironment(environmentBuilder.build());
          break;
        }
      }
    }
  }


  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      // 与老版本兼容
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      // 解析相关配置信息
      Properties properties = context.getChildrenAsProperties();
      // 创建DatabaseIdProvider对象
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
      // 配置DatabaseIdProvider，完成初始化
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      // 通过dataSource获取databaseId并记录到configuration.databaseId字段中
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  //7.1事务管理器
  //<transactionManager type="JDBC">
  //  <property name="..." value="..."/>
  //</transactionManager>
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      // 根据type="JDBC"解析返回适当的TransactionFactory
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  //7.2数据源
  //<dataSource type="POOLED">
  //  <property name="driver" value="${driver}"/>
  //  <property name="url" value="${url}"/>
  //  <property name="username" value="${username}"/>
  //  <property name="password" value="${password}"/>
  //</dataSource>
  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      // 根据type="POOLED"解析返回适当的DataSourceFactory
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  //9.类型处理器
  //	<typeHandlers>
  //	  <typeHandler handler="org.mybatis.example.ExampleTypeHandler"/>
  //	</typeHandlers>
  //or
  //	<typeHandlers>
  //	  <package name="org.mybatis.example"/>
  //	</typeHandlers>
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 如果是package
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          // 调用TypeHandlerRegistry.register，去包下找所有类
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          // 如果是typeHandler
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          // 调用TypeHandlerRegistry.register(以下是3种不同的参数形式)
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  // 10.映射器
  //	10.1使用类路径
  //	<mappers>
  //	  <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
  //	  <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
  //	  <mapper resource="org/mybatis/builder/PostMapper.xml"/>
  //	</mappers>
  //
  //	10.2使用绝对url路径
  //	<mappers>
  //	  <mapper url="file:///var/mappers/AuthorMapper.xml"/>
  //	  <mapper url="file:///var/mappers/BlogMapper.xml"/>
  //	  <mapper url="file:///var/mappers/PostMapper.xml"/>
  //	</mappers>
  //
  //	10.3使用java类名
  //	<mappers>
  //	  <mapper class="org.mybatis.builder.AuthorMapper"/>
  //	  <mapper class="org.mybatis.builder.BlogMapper"/>
  //	  <mapper class="org.mybatis.builder.PostMapper"/>
  //	</mappers>
  //
  //	10.4自动扫描包下所有映射器
  //	<mappers>
  //	  <package name="org.mybatis.builder"/>
  //	</mappers>
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      // 处理mapper子节点
      for (XNode child : parent.getChildren()) {
        // package子节点
        if ("package".equals(child.getName())) {
          // 自动扫描包下所有映射器
          String mapperPackage = child.getStringAttribute("name");
          // 扫描指定的包，并向mapperRegistry注册mapper接口
          configuration.addMappers(mapperPackage);
        } else {
          // 获取mapper节点的resource、url、class属性，三个属性互斥
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          // 如果mapper节点指定了resource或者url属性，则创建XmlMapperBuilder对象，并通过该对象解析resource或者url属性指定的mapper配置文件
          if (resource != null && url == null && mapperClass == null) {
            // 使用类路径
            ErrorContext.instance().resource(resource);
            try(InputStream inputStream = Resources.getResourceAsStream(resource)) {
              // 创建XMLMapperBuilder对象，解析映射配置文件
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url != null && mapperClass == null) {
            // 使用绝对url路径
            ErrorContext.instance().resource(url);
            try(InputStream inputStream = Resources.getUrlAsStream(url)){
              // 创建XMLMapperBuilder对象，解析映射配置文件
              XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
              mapperParser.parse();
            }
          } else if (resource == null && url == null && mapperClass != null) {
            // 如果mapper节点指定了class属性，则向MapperRegistry注册该mapper接口
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            // 直接把这个映射加入配置
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  // 比较id和environment是否相等
  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    }
    if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    }
    return environment.equals(id);
  }

}
