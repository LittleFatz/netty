/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.PlatformDependent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.WeakHashMap;

final class ChannelHandlerMask {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelHandlerMask.class);

    // Using to mask which methods must be called for a ChannelHandler.
    // 0b 0000 0000 0000 0000 0000 0000 0000 0001
    static final int MASK_EXCEPTION_CAUGHT = 1;
    // 0b 0000 0000 0000 0000 0000 0000 0000 0010
    static final int MASK_CHANNEL_REGISTERED = 1 << 1;
    // 0b 0000 0000 0000 0000 0000 0000 0000 0100
    static final int MASK_CHANNEL_UNREGISTERED = 1 << 2;
    // 0b 0000 0000 0000 0000 0000 0000 0000 1000
    static final int MASK_CHANNEL_ACTIVE = 1 << 3;
    // 0b 0000 0000 0000 0000 0000 0000 0001 0000
    static final int MASK_CHANNEL_INACTIVE = 1 << 4;
    // 0b 0000 0000 0000 0000 0000 0000 0010 0000
    static final int MASK_CHANNEL_READ = 1 << 5;
    // 0b 0000 0000 0000 0000 0000 0000 0100 0000
    static final int MASK_CHANNEL_READ_COMPLETE = 1 << 6;
    // 0b 0000 0000 0000 0000 0000 0000 1000 0000
    static final int MASK_USER_EVENT_TRIGGERED = 1 << 7;
    // 0b 0000 0000 0000 0000 0000 0001 0000 0000
    static final int MASK_CHANNEL_WRITABILITY_CHANGED = 1 << 8;
    // 0b 0000 0000 0000 0000 0000 0010 0000 0000
    static final int MASK_BIND = 1 << 9;
    // 0b 0000 0000 0000 0000 0000 0100 0000 0000
    static final int MASK_CONNECT = 1 << 10;
    // 0b 0000 0000 0000 0000 0000 1000 0000 0000
    static final int MASK_DISCONNECT = 1 << 11;
    // 0b 0000 0000 0000 0000 0001 0000 0000 0000
    static final int MASK_CLOSE = 1 << 12;
    // 0b 0000 0000 0000 0000 0010 0000 0000 0000
    static final int MASK_DEREGISTER = 1 << 13;
    // 0b 0000 0000 0000 0000 0100 0000 0000 0000
    static final int MASK_READ = 1 << 14;
    // 0b 0000 0000 0000 0000 1000 0000 0000 0000
    static final int MASK_WRITE = 1 << 15;
    // 0b 0000 0000 0000 0001 0000 0000 0000 0000
    static final int MASK_FLUSH = 1 << 16;

    //把上面对应的位或起来
    // 计算出一个入站事件的掩码：
    // 0b 0000 0000 0000 0000 0000 0000 0000 0010
    // 0b 0000 0000 0000 0000 0000 0000 0000 0100
    // 0b 0000 0000 0000 0000 0000 0000 0000 1000
    // 0b 0000 0000 0000 0000 0000 0000 0001 0000
    // 0b 0000 0000 0000 0000 0000 0000 0010 0000
    // 0b 0000 0000 0000 0000 0000 0000 0100 0000
    // 0b 0000 0000 0000 0000 0000 0000 1000 0000
    // 0b 0000 0000 0000 0000 0000 0001 0000 0000
    // 0b 0000 0000 0000 0000 0000 0001 1111 1110
    static final int MASK_ONLY_INBOUND =  MASK_CHANNEL_REGISTERED |
            MASK_CHANNEL_UNREGISTERED | MASK_CHANNEL_ACTIVE | MASK_CHANNEL_INACTIVE | MASK_CHANNEL_READ |
            MASK_CHANNEL_READ_COMPLETE | MASK_USER_EVENT_TRIGGERED | MASK_CHANNEL_WRITABILITY_CHANGED;


    // 计算出一个入站事件的掩码（包含 MASK_EXCEPTION_CAUGHT）：
    // 0b 0000 0000 0000 0000 0000 0001 1111 1110
    // 0b 0000 0000 0000 0000 0000 0000 0000 0001
    // 0b 0000 0000 0000 0000 0000 0001 1111 1111
    private static final int MASK_ALL_INBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_INBOUND;



    // 计算出一个出站事件的掩码：
    // 0b 0000 0000 0000 0000 0000 0010 0000 0000
    // 0b 0000 0000 0000 0000 0000 0100 0000 0000
    // 0b 0000 0000 0000 0000 0000 1000 0000 0000
    // 0b 0000 0000 0000 0000 0001 0000 0000 0000
    // 0b 0000 0000 0000 0000 0010 0000 0000 0000
    // 0b 0000 0000 0000 0000 0100 0000 0000 0000
    // 0b 0000 0000 0000 0000 1000 0000 0000 0000
    // 0b 0000 0000 0000 0001 0000 0000 0000 0000
    // 0b 0000 0000 0000 0001 1111 1110 0000 0000
    static final int MASK_ONLY_OUTBOUND =  MASK_BIND | MASK_CONNECT | MASK_DISCONNECT |
            MASK_CLOSE | MASK_DEREGISTER | MASK_READ | MASK_WRITE | MASK_FLUSH;


    // 计算出一个出站事件的掩码（包含 MASK_EXCEPTION_CAUGHT）：
    // 0b 0000 0000 0000 0001 1111 1110 0000 0000
    // 0b 0000 0000 0000 0000 0000 0000 0000 0001
    // 0b 0000 0000 0000 0001 1111 1110 0000 0001
    private static final int MASK_ALL_OUTBOUND = MASK_EXCEPTION_CAUGHT | MASK_ONLY_OUTBOUND;

    private static final FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>> MASKS =
            new FastThreadLocal<Map<Class<? extends ChannelHandler>, Integer>>() {
                @Override
                protected Map<Class<? extends ChannelHandler>, Integer> initialValue() {
                    return new WeakHashMap<Class<? extends ChannelHandler>, Integer>(32);
                }
            };

    /**
     * Return the {@code executionMask}.
     */
    static int mask(Class<? extends ChannelHandler> clazz) {
        // Try to obtain the mask from the cache first. If this fails calculate it and put it in the cache for fast
        // lookup in the future.
        Map<Class<? extends ChannelHandler>, Integer> cache = MASKS.get();
        Integer mask = cache.get(clazz);
        if (mask == null) {
            mask = mask0(clazz);
            cache.put(clazz, mask);
        }
        return mask;
    }

    /**
     * Calculate the {@code executionMask}.
     */
    private static int mask0(Class<? extends ChannelHandler> handlerType) {
        int mask = MASK_EXCEPTION_CAUGHT;
        try {
            //判断handlerType是否ChannelInboundHandler的子类，或者实现了ChannelInboundHandler接口
            if (ChannelInboundHandler.class.isAssignableFrom(handlerType)) {
                mask |= MASK_ALL_INBOUND;

                /**
                 * 如果handlerType没有重写ChannelHandler该方法的话，方法上还是会有 @Skip，那么进入if逻辑
                 * 然后把对应位设置为0
                 */
                if (isSkippable(handlerType, "channelRegistered", ChannelHandlerContext.class)) {
                    // 0b 0000 0000 0000 0000 0000 0001 1111 1111
                    // 0b 1111 1111 1111 1111 1111 1111 1111 1101  ~MASK_CHANNEL_REGISTERED取反
                    // 0b 0000 0000 0000 0000 0000 0001 1111 1101
                    mask &= ~MASK_CHANNEL_REGISTERED;
                }
                if (isSkippable(handlerType, "channelUnregistered", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_UNREGISTERED;
                }
                if (isSkippable(handlerType, "channelActive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_ACTIVE;
                }
                if (isSkippable(handlerType, "channelInactive", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_INACTIVE;
                }
                if (isSkippable(handlerType, "channelRead", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_CHANNEL_READ;
                }
                if (isSkippable(handlerType, "channelReadComplete", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_READ_COMPLETE;
                }
                if (isSkippable(handlerType, "channelWritabilityChanged", ChannelHandlerContext.class)) {
                    mask &= ~MASK_CHANNEL_WRITABILITY_CHANGED;
                }
                if (isSkippable(handlerType, "userEventTriggered", ChannelHandlerContext.class, Object.class)) {
                    mask &= ~MASK_USER_EVENT_TRIGGERED;
                }
            }

            if (ChannelOutboundHandler.class.isAssignableFrom(handlerType)) {
                mask |= MASK_ALL_OUTBOUND;

                if (isSkippable(handlerType, "bind", ChannelHandlerContext.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_BIND;
                }
                if (isSkippable(handlerType, "connect", ChannelHandlerContext.class, SocketAddress.class,
                        SocketAddress.class, ChannelPromise.class)) {
                    mask &= ~MASK_CONNECT;
                }
                if (isSkippable(handlerType, "disconnect", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DISCONNECT;
                }
                if (isSkippable(handlerType, "close", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_CLOSE;
                }
                if (isSkippable(handlerType, "deregister", ChannelHandlerContext.class, ChannelPromise.class)) {
                    mask &= ~MASK_DEREGISTER;
                }
                if (isSkippable(handlerType, "read", ChannelHandlerContext.class)) {
                    mask &= ~MASK_READ;
                }
                if (isSkippable(handlerType, "write", ChannelHandlerContext.class,
                        Object.class, ChannelPromise.class)) {
                    mask &= ~MASK_WRITE;
                }
                if (isSkippable(handlerType, "flush", ChannelHandlerContext.class)) {
                    mask &= ~MASK_FLUSH;
                }
            }

            if (isSkippable(handlerType, "exceptionCaught", ChannelHandlerContext.class, Throwable.class)) {
                mask &= ~MASK_EXCEPTION_CAUGHT;
            }
        } catch (Exception e) {
            // Should never reach here.
            PlatformDependent.throwException(e);
        }

        return mask;
    }

    @SuppressWarnings("rawtypes")
    //方法是否有@Skip注释，如果有就跳过这个方法
    private static boolean isSkippable(
            final Class<?> handlerType, final String methodName, final Class<?>... paramTypes) throws Exception {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                Method m;
                try {
                    m = handlerType.getMethod(methodName, paramTypes);
                } catch (NoSuchMethodException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                            "Class {} missing method {}, assume we can not skip execution", handlerType, methodName, e);
                    }
                    return false;
                }
                return m.isAnnotationPresent(Skip.class);
            }
        });
    }

    private ChannelHandlerMask() { }

    /**
     * Indicates that the annotated event handler method in {@link ChannelHandler} will not be invoked by
     * {@link ChannelPipeline} and so <strong>MUST</strong> only be used when the {@link ChannelHandler}
     * method does nothing except forward to the next {@link ChannelHandler} in the pipeline.
     * <p>
     * Note that this annotation is not {@linkplain Inherited inherited}. If a user overrides a method annotated with
     * {@link Skip}, it will not be skipped anymore. Similarly, the user can override a method not annotated with
     * {@link Skip} and simply pass the event through to the next handler, which reverses the behavior of the
     * supertype.
     * </p>
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Skip {
        // no value
    }
}
