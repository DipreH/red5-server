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
import org.red5.server.net.rtmp.event.AudioData;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.stream.PlayEngine;
import org.red5.server.stream.StreamNotFoundException;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;
import java.io.IOException;

public abstract class AbstractPlayEngineState {

    private PlayEngine playEngine;
    protected Logger log;
    protected boolean isTrace;

    public AbstractPlayEngineState(PlayEngine p,Logger log) {
        this.playEngine = p;
        this.log = log;
    }

    public PlayEngine getPlayEngine() {
        return playEngine;
    }

    public void setPlayEngine(PlayEngine playEngine) {
        this.playEngine = playEngine;
    }

    public abstract boolean play(IPlayItem item, IMessageInput in, IScope thisScope, boolean withReset) throws IOException, StreamNotFoundException;

    public abstract void sendMessage(RTMPMessage messageIn);

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



    protected void playLive() throws IOException {
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
}
