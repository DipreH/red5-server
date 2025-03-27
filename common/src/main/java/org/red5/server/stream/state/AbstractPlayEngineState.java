package org.red5.server.stream.state;

import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.messaging.IMessageInput;
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
}
