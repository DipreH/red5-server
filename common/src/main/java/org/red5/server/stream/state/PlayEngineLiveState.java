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
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;
import java.io.IOException;

public class PlayEngineLiveState extends AbstractPlayEngineState {

    public PlayEngineLiveState(Logger log) {
        this.log = log;
        log.isTraceEnabled();
    }

    /**
     * Performs the processes needed for live streams. The following items are sent if they exist:
     * <ul>
     * <li>Metadata</li>
     * <li>Decoder configurations (ie. AVC codec)</li>
     * <li>Most recent keyframe</li>
     * </ul>
     *
     * @throws IOException
     */



    private void playLive() throws IOException {
        // change state
        getPlayEngine().getSubscriberStream().setState(StreamState.PLAYING);
        IMessageInput in = getPlayEngine().getMsgInReference().get();
        IMessageOutput out = getPlayEngine().getMsgOutReference().get();
        if (in != null && out != null) {
            // get the stream so that we can grab any metadata and decoder configs
            IBroadcastStream stream = (IBroadcastStream) ((IBroadcastScope) in).getClientBroadcastStream();
            // prevent an NPE when a play list is created and then immediately flushed
            int ts = 0;
            if (stream != null) {
                Notify metaData = stream.getMetaData();
                //check for metadata to send
                if (metaData != null) {
                    ts = metaData.getTimestamp();
                    log.debug("Metadata is available");
                    RTMPMessage metaMsg = RTMPMessage.build(metaData, metaData.getTimestamp());
                    sendMessage(metaMsg);
                } else {
                    log.debug("No metadata available");
                }
                IStreamCodecInfo codecInfo = stream.getCodecInfo();
                log.debug("Codec info: {}", codecInfo);
                if (codecInfo instanceof StreamCodecInfo) {
                    StreamCodecInfo info = (StreamCodecInfo) codecInfo;
                    // handle video codec with configuration
                    IVideoStreamCodec videoCodec = info.getVideoCodec();
                    log.debug("Video codec: {}", videoCodec);
                    if (videoCodec != null) {
                        // check for decoder configuration to send
                        IoBuffer config = videoCodec.getDecoderConfiguration();
                        if (config != null) {
                            log.debug("Decoder configuration is available for {}", videoCodec.getName());
                            VideoData conf = new VideoData(config, true);
                            log.debug("Pushing video decoder configuration");
                            sendMessage(RTMPMessage.build(conf, ts));
                        }
                        // check for keyframes to send
                        IVideoStreamCodec.FrameData[] keyFrames = videoCodec.getKeyframes();
                        for (IVideoStreamCodec.FrameData keyframe : keyFrames) {
                            log.debug("Keyframe is available");
                            VideoData video = new VideoData(keyframe.getFrame(), true);
                            log.debug("Pushing keyframe");
                            sendMessage(RTMPMessage.build(video, ts));
                        }
                    } else {
                        log.debug("No video decoder configuration available");
                    }
                    // handle audio codec with configuration
                    IAudioStreamCodec audioCodec = info.getAudioCodec();
                    log.debug("Audio codec: {}", audioCodec);
                    if (audioCodec != null) {
                        // check for decoder configuration to send
                        IoBuffer config = audioCodec.getDecoderConfiguration();
                        if (config != null) {
                            log.debug("Decoder configuration is available for {}", audioCodec.getName());
                            AudioData conf = new AudioData(config.asReadOnlyBuffer());
                            log.debug("Pushing audio decoder configuration");
                            sendMessage(RTMPMessage.build(conf, ts));
                        }
                    } else {
                        log.debug("No audio decoder configuration available");
                    }
                }
            }
        } else {
            throw new IOException(String.format("A message pipe is null - in: %b out: %b", (getPlayEngine().getMsgInReference() == null), (getPlayEngine().getMsgOutReference() == null)));
        }
        getPlayEngine().setConfigsDone(true);
    }


    @Override
    public void play(IPlayItem item, IMessageInput in, IScope thisScope, boolean withReset,boolean sendNotifications) throws IOException {
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
    }
}

