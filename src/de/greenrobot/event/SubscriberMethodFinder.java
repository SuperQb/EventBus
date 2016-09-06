/*
 * Copyright (C) 2012 Markus Junginger, greenrobot (http://greenrobot.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.greenrobot.event;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class SubscriberMethodFinder {
    private static final String ON_EVENT_METHOD_NAME = "onEvent";

    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     * 在较新的类文件，编译器可能会添加方法。那些被称为BRIDGE或SYNTHETIC方法。
     * EventBus必须忽略两者。有修饰符没有公开，但在Java类文件中有格式定义
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    //需要忽略的修饰符
    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    //key：类名，value：该类中需要要响应的方法集合
    private static final Map<Class<?>, List<SubscriberMethod>> methodCache = new HashMap<Class<?>, List<SubscriberMethod>>();
    //跳过校验方法的类（即通过构造函数传入的集合）
    private final Map<Class<?>, Class<?>> skipMethodVerificationForClasses;

    SubscriberMethodFinder(List<Class<?>> skipMethodVerificationForClassesList) {
        skipMethodVerificationForClasses = new ConcurrentHashMap<Class<?>, Class<?>>();
        if (skipMethodVerificationForClassesList != null) {
            for (Class<?> clazz : skipMethodVerificationForClassesList) {
                skipMethodVerificationForClasses.put(clazz, clazz);
            }
        }
    }

    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        List<SubscriberMethod> subscriberMethods;
        synchronized (methodCache) {
            subscriberMethods = methodCache.get(subscriberClass);
        }
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        subscriberMethods = new ArrayList<SubscriberMethod>();
        Class<?> clazz = subscriberClass;

        HashMap<String, Class> eventTypesFound = new HashMap<String, Class>();
        StringBuilder methodKeyBuilder = new StringBuilder();
        while (clazz != null) {
            String name = clazz.getName();
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                // Skip system classes, this just degrades performance
                break;
            }

            // Starting with EventBus 2.2 we enforced methods to be public (might change with annotations again)
            try {
                // This is faster than getMethods, especially when subscribers a fat classes like Activities
                //通过反射得到clazz的全部方法
                Method[] methods = clazz.getDeclaredMethods();

                filterSubscriberMethods(subscriberMethods, eventTypesFound, methodKeyBuilder, methods);
            } catch (Throwable th) {
                // Workaround for java.lang.NoClassDefFoundError, see https://github.com/greenrobot/EventBus/issues/149
                Method[] methods = subscriberClass.getMethods();
                subscriberMethods.clear();
                eventTypesFound.clear();
                filterSubscriberMethods(subscriberMethods, eventTypesFound, methodKeyBuilder, methods);
                break;
            }
            clazz = clazz.getSuperclass();
        }
        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass + " has no public methods called "
                    + ON_EVENT_METHOD_NAME);
        } else {
            synchronized (methodCache) {
                methodCache.put(subscriberClass, subscriberMethods);
            }
            return subscriberMethods;
        }
    }

    private void filterSubscriberMethods(List<SubscriberMethod> subscriberMethods,
                                         HashMap<String, Class> eventTypesFound, StringBuilder methodKeyBuilder,
                                         Method[] methods) {
        for (Method method : methods) {
            String methodName = method.getName();
            if (methodName.startsWith(ON_EVENT_METHOD_NAME)) {
                int modifiers = method.getModifiers();//方法的修饰符
                Class<?> methodClass = method.getDeclaringClass();
                //如果是public，不是之前需要忽略的类型
                if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                    Class<?>[] parameterTypes = method.getParameterTypes();//得到方法的参数
                    if (parameterTypes.length == 1) {//如果有一个参数
                        ThreadMode threadMode = getThreadMode(methodClass, method, methodName);
                        if (threadMode == null) {
                            continue;
                        }
                        Class<?> eventType = parameterTypes[0];//响应方法的参数，也就是响应的标志
                        methodKeyBuilder.setLength(0);
                        methodKeyBuilder.append(methodName);
                        methodKeyBuilder.append('>').append(eventType.getName());
                        //方法名>方法的参数 作为key
                        String methodKey = methodKeyBuilder.toString();
                        //方法+参数作为key，方法所在的类
                        Class methodClassOld = eventTypesFound.put(methodKey, methodClass);
                        if (methodClassOld == null || methodClassOld.isAssignableFrom(methodClass)) {
                            // Only add if not already found in a sub class
                            //方法名、工作在哪个线程、事件类型
                            subscriberMethods.add(new SubscriberMethod(method, threadMode, eventType));
                        } else {
                            // Revert the put, old class is further down the class hierarchy
                            eventTypesFound.put(methodKey, methodClassOld);
                        }
                    }
                } else if (!skipMethodVerificationForClasses.containsKey(methodClass)) {
                    Log.d(EventBus.TAG, "Skipping method (not public, static or abstract): " + methodClass + "."
                            + methodName);
                }
            }
        }
    }

    private ThreadMode getThreadMode(Class<?> clazz, Method method, String methodName) {
        String modifierString = methodName.substring(ON_EVENT_METHOD_NAME.length());
        ThreadMode threadMode;
        if (modifierString.length() == 0) {
            threadMode = ThreadMode.PostThread;
        } else if (modifierString.equals("MainThread")) {
            threadMode = ThreadMode.MainThread;
        } else if (modifierString.equals("BackgroundThread")) {
            threadMode = ThreadMode.BackgroundThread;
        } else if (modifierString.equals("Async")) {
            threadMode = ThreadMode.Async;
        } else {
            if (!skipMethodVerificationForClasses.containsKey(clazz)) {
                throw new EventBusException("Illegal onEvent method, check for typos: " + method);
            } else {
                threadMode = null;
            }
        }
        return threadMode;
    }

    static void clearCaches() {
        synchronized (methodCache) {
            methodCache.clear();
        }
    }

}
