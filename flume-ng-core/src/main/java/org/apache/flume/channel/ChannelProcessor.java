/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.channel;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.*;
import java.util.concurrent.*;

import org.apache.flume.Channel;
import org.apache.flume.ChannelException;
import org.apache.flume.ChannelSelector;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.apache.flume.interceptor.Interceptor;
import org.apache.flume.interceptor.InterceptorChain;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.interceptor.InterceptorBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A channel processor exposes operations to put {@link Event}s into
 * {@link Channel}s. These operations will propagate a {@link ChannelException}
 * if any errors occur while attempting to write to {@code required} channels.
 * <p>
 * Each channel processor instance is configured with a {@link ChannelSelector}
 * instance that specifies which channels are
 * {@linkplain ChannelSelector#getRequiredChannels(Event) required} and which
 * channels are
 * {@linkplain ChannelSelector#getOptionalChannels(Event) optional}.
 */
public class ChannelProcessor implements Configurable {

    private static final Logger LOG = LoggerFactory.getLogger(
            ChannelProcessor.class);

    private final ChannelSelector selector;
    private final InterceptorChain interceptorChain;
    private ExecutorService execService;
    BlockingQueue<Runnable> taskQueue;

    public ChannelProcessor(ChannelSelector selector) {
        this.selector = selector;
        this.interceptorChain = new InterceptorChain();
    }

    public void initialize() {
        interceptorChain.initialize();
    }

    public void close() {
        interceptorChain.close();
    }

    /**
     * The Context of the associated Source is passed.
     *
     * @param context
     */
    @Override
    public void configure(Context context) {
        int queueSize = context.getInteger("pendingTransactions", 20);
        taskQueue = new ArrayBlockingQueue<Runnable>(queueSize, true);
        ThreadFactory factory = new ThreadFactoryBuilder()
                .setNameFormat("OptionalChannelProcessorThread").build();
        this.execService =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, taskQueue,
                        factory, new ThreadPoolExecutor.DiscardPolicy());
        configureInterceptors(context);
    }

    // WARNING: throws FlumeException (is that ok?)
    private void configureInterceptors(Context context) {

        List<Interceptor> interceptors = Lists.newLinkedList();

        String interceptorListStr = context.getString("interceptors", "");
        if (interceptorListStr.isEmpty()) {
            return;
        }
        String[] interceptorNames = interceptorListStr.split("\\s+");

        Context interceptorContexts =
                new Context(context.getSubProperties("interceptors."));

        // run through and instantiate all the interceptors specified in the Context
        InterceptorBuilderFactory factory = new InterceptorBuilderFactory();
        for (String interceptorName : interceptorNames) {
            Context interceptorContext = new Context(
                    interceptorContexts.getSubProperties(interceptorName + "."));
            String type = interceptorContext.getString("type");
            if (type == null) {
                LOG.error("Type not specified for interceptor " + interceptorName);
                throw new FlumeException("Interceptor.Type not specified for " +
                        interceptorName);
            }
            try {
                Interceptor.Builder builder = factory.newInstance(type);
                builder.configure(interceptorContext);
                interceptors.add(builder.build());
            } catch (ClassNotFoundException e) {
                LOG.error("Builder class not found. Exception follows.", e);
                throw new FlumeException("Interceptor.Builder not found.", e);
            } catch (InstantiationException e) {
                LOG.error("Could not instantiate Builder. Exception follows.", e);
                throw new FlumeException("Interceptor.Builder not constructable.", e);
            } catch (IllegalAccessException e) {
                LOG.error("Unable to access Builder. Exception follows.", e);
                throw new FlumeException("Unable to access Interceptor.Builder.", e);
            }
        }

        interceptorChain.setInterceptors(interceptors);
    }

    public ChannelSelector getSelector() {
        return selector;
    }


    private boolean loadBalanceTryOther(List<Event> batch, List<Channel> reqChannels, Channel failChannel) {
        for (Channel otherChannel : reqChannels) {
            //失败通道排除
            if (otherChannel != failChannel) {
                for (Event event : batch) {
                    //重新标记 Event Header
                    Map<String, String> eventHeader = event.getHeaders();
                    eventHeader.put("sc", otherChannel.getName());
                }
                try {
                    executeChannelTransaction(otherChannel, batch, false);
                    //有一个通道处理成功 提交返回
                    return true;
                }
                //只捕获Channel异常 Error穿透
                catch (ChannelException e) {
                }
            }
        }
        return false;
    }

    /**
     * 自定义负载通道选择器处理 仅限 selector 为 LoadBalanceChannelSelector 时使用
     * 批次Event 分割为N块(N=通道列表数),分别提交通道列表,某个通道失败将尝试将批次块转提其它通道,全部失败时抛出
     *
     * @param events
     */
    private void processEventBatchByLoadBalance(List<Event> events) {
        LoadBalanceChannelSelector loadBalanceChannelSelector = (LoadBalanceChannelSelector) selector;
        List<Channel> reqChannels = loadBalanceChannelSelector.getRequiredChannels(null);
        Map<Channel, List<Event>> channelQueue = new LinkedHashMap<Channel, List<Event>>();

        for(int eventIdx=0;eventIdx< events.size();eventIdx++)
        {
            Event event = events.get(eventIdx);
            int channelIdx = eventIdx % reqChannels.size();
            Channel currentChannel = reqChannels.get(channelIdx);

            Map<String, String> eventHeader = event.getHeaders();
            eventHeader.put("sc", currentChannel.getName());
            List<Event> batch = channelQueue.get(currentChannel);
            if (batch == null) {
                batch = new ArrayList<Event>();
                channelQueue.put(currentChannel, batch);
            }
            batch.add(event);
        }
        for (Map.Entry<Channel, List<Event>> entry : channelQueue.entrySet()) {
            Channel channel = entry.getKey();
            List<Event> batch = entry.getValue();
            if (null != batch && batch.size() > 0) {
                boolean isProcessSuccess = false;
                Transaction tx = entry.getKey().getTransaction();
                Preconditions.checkNotNull(tx, "Transaction object must not be null");
                try {
                    tx.begin();
                    for (Event event : batch) {
                        channel.put(event);
                    }
                    tx.commit();
                    isProcessSuccess = true;
                }
                //失败通道先回滚关闭  后续再处理
                catch (Throwable t) {
                    tx.rollback();
                } finally {
                    tx.close();
                }
                if (!isProcessSuccess) {
                    boolean isRetrySuccess = loadBalanceTryOther(batch, reqChannels, channel);
                    if (!isRetrySuccess) {
                        //其它通道重试失败,确认失败抛出
                        throw new ChannelException("loadBalance try all channels failed ");
                    }
                }
            }
        }
    }

    /**
     * 自定义容错通道选择器处理 仅限 selector 为 FailoverChannelSelector 时使用
     * 以整个批次为单位轮播尝试通道列表,有一个处理成功即完毕,全部失败将抛出异常FlumeException
     *
     * @param events
     */
    private void processEventBatchByFailover(List<Event> events) {
        FailoverChannelSelector failoverSelector = (FailoverChannelSelector) selector;
        List<Channel> reqChannels = failoverSelector.getRequiredChannels(null);
        String lastExceptionMsg = "";
        for (Channel channel : reqChannels) {
            List<Event> eventQueue = new ArrayList<Event>();
            for (Event event : events) {
                Map<String, String> eventHeader = event.getHeaders();
                eventHeader.put("sc", channel.getName());
                eventQueue.add(event);
            }
            try {
                executeChannelTransaction(channel, eventQueue, false);
                return;
            }
            //只捕获ChannelException Error穿透
            catch (ChannelException e) {
                LOG.error("loadBalance select channel exception : " + channel + ": " + e.getMessage());
                lastExceptionMsg = e.getMessage();
            }
        }
        throw new FlumeException("LoadBalanceChannelSelector all channel failed,lastExceptionMsg:" + lastExceptionMsg);
    }

    /**
     * Attempts to {@linkplain Channel#put(Event) put} the given events into each
     * configured channel. If any {@code required} channel throws a
     * {@link ChannelException}, that exception will be propagated.
     * <p>
     * <p>Note that if multiple channels are configured, some {@link Transaction}s
     * may have already been committed while others may be rolled back in the
     * case of an exception.
     *
     * @param events A list of events to put into the configured channels.
     * @throws ChannelException when a write to a required channel fails.
     */
    public void processEventBatch(List<Event> events) {
        Preconditions.checkNotNull(events, "Event list must not be null");

        events = interceptorChain.intercept(events);


        if (selector instanceof FailoverChannelSelector) {
            //自定义容错通道选择器 接管提交通道逻辑
            processEventBatchByFailover(events);
        } else if (selector instanceof LoadBalanceChannelSelector) {
            //自定义负载通道选择器 接管提交通道逻辑
            processEventBatchByLoadBalance(events);
        } else {
            //没有使用自定义的负载通道选择器,一切照原始逻辑
            Map<Channel, List<Event>> reqChannelQueue =
                    new LinkedHashMap<Channel, List<Event>>();

            Map<Channel, List<Event>> optChannelQueue =
                    new LinkedHashMap<Channel, List<Event>>();

            for (Event event : events) {
                List<Channel> reqChannels = selector.getRequiredChannels(event);
                for (Channel ch : reqChannels) {
                    List<Event> eventQueue = reqChannelQueue.get(ch);
                    if (eventQueue == null) {
                        eventQueue = new ArrayList<Event>();
                        reqChannelQueue.put(ch, eventQueue);
                    }
                    eventQueue.add(event);
                }

                List<Channel> optChannels = selector.getOptionalChannels(event);
                for (Channel ch : optChannels) {
                    List<Event> eventQueue = optChannelQueue.get(ch);
                    if (eventQueue == null) {
                        eventQueue = new ArrayList<Event>();
                        optChannelQueue.put(ch, eventQueue);
                    }
                    eventQueue.add(event);
                }
            }

            // Process required channels
            for (Channel reqChannel : reqChannelQueue.keySet()) {
                List<Event> batch = reqChannelQueue.get(reqChannel);
                executeChannelTransaction(reqChannel, batch, false);
            }

            // Process optional channels
            for (Channel optChannel : optChannelQueue.keySet()) {
                List<Event> batch = optChannelQueue.get(optChannel);
                execService.submit(new OptionalChannelTransactionRunnable(optChannel, batch));
            }
        }
    }

    /**
     * Attempts to {@linkplain Channel#put(Event) put} the given event into each
     * configured channel. If any {@code required} channel throws a
     * {@link ChannelException}, that exception will be propagated.
     * <p>
     * <p>Note that if multiple channels are configured, some {@link Transaction}s
     * may have already been committed while others may be rolled back in the
     * case of an exception.
     *
     * @param event The event to put into the configured channels.
     * @throws ChannelException when a write to a required channel fails.
     */
    public void processEvent(Event event) {
        if (selector instanceof LoadBalanceChannelSelector) {
            List<Event> events = new ArrayList<Event>(1);
            events.add(event);
            processEventBatch(events);
        } else {
            // 没使用 LoadBalanceChannelSelector 完全沿用原始逻辑
            event = interceptorChain.intercept(event);
            if (event == null) {
                return;
            }
            List<Event> events = new ArrayList<Event>(1);
            events.add(event);

            // Process required channels
            List<Channel> requiredChannels = selector.getRequiredChannels(event);
            for (Channel reqChannel : requiredChannels) {
                executeChannelTransaction(reqChannel, events, false);
            }

            // Process optional channels
            List<Channel> optionalChannels = selector.getOptionalChannels(event);
            for (Channel optChannel : optionalChannels) {
                execService.submit(new OptionalChannelTransactionRunnable(optChannel, events));
            }
        }
    }

    private static void executeChannelTransaction(Channel channel, List<Event> batch, boolean isOptional) {
        Transaction tx = channel.getTransaction();
        Preconditions.checkNotNull(tx, "Transaction object must not be null");
        try {
            tx.begin();

            for (Event event : batch) {
                channel.put(event);
            }

            tx.commit();
        } catch (Throwable t) {
            tx.rollback();
            if (t instanceof Error) {
                LOG.error("Error while writing to channel: " + channel, t);
                throw (Error) t;
            } else if (!isOptional) {
                throw new ChannelException("Unable to put batch on required " + "channel: " + channel, t);
            }
        } finally {
            tx.close();
        }
    }

    private static class OptionalChannelTransactionRunnable implements Runnable {
        private Channel channel;
        private List<Event> events;

        OptionalChannelTransactionRunnable(Channel channel, List<Event> events) {
            this.channel = channel;
            this.events = events;
        }

        public void run() {
            executeChannelTransaction(channel, events, true);
        }
    }
}