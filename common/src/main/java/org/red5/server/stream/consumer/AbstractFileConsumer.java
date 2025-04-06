package org.red5.server.stream.consumer;

import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IClientStream;
import org.red5.server.api.stream.IStreamFilenameGenerator;
import org.red5.server.messaging.IPipeConnectionListener;
import org.red5.server.messaging.PipeConnectionEvent;
import org.red5.server.stream.DefaultStreamFilenameGenerator;
import org.red5.server.util.ScopeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class AbstractFileConsumer implements IPipeConnectionListener {
    protected static final Logger log = LoggerFactory.getLogger(FileConsumer.class);
    /**
     * Scope
     */
    protected IScope scope;
    /**
     * Path
     */
    protected Path path;
    /**
     * Operation mode
     */
    protected String mode = "none";
    /**
     * Threshold / size for the queue.
     */
    protected int queueThreshold;
    /**
     * Whether or not to wait until a video keyframe arrives before writing video.
     */
    protected boolean waitForVideoKeyframe = true;

    public AbstractFileConsumer(){
    }

    public AbstractFileConsumer(IScope scope, File file) {
        this.scope = scope;
        this.path = file.toPath();
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
    public AbstractFileConsumer(IScope scope, String fileName, String mode) {
        this.scope = scope;
        this.mode = mode;
        setupOutputPath(fileName);
    }
    /**
     * Pipe connection event handler
     *
     * @param event
     *            Pipe connection event
     */
    @SuppressWarnings("incomplete-switch")
    public void onPipeConnectionEvent(PipeConnectionEvent event) {
        switch (event.getType()) {
            case CONSUMER_CONNECT_PUSH:
                if (event.getConsumer() == this) {
                    Map<String, Object> paramMap = event.getParamMap();
                    if (paramMap != null) {
                        mode = (String) paramMap.get("mode");
                    }
                }
                break;
        }
    }

    /**
     * Sets up the output file path for writing.
     *
     * @param name output filename to use
     */
    public void setupOutputPath(String name) {
        // get stream filename generator
        IStreamFilenameGenerator generator = (IStreamFilenameGenerator) ScopeUtils.getScopeService(scope, IStreamFilenameGenerator.class, DefaultStreamFilenameGenerator.class);
        // generate file path
        String filePath = generator.generateFilename(scope, name, ".flv", IStreamFilenameGenerator.GenerationType.RECORD);
        this.path = generator.resolvesToAbsolutePath() ? Paths.get(filePath) : Paths.get(System.getProperty("red5.root"), "webapps", scope.getContextPath(), filePath);
        // if append was requested, ensure the file we want to append exists (append==record)
        File appendee = getFile();
        if (IClientStream.MODE_APPEND.equals(mode) && !appendee.exists()) {
            try {
                if (appendee.createNewFile()) {
                    log.debug("New file created for appending");
                } else {
                    log.debug("Failure to create new file for appending");
                }
            } catch (IOException e) {
                log.warn("Exception creating replacement file for append", e);
            }
        }
    }

    /**
     * Sets the scope for this consumer.
     *
     * @param scope
     *            scope
     */
    public void setScope(IScope scope) {
        this.scope = scope;
    }

    /**
     * Sets the file we're writing to.
     *
     * @param file file
     */
    public void setFile(File file) {
        path = file.toPath();
    }

    /**
     * Returns the file.
     *
     * @return file
     */
    public File getFile() {
        return path.toFile();
    }

    /**
     * Sets the threshold for the queue. When the threshold is met a worker is spawned to empty the sorted queue to the writer.
     *
     * @param queueThreshold
     *            number of items to queue before spawning worker
     */
    public void setQueueThreshold(int queueThreshold) {
        this.queueThreshold = queueThreshold;
    }

    /**
     * Returns the size of the delayed writing queue.
     *
     * @return queue length
     */
    public int getQueueThreshold() {
        return queueThreshold;
    }

    /**
     * Whether or not the queue should be utilized.
     *
     * @return true if using the queue, false if sending directly to the writer
     */
    @Deprecated
    public boolean isDelayWrite() {
        return true;
    }

    /**
     * Sets whether or not to use the queue.
     *
     * @param delayWrite
     *            true to use the queue, false if not
     */
    @Deprecated
    public void setDelayWrite(boolean delayWrite) {
    }

    /**
     * Whether or not to wait for the first keyframe before processing video frames.
     *
     * @param waitForVideoKeyframe wait for a key frame or not
     */
    public void setWaitForVideoKeyframe(boolean waitForVideoKeyframe) {
        log.debug("setWaitForVideoKeyframe: {}", waitForVideoKeyframe);
        this.waitForVideoKeyframe = waitForVideoKeyframe;
    }

    /**
     * Sets the recording mode.
     *
     * @param mode
     *            either "record" or "append" depending on the type of action to perform
     */
    public void setMode(String mode) {
        this.mode = mode;
    }
}
