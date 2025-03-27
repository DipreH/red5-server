package org.red5.server.stream.state;

import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.api.stream.StreamState;
import org.red5.server.messaging.IMessage;
import org.red5.server.messaging.IMessageInput;
import org.red5.server.net.rtmp.event.*;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.stream.PlayEngine;
import org.red5.server.stream.StreamNotFoundException;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;

import java.io.IOException;

public class PlayEngineVODState extends AbstractPlayEngineState{


    public PlayEngineVODState(PlayEngine p, Logger log) {
        super(p, log);
    }

    private final IMessage playVOD(boolean withReset, long itemLength) throws IOException {
        IMessage msg = null;
        // change state
        getPlayEngine().getSubscriberStream().setState(StreamState.PLAYING);
        if (withReset) {
            getPlayEngine().releasePendingMessage();
        }
        getPlayEngine().sendVODInitCM(getPlayEngine().getCurrentItem().get());
        // Don't use pullAndPush to detect IOExceptions prior to sending NetStream.Play.Start
        int start = (int) getPlayEngine().getCurrentItem().get().getStart();
        if (start > 0) {
            getPlayEngine().setStreamOffset(getPlayEngine().sendVODSeekCM(start));
            // We seeked to the nearest keyframe so use real timestamp now
            if (getPlayEngine().getStreamOffset() == -1) {
                getPlayEngine().setStreamOffset(start);
            }
        }
        IMessageInput in = getPlayEngine().getMsgInReference().get();
        msg = in.pullMessage();
        if (msg instanceof RTMPMessage) {
            // Only send first video frame
            IRTMPEvent body = ((RTMPMessage) msg).getBody();
            if (itemLength == 0) {
                while (body != null && !(body instanceof VideoData)) {
                    msg = in.pullMessage();
                    if (msg != null && msg instanceof RTMPMessage) {
                        body = ((RTMPMessage) msg).getBody();
                    } else {
                        break;
                    }
                }
            }
            if (body != null) {
                // Adjust timestamp when playing lists
                body.setTimestamp(body.getTimestamp() + getPlayEngine().getTimestampOffset());
            }
        }
        return msg;
    }

    @Override
    public void play(IPlayItem item, IMessageInput in, IScope thisScope, boolean withReset, boolean sendNotifications) throws IOException, StreamNotFoundException {
        IMessage msg = null;
        in = getPlayEngine().getProviderService().getVODProviderInput(thisScope, item.getName());
        if (getPlayEngine().getMsgInReference().compareAndSet(null, in)) {
            if (in.subscribe(getPlayEngine(), null)) {
                // execute the processes to get VOD playback setup
                msg = playVOD(withReset, item.getLength());
            } else {
                log.warn("Input source subscribe failed");
                throw new IOException(String.format("Subscribe to %s failed", item.getName()));
            }
        } else {
            getPlayEngine().sendStreamNotFoundStatus(item);
            throw new StreamNotFoundException(item.getName());
        }
        if (msg != null) {
            sendMessage((RTMPMessage) msg);
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
        getPlayEngine().doPushMessage(messageOut);
    }
}
