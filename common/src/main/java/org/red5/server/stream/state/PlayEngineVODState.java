package org.red5.server.stream.state;

import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.messaging.IMessageInput;
import org.red5.server.net.rtmp.event.*;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;

import java.io.IOException;

public class PlayEngineVODState extends AbstractPlayEngineState{

    public PlayEngineVODState(Logger log) {
        this.log = log;
    }

    @Override
    public void play(IPlayItem item, IMessageInput in, IScope thisScope, boolean withReset, boolean sendNotifications) throws IOException {

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
            long delta = System.currentTimeMillis() - getPlayEngine().getPlaybackStart();
            log.trace("sendMessage: streamStartTS {}, length {}, streamOffset {}, timestamp {} last timestamp {} delta {} buffered {}", new Object[]{getPlayEngine().getStreamStartTS().get(), getPlayEngine().getCurrentItem().get().getLength(), getPlayEngine().getStreamOffset(), eventTime, getPlayEngine().getLastMessageTimestamp(), delta, getPlayEngine().getLastMessageTimestamp() - delta});
        }
        if (eventTime > 0 && getPlayEngine().getStreamStartTS().compareAndSet(-1, eventTime)) {
            log.debug("sendMessage: set streamStartTS");
            messageOut.getBody().setTimestamp(0);
        }
        long length = getPlayEngine().getCurrentItem().get().getLength();
        if (length >= 0) {
            int duration = eventTime - getPlayEngine().getStreamStartTS().get();
            if (isTrace) {
                log.trace("sendMessage duration={} length={}", duration, length);
            }
            if (duration - getPlayEngine().getStreamOffset() >= length) {
                // sent enough data to client
                getPlayEngine().stop();
                return;
            }
        }
    }
}
