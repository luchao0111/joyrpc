package io.joyrpc.proxy;

/*-
 * #%L
 * joyrpc
 * %%
 * Copyright (C) 2019 joyrpc.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joyrpc.Invoker;
import io.joyrpc.Result;
import io.joyrpc.constants.Constants;
import io.joyrpc.context.RequestContext;
import io.joyrpc.extension.URL;
import io.joyrpc.protocol.message.Invocation;
import io.joyrpc.protocol.message.RequestMessage;
import io.joyrpc.util.SystemClock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static io.joyrpc.util.ClassUtils.isReturnFuture;

/**
 * The type Consumer invoke telnet.
 *
 * @date: 2 /19/2019
 */
public class ConsumerInvokeHandler implements InvocationHandler {
    /**
     * The Method name tostring.
     */
    static String METHOD_NAME_TOSTRING = "toString";
    /**
     * The Method name hashcode.
     */
    static String METHOD_NAME_HASHCODE = "hashCode";
    /**
     * The Method name equals.
     */
    static String METHOD_NAME_EQUALS = "equals";
    /**
     * The Invoker.
     */
    protected Invoker invoker;
    /**
     * 接口名称
     */
    protected Class iface;
    /**
     * consumer serviceUrl
     */
    protected URL serviceUrl;
    /**
     * 是否为异步
     */
    protected boolean async;

    /**
     * Instantiates a new Consumer telnet.
     *
     * @param invoker the invoker
     */
    public ConsumerInvokeHandler(Invoker invoker, Class iface) {
        this.invoker = invoker;
        this.iface = iface;
    }

    public void setServiceUrl(URL serviceUrl) {
        this.async = serviceUrl.getBoolean(Constants.ASYNC_OPTION);
        this.serviceUrl = serviceUrl;
    }

    public void setInvoker(Invoker invoker) {
        this.invoker = invoker;
    }

    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] param) throws Throwable {

        //Java8允许在接口上定义静态方法
        if (Modifier.isStatic(method.getModifiers())) {
            //静态方法
            return method.invoke(proxy, param);
        }

        String methodName = method.getName();
        Class[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0 && METHOD_NAME_TOSTRING.equals(methodName)) {
            return invoker.toString();
        }
        if (paramTypes.length == 0 && METHOD_NAME_HASHCODE.equals(methodName)) {
            return invoker.hashCode();
        }
        if (paramTypes.length == 1 && METHOD_NAME_EQUALS.equals(methodName)) {
            return invoker.equals(param[0]);
        }

        boolean isReturnFuture = isReturnFuture(iface, method);
        boolean isAsync = this.async || isReturnFuture;
        //请求上下文
        RequestContext context = RequestContext.getContext();
        //上下文的异步必须设置成completeFuture
        context.setAsync(isReturnFuture);
        //构造请求消息
        RequestMessage<Invocation> request = RequestMessage.build(new Invocation(iface, method, param));
        //分组Failover调用，需要在这里设置创建时间和超时时间，不能再Refer里面。否则会重置。
        request.setCreateTime(SystemClock.now());
        //超时时间为0，Refer会自动修正，便于分组重试
        request.getHeader().setTimeout(0);
        //当前线程上下文
        request.setThread(Thread.currentThread());
        request.setContext(context);
        Object response = doInvoke(invoker, request, isAsync);
        if (isAsync) {
            if (isReturnFuture) {
                //方法返回值为 future
                return response;
            } else {
                //手动异步
                context.setFuture((CompletableFuture<?>) response);
                return null;
            }
        } else {
            // 返回同步结果
            return response;
        }
    }

    /**
     * 这个方法用来做 Trace 追踪的增强点，不要随便修改
     */
    private Object doInvoke(Invoker invoker, RequestMessage<Invocation> request, boolean async) throws Throwable {
        RequestContext context = request.getContext();
        CompletableFuture<Result> future = invoker.invoke(request);
        if (async) {
            //方法返回值为 future
            CompletableFuture<Object> response = new CompletableFuture<>();
            future.whenComplete((res, err) -> {
                Throwable throwable = err == null ? res.getException() : err;
                if (throwable != null) {
                    response.completeExceptionally(throwable);
                } else {
                    response.complete(res.getValue());
                }
            });
            response.whenComplete((r, t) -> {
                //业务逻辑执行完毕，进行异步线程清理清理
                if (request.getThread() != Thread.currentThread()) {
                    RequestContext.remove();
                }
            });

            if (future.isDone()) {
                //清理线程
                context.clearAttachments();
            } else {
                //异步调用，需要复制一份数据，保留会话信息
                Map<String, Object> session = context.getSession();
                RequestContext.restore(new RequestContext(session == null ? null : new HashMap<>(session)));
            }
            return response;
        } else {
            try {
                //正常同步返回
                Result result = future.get();
                if (result.isException()) {
                    throw result.getException();
                }
                return result.getValue();
            } catch (ExecutionException e) {
                throw e.getCause() != null ? e.getCause() : e;
            } finally {
                context.clearAttachments();
            }
        }
    }

}