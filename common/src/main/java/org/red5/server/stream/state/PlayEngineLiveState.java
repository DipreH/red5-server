package org.red5.server.stream.state;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.codec.IAudioStreamCodec;
import org.red5.codec.IStreamCodecInfo;
import org.red5.codec.IVideoStreamCodec;
import org.red5.codec.StreamCodecInfo;
import org.red5.server.api.scope.IBroadcastScope;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.api.stream.StreamState;
import org.red5.server.messaging.IMessageInput;
import org.red5.server.messaging.IMessageOutput;
import org.red5.server.net.rtmp.event.*;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.stream.IFrameDropper;
import org.red5.server.stream.PlayEngine;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;
import java.io.IOException;

public class PlayEngineLiveState extends AbstractPlayEngineState {


    public PlayEngineLiveState(PlayEngine p, Logger log) {
        super(p, log);
    }


    @Override
    public boolean play(IPlayItem item, IMessageInput in, IScope thisScope, boolean withReset) throws IOException {
        boolean sendNotifications = true;
        in = getPlayEngine().getProviderService().getLiveProviderInput(thisScope, item.getName(), false);
        if (getPlayEngine().getMsgInReference().compareAndSet(null, in)) {
            // drop all frames up to the next keyframe
            getPlayEngine().getVideoFrameDropper().reset(IFrameDropper.SEND_KEYFRAMES_CHECK);
            if (in instanceof IBroadcastScope) {
                IBroadcastStream stream = ((IBroadcastScope) in).getClientBroadcastStream();
                if (stream != null && stream.getCodecInfo() != null) {
                    IVideoStreamCodec videoCodec = stream.getCodecInfo().getVideoCodec();
                    if (videoCodec != null) {
                        if (withReset) {
                            getPlayEngine().withResetActions(item);
                        }
                        sendNotifications = false;
                        if (videoCodec.getNumInterframes() > 0 || videoCodec.getKeyframe() != null) {
                            getPlayEngine().setBufferedInterframeIdx(0);
                            getPlayEngine().getVideoFrameDropper().reset(IFrameDropper.SEND_ALL);
                        }
                    }
                }
            }
            // subscribe to stream (ClientBroadcastStream.onPipeConnectionEvent)
            in.subscribe(getPlayEngine(), null);
            // execute the processes to get Live playback setup
            playLive();

        }
        return sendNotifications;
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

