package org.red5.server.stream.state;

import org.red5.server.api.scheduling.IScheduledJob;
import org.red5.server.api.scheduling.ISchedulingService;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.api.stream.StreamState;
import org.red5.server.messaging.IMessageInput;
import org.red5.server.net.rtmp.event.*;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.net.rtmp.status.Status;
import org.red5.server.net.rtmp.status.StatusCodes;
import org.red5.server.stream.PlayEngine;
import org.red5.server.stream.StreamNotFoundException;
import org.red5.server.stream.StreamService;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;

import java.io.IOException;

public class PlayEngineWaitState extends AbstractPlayEngineState{
    public PlayEngineWaitState(PlayEngine p, Logger log) {
        super(p, log);
    }

    @Override
    public boolean play(IPlayItem item, IMessageInput in, IScope thisScope, boolean withReset) throws IOException, StreamNotFoundException {
        // get source input with create
        in = getPlayEngine().getProviderService().getLiveProviderInput(thisScope, item.getName(), true);
        int type = (int) (item.getStart() / 1000);
        if (getPlayEngine().getMsgInReference().compareAndSet(null, in)) {
            if (type == -1 && item.getLength() >= 0) {
                if (getPlayEngine().isDebug()) {
                    log.debug("Creating wait job for {}", item.getLength());
                }
                // Wait given timeout for stream to be published
                getPlayEngine().setWaitLiveJob(getPlayEngine().getSchedulingService().addScheduledOnceJob(item.getLength(), new IScheduledJob() {
                    public void execute(ISchedulingService service) {
                        connectToProvider(item.getName());
                        getPlayEngine().setWaitLiveJob(null);
                        getPlayEngine().getSubscriberStream().onChange(StreamState.END);
                    }
                }));
            } else if (type == -2) {
                if (getPlayEngine().isDebug()) {
                    log.debug("Creating wait job");
                }
                // Wait x seconds for the stream to be published
                getPlayEngine().setWaitLiveJob(getPlayEngine().getSchedulingService().addScheduledOnceJob(15000, new IScheduledJob() {
                    public void execute(ISchedulingService service) {
                        connectToProvider(item.getName());
                        getPlayEngine().setWaitLiveJob(null);
                    }
                }));
            } else {
                connectToProvider(item.getName());
            }
        } else if (getPlayEngine().isDebug()) {
            log.debug("Message input already set for {}", item.getName());
        }
        return true;
    }

    @Override
    public void sendMessage(RTMPMessage messageIn) {
        IRTMPEvent eventIn = messageIn.getBody();
        IRTMPEvent event;
        switch (eventIn.getDataType()) {
            case Constants.TYPE_AGGREGATE:
                event = new Aggregate(((Aggregate) eventIn).getData());
                break;
            case Constants.TYPE_AUDIO_DATA:
                event = new AudioData(((AudioData) eventIn).getData());
                break;
            case Constants.TYPE_VIDEO_DATA:
                event = new VideoData(((VideoData) eventIn).getData());
                break;
            default:
                event = new Notify(((Notify) eventIn).getData());
                break;
        }
        // get the incoming event time
        int eventTime = eventIn.getTimestamp();
        // get the incoming event source type and set on the outgoing event
        event.setSourceType(eventIn.getSourceType());
        // instance the outgoing message
        RTMPMessage messageOut = RTMPMessage.build(event, eventTime);
        if (isTrace) {
            log.trace("Source type - in: {} out: {}", eventIn.getSourceType(), messageOut.getBody().getSourceType());
            long delta = System.currentTimeMillis() - this.getPlayEngine().getPlaybackStart();
            log.trace("sendMessage: streamStartTS {}, length {}, streamOffset {}, timestamp {} last timestamp {} delta {} buffered {}", new Object[] { getPlayEngine().getStreamStartTS().get(), getPlayEngine().getCurrentItem().get().getLength(), getPlayEngine().getStreamOffset(), eventTime, getPlayEngine().getLastMessageTimestamp(), delta, getPlayEngine().getLastMessageTimestamp() - delta });
        }
        // don't reset streamStartTS to 0 for live streams
        if (eventTime > 0 && getPlayEngine().getStreamStartTS().compareAndSet(-1, eventTime)) {
            log.debug("sendMessage: set streamStartTS");
        }
        // relative timestamp adjustment for live streams
        int startTs = getPlayEngine().getStreamStartTS().get();
        if (startTs > 0) {
            // subtract the offset time of when the stream started playing for the client
            eventTime -= startTs;
            messageOut.getBody().setTimestamp(eventTime);
            if (isTrace) {
                log.trace("sendMessage (updated): streamStartTS={}, length={}, streamOffset={}, timestamp={}", new Object[] { startTs,getPlayEngine().getCurrentItem().get().getLength(), getPlayEngine().getStreamOffset(), eventTime });
            }
        }
        getPlayEngine().doPushMessage(messageOut);
    }

    /**
     * Connects to the data provider.
     *
     * @param itemName
     *            name of the item to play
     */
    public final void connectToProvider(String itemName) {
        log.debug("Attempting connection to {}", itemName);
        IMessageInput in = getPlayEngine().getMsgInReference().get();
        if (in == null) {
            in = getPlayEngine().getProviderService().getLiveProviderInput(getPlayEngine().getSubscriberStream().getScope(), itemName, true);
            getPlayEngine().getMsgInReference().set(in);
        }
        if (in != null) {
            log.debug("Provider: {}", getPlayEngine().getMsgInReference().get());
            if (in.subscribe(getPlayEngine(), null)) {
                log.debug("Subscribed to {} provider", itemName);
                // execute the processes to get Live playback setup
                try {
                    playLive();
                } catch (IOException e) {
                    log.warn("Could not play live stream: {}", itemName, e);
                }
            } else {
                log.warn("Subscribe to {} provider failed", itemName);
            }
        } else {
            log.warn("Provider was not found for {}", itemName);
            StreamService.sendNetStreamStatus(getPlayEngine().getSubscriberStream().getConnection(), StatusCodes.NS_PLAY_STREAMNOTFOUND, "Stream was not found", itemName, Status.ERROR, getPlayEngine().getSubscriberStream().getStreamId());        }
    }
}
