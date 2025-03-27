package org.red5.server.stream.state;

import org.red5.server.api.scheduling.IScheduledJob;
import org.red5.server.api.scheduling.ISchedulingService;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.api.stream.StreamState;
import org.red5.server.messaging.IMessageInput;
import org.red5.server.net.rtmp.event.*;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.stream.PlayEngine;
import org.red5.server.stream.StreamNotFoundException;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;

import java.io.IOException;

public class PlayEngineWaitState extends AbstractPlayEngineState{
    public PlayEngineWaitState(PlayEngine p, Logger log) {
        super(p, log);
    }

    @Override
    public void play(IPlayItem item, IMessageInput in, IScope thisScope, boolean withReset, boolean sendNotifications) throws IOException, StreamNotFoundException {
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
                        getPlayEngine().connectToProvider(item.getName());
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
                        getPlayEngine().connectToProvider(item.getName());
                        getPlayEngine().setWaitLiveJob(null);
                    }
                }));
            } else {
                getPlayEngine().connectToProvider(item.getName());
            }
        } else if (getPlayEngine().isDebug()) {
            log.debug("Message input already set for {}", item.getName());
        }
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
}
