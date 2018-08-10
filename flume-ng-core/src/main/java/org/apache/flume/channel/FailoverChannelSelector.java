package org.apache.flume.channel;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Created by Administrator on 2018/8/9.
 */
public class FailoverChannelSelector extends AbstractChannelSelector {

    private static final Logger LOG = LoggerFactory.getLogger(FailoverChannelSelector.class);

    public static final String CONFIG_SELECTOR = "lb.type";
    public static final String CONFIG_SPLITE_CHANNEL = "split";

    private List<Channel> splitChannels = null;
    private String selectorType = null;
    private static Set<String> acceptSelectorType = null;

    static {
        acceptSelectorType = new HashSet<String>();
        acceptSelectorType.add("robin");
        acceptSelectorType.add("random");
    }

    @Override
    public void configure(Context context) {
        Map<String, Channel> channelNameMap = getChannelNameMap();

        this.splitChannels = getChannelListFromNames(context.getString(CONFIG_SPLITE_CHANNEL), channelNameMap);

        if (null == splitChannels || splitChannels.size() == 0) {
            throw new FlumeException("No channel configured for split ");
        }

        this.selectorType = context.getString(CONFIG_SELECTOR, "robin");
        if (!acceptSelectorType.contains(this.selectorType)) {
            throw new FlumeException(CONFIG_SELECTOR + " must be robin or random");
        }
    }

    @Override
    public List<Channel> getRequiredChannels(Event event) {
        if (this.selectorType == "robin") {
            return this.splitChannels;
        } else {
            Collections.shuffle(this.splitChannels);
            return this.splitChannels;
        }
    }

    @Override
    public List<Channel> getOptionalChannels(Event event) {
        return new ArrayList<Channel>();
    }
}
