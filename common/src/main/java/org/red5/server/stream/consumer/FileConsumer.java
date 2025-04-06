package org.red5.server.stream.consumer;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.red5.codec.VideoCodec;
import org.red5.io.ITag;
import org.red5.io.ITagWriter;
import org.red5.io.flv.impl.FLVWriter;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IClientStream;
import org.red5.server.api.stream.IStreamFilenameGenerator;
import org.red5.server.api.stream.IStreamFilenameGenerator.GenerationType;
import org.red5.server.api.stream.consumer.IFileConsumer;
import org.red5.server.messaging.IMessage;
import org.red5.server.messaging.IMessageComponent;
import org.red5.server.messaging.IPipe;
import org.red5.server.messaging.IPipeConnectionListener;
import org.red5.server.messaging.IPushableConsumer;
import org.red5.server.messaging.OOBControlMessage;
import org.red5.server.messaging.PipeConnectionEvent;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.stream.DefaultStreamFilenameGenerator;
import org.red5.server.stream.IStreamData;
import org.red5.server.stream.message.RTMPMessage;
import org.red5.server.stream.message.ResetMessage;
import org.red5.server.util.ScopeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Consumer that pushes messages to a writer using priority / comparison.
 *
 * @author The Red5 Project
 * @author Paul Gregoire (mondain@gmail.com)
 */
public class FileConsumer extends AbstractFileConsumer implements Constants, IPushableConsumer, DisposableBean, IFileConsumer {

    private AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Executor for all instance writer jobs
     */
    private ExecutorService executor = Executors.newFixedThreadPool(1);

    private static QueuedMediaDataComparator comparator = new QueuedMediaDataComparator();

    /**
     * Queue to hold data awaiting writing
     */
    private BlockingQueue<QueuedMediaData> queue;

    /**
     * Tag writer
     */
    private ITagWriter writer;

    /**
     * Start timestamp
     */
    private int startTimestamp = -1;

    /**
     * Video decoder configuration
     */
    private ITag videoConfigurationTag;

    /**
     * Audio decoder configuration
     */
    @SuppressWarnings("unused")
    private ITag audioConfigurationTag;

    /**
     * Keeps track of the last spawned write worker.
     */
    private volatile Future<?> writerFuture;

    private volatile boolean gotKeyFrame = false;

    /**
     * Whether or not to use a comparator with a priority queue.
     */
    private boolean usePriority = true;

    /**
     * Queue offer timeout in milliseconds.
     */
    private long offerTimeout = 100L;

    /**
     * Default ctor
     */
    public FileConsumer() {
        super();
        this.queueThreshold = 240;
    }

    /**
     * Creates file consumer
     *
     * @param scope
     *            Scope of consumer
     * @param file
     *            File
     */
    public FileConsumer(IScope scope, File file) {
        super(scope,file);
        this.queueThreshold = 240;
    }

    /**
     * Creates file consumer
     *
     * @param scope
     *            Scope of consumer
     * @param fileName
     *            The file name without the extension
     * @param mode
     *            The recording mode
     */
    public FileConsumer(IScope scope, String fileName, String mode) {
        super(scope,fileName,mode);
        this.queueThreshold = 240;
    }

    /**
     * Push message through pipe
     *
     * @param pipe
     *            Pipe
     * @param message
     *            Message to push
     * @throws IOException
     *             if message could not be written
     */
    @SuppressWarnings("rawtypes")
    public void pushMessage(IPipe pipe, IMessage message) throws IOException {
        if (message instanceof RTMPMessage) {
            final IRTMPEvent msg = ((RTMPMessage) message).getBody();
            // if writes are delayed, queue the data and sort it by time
            if (queue == null) {
                if (usePriority) {
                    if (log.isTraceEnabled()) {
                        log.trace("Creating priority typed packet queue. queueThreshold={}", queueThreshold);
                    }
                    // if we want ordering / comparing built-in
                    queue = new PriorityBlockingQueue<>(queueThreshold <= 0 ? 240 : queueThreshold, comparator);
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("Creating non-priority typed packet queue");
                    }
                    // process as received
                    queue = new LinkedBlockingQueue<>();
                }
            }
            if (msg instanceof IStreamData) {
                // get the type
                byte dataType = msg.getDataType();
                // get the timestamp
                int timestamp = msg.getTimestamp();
                if (log.isTraceEnabled()) {
                    log.trace("Stream data, body saved, timestamp: {} data type: {} class type: {}", timestamp, dataType, msg.getClass().getName());
                }
                // if the last message was a reset or we just started, use the header timer
                if (startTimestamp == -1) {
                    startTimestamp = timestamp;
                    timestamp = 0;
                } else {
                    timestamp -= startTimestamp;
                }
                // offer to the queue
                try {
                    QueuedMediaData queued = new QueuedMediaData(timestamp, dataType, (IStreamData) msg);
                    if (log.isTraceEnabled()) {
                        log.trace("Inserting packet into queue. timestamp: {} queue size: {}, codecId={}, isConfig={}", timestamp, queue.size(), queued.codecId, queued.config);
                    }
                    if (queue.size() > queueThreshold) {
                        if (queue.size() % 20 == 0) {
                            log.warn("Queue size is greater than threshold. queue size={} threshold={}", queue.size(), queueThreshold);
                        }
                    }
                    if (queue.size() < 2 * queueThreshold) {
                        // Cap queue size to prevent a runaway stream causing OOM.
                        queue.offer(queued, offerTimeout, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    log.warn("Stream data was not accepted by the queue - timestamp: {} data type: {}", timestamp, dataType, e);
                }
            }
            // initialize a writer
            if (writer == null) {
                executor.submit(new Runnable() {
                    public void run() {
                        Thread.currentThread().setName("ProFileConsumer-" + path.getFileName());
                        try {
                            if (log.isTraceEnabled()) {
                                log.trace("Running FileConsumer thread. queue size: {} initialized: {} writerNotNull={}", queue.size(), initialized, (writer != null));
                            }
                            init();
                            while (writer != null) {
                                if (log.isTraceEnabled()) {
                                    log.trace("Processing packet from queue. queue size: {}", queue.size());
                                }

                                try {
                                    QueuedMediaData queued = queue.take();
                                    if (queued != null) {
                                        // get data type
                                        byte dataType = queued.getDataType();
                                        // get timestamp
                                        int timestamp = queued.getTimestamp();
                                        ITag tag = queued.getData();
                                        // ensure that our first video frame written is a key frame
                                        if (queued.isVideo()) {
                                            if (log.isTraceEnabled()) {
                                                log.trace("pushMessage video - waitForKeyframe: {} gotKeyframe: {} timestamp: {}", waitForVideoKeyframe, gotKeyFrame, queued.getTimestamp());
                                            }
                                            if (queued.codecId == VideoCodec.AVC.getId()) {
                                                if (queued.isConfig()) {
                                                    videoConfigurationTag = tag;
                                                    gotKeyFrame = true;
                                                }
                                                if (videoConfigurationTag == null && waitForVideoKeyframe) {
                                                    continue;
                                                }
                                            } else {
                                                if (queued.frameType == VideoData.FrameType.KEYFRAME) {
                                                    gotKeyFrame = true;
                                                }
                                                if (waitForVideoKeyframe && !gotKeyFrame) {
                                                    continue;
                                                }
                                            }
                                        } else if (queued.isAudio()) {
                                            if (queued.isConfig()) {
                                                audioConfigurationTag = tag;
                                            }
                                        }

                                        if (queued.isVideo()) {
                                            if (log.isTraceEnabled()) {
                                                log.trace("Writing packet. frameType={} timestamp={}", queued.frameType, queued.getTimestamp());
                                            }
                                        }

                                        // write
                                        write(dataType, timestamp, tag);
                                        // clean up
                                        queued.dispose();
                                    } else {
                                        if (log.isTraceEnabled()) {
                                            log.trace("Queued media is null. queue size: {}", queue.size());
                                        }
                                    }
                                } catch (InterruptedException e) {
                                    log.warn("{}", e.getMessage(), e);
                                }
                                //finally {
                                //    if (log.isTraceEnabled()) {
                                //        log.trace("Clearing queue. queue size: {}", queue.size());
                                //    }
                                //    queue.clear();
                                //}
                            }
                        } catch (IOException e) {
                            log.warn("{}", e.getMessage(), e);
                        }
                    }
                });
            }
        } else if (message instanceof ResetMessage) {
            startTimestamp = -1;
        } else if (log.isDebugEnabled()) {
            log.debug("Ignoring pushed message: {}", message);
        }
    }

    /**
     * Out-of-band control message handler
     *
     * @param source
     *            Source of message
     * @param pipe
     *            Pipe that is used to transmit OOB message
     * @param oobCtrlMsg
     *            OOB control message
     */
    public void onOOBControlMessage(IMessageComponent source, IPipe pipe, OOBControlMessage oobCtrlMsg) {
    }

    /**
     * Initialization
     *
     * @throws IOException
     *             I/O exception
     */
    private void init() throws IOException {
        if (initialized.compareAndSet(false, true)) {
            log.debug("Init: {}", mode);
            // if the path is null, the consumer has been uninitialized
            if (path != null) {
                if (log.isDebugEnabled()) {
                    Path parent = path.getParent();
                    log.debug("Parent abs: {} dir: {}", parent.isAbsolute(), Files.isDirectory(parent));
                }
                if (IClientStream.MODE_APPEND.equals(mode)) {
                    if (Files.notExists(path)) {
                        throw new IOException("File to be appended doesnt exist, verify the record mode");
                    }
                    log.debug("Path: {}\nRead: {} write: {} size: {}", path, Files.isReadable(path), Files.isWritable(path), Files.size(path));
                    writer = new FLVWriter(path, true);
                } else if (IClientStream.MODE_RECORD.equals(mode)) {
                    try {
                        // delete existing file
                        if (Files.deleteIfExists(path)) {
                            log.debug("File deleted");
                        }
                        // ensure parent dirs exist
                        Files.createDirectories(path.getParent());
                        // create the file
                        path = Files.createFile(path);
                    } catch (IOException ioe) {
                        log.error("File creation error: {}", ioe);
                    }
                    if (!Files.isWritable(path)) {
                        throw new IOException("File is not writable");
                    }
                    log.debug("Path: {}\nRead: {} write: {}", path, Files.isReadable(path), Files.isWritable(path));
                    writer = new FLVWriter(path, false);
                } else {
                    try {
                        // delete existing file since we're not recording nor appending
                        if (Files.deleteIfExists(path)) {
                            log.debug("File deleted");
                        }
                    } catch (IOException ioe) {
                        log.error("File creation error: {}", ioe);
                    }
                }
            } else {
                log.warn("Consumer is uninitialized");
            }
            log.debug("Init - complete");
        }
    }

    /**
     * Reset or uninitialize
     */
    public void uninit() {
        if (initialized.get()) {
            log.debug("Uninit");
            if (writer != null) {
                if (writerFuture != null) {
                    try {
                        writerFuture.get();
                    } catch (Exception e) {
                        log.warn("Exception waiting for write result on uninit", e);
                    }
                    if (writerFuture.cancel(false)) {
                        log.debug("Future completed");
                    }
                }
                writerFuture = null;
                // clear the queue
                queue.clear();
                queue = null;
                // close the writer
                writer.close();
                writer = null;
            }
            // clear path ref
            path = null;
        }
    }

    /**
     * Adjust timestamp and write to the file.
     *
     * @param queued
     *            queued data for write
     */
    private final void write(byte dataType, int timestamp, ITag tag) {
        if (tag != null) {
            // only allow blank tags if they are of audio type
            if (tag.getBodySize() > 0 || dataType == ITag.TYPE_AUDIO) {
                try {
                    if (timestamp >= 0) {
                        if (!writer.writeTag(tag)) {
                            log.warn("Tag was not written");
                        }
                    } else {
                        log.warn("Skipping message with negative timestamp");
                    }
                } catch (ClosedChannelException cce) {
                    // the channel we tried to write to is closed, we should not try again on that writer
                    log.error("The writer is no longer able to write to the file: {} writable: {}", path.getFileName(), path.toFile().canWrite());
                } catch (IOException e) {
                    log.warn("Error writing tag", e);
                    if (e.getCause() instanceof ClosedChannelException) {
                        // the channel we tried to write to is closed, we should not try again on that writer
                        log.error("The writer is no longer able to write to the file: {} writable: {}", path.getFileName(), path.toFile().canWrite());
                    }
                }
            }
        }
    }

    /**
     * Whether or not to use a PriorityBlockingQueue or LinkedBlockingQueue for data queue.
     *
     * @param usePriority priority queue or blocking queue
     */
    public void setUsePriority(boolean usePriority) {
        this.usePriority = usePriority;
    }

    /**
     * Amount of time in milliseconds to wait for an offer to be accepted.
     *
     * @param offerTimeout how long to wait for offer acceptance
     */
    public void setOfferTimeout(long offerTimeout) {
        this.offerTimeout = offerTimeout;
    }

    public void setAudioDecoderConfiguration(IRTMPEvent audioConfig) {
        // no-op
    }

    public void setVideoDecoderConfiguration(IRTMPEvent videoConfig) {
        // no-op
    }

    @Override
    public void destroy() throws Exception {
        if (executor != null) {
            executor.shutdown();
        }
    }

}
