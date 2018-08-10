package org.apache.flume.channel;

import junit.framework.Assert;
import org.apache.flume.Channel;
import org.apache.flume.ChannelSelector;
import org.apache.flume.Event;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by Administrator on 2018/8/9.
 */
public class TestLoadBalanceChannelSelector {
    private List<Channel> channels = new ArrayList<Channel>();

    private ChannelSelector selector;
    private Map<String, String> config = new HashMap<String, String>();

    @Before
    public void setUp() throws Exception {
        channels.clear();
        channels.add(MockChannel.createMockChannel("ch1"));
        channels.add(MockChannel.createMockChannel("ch2"));
        channels.add(MockChannel.createMockChannel("ch3"));
        config.put("type", "LOADBALANCE");
        config.put("split", "ch1 ch2 ch3");


    }

    @Test
    public void testRobinSelection() throws Exception {
        config.put("lb.type", "robin");
        selector = ChannelSelectorFactory.create(channels, config);
        Assert.assertTrue(selector instanceof LoadBalanceChannelSelector);

        Event event10 = new MockEvent();
        Event event11 = new MockEvent();
        Event event12 = new MockEvent();
        Event event13 = new MockEvent();
        Event event14 = new MockEvent();

        List<Event> eventList = new ArrayList<Event>();
        eventList.add(event10);
        eventList.add(event11);
        eventList.add(event12);
        eventList.add(event13);
        eventList.add(event14);

//        List<Channel> reqCh1 = selector.getRequiredChannels(event1);
//        Assert.assertTrue(reqCh1.get(0).getName().equals("ch1"));

        ChannelProcessor processor = new ChannelProcessor(selector);
        processor.processEventBatch(eventList);

        System.out.println("event10:"+event10.getHeaders().get("sc"));
        System.out.println("event11:"+event11.getHeaders().get("sc"));
        System.out.println("event12:"+event12.getHeaders().get("sc"));
        System.out.println("event13:"+event13.getHeaders().get("sc"));
        System.out.println("event14:"+event14.getHeaders().get("sc"));
        Assert.assertTrue(event10.getHeaders().get("sc")== "ch1") ;



        Event event2 = new MockEvent();
        processor.processEvent(event2);

        System.out.println(event2.getHeaders().get("sc"));
        Assert.assertTrue(event2.getHeaders().get("sc")== "ch1") ;

    }

    @Test
    public void testRandomSelection() throws Exception {
        config.put("lb.type", "random");
        selector = ChannelSelectorFactory.create(channels, config);
        Assert.assertTrue(selector instanceof LoadBalanceChannelSelector);

        Event event1 = new MockEvent();
        Map<String, String> header1 = new HashMap<String, String>();
        event1.setHeaders(header1);

        List<Event> eventList = new ArrayList<Event>();
        eventList.add(event1);

//        List<Channel> reqCh1 = selector.getRequiredChannels(event1);
//        Assert.assertTrue(reqCh1.get(0).getName().equals("ch1"));

        ChannelProcessor processor = new ChannelProcessor(selector);
        processor.processEventBatch(eventList);

        System.out.println(event1.getHeaders().get("sc"));

        Event event2 = new MockEvent();
        processor.processEvent(event2);

        System.out.println(event2.getHeaders().get("sc"));

    }
}
