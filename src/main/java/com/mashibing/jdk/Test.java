package com.mashibing.jdk;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class Test {
    public static void main(String[] args) {
        System.getProperties().put("sun.misc.ProxyGenerator.saveGeneratedFiles", "true");
        ClassLoader loader = Calculator.class.getClassLoader();
        InvocationHandler h = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                System.out.println(method);
                return 2;
            }
        };
        Calculator proxy = (Calculator)Proxy.newProxyInstance(loader, new Class[]{Calculator.class}, h);
        int add = proxy.add(1, 1);
        System.out.println(add);
    }
}
