package org.red5.server.stream.state;

import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IPlayItem;
import org.red5.server.messaging.IMessageInput;
import org.red5.server.stream.PlayEngine;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;
import java.io.IOException;

public abstract class AbstractPlayEngineState {

    private PlayEngine playEngine;
    protected Logger log;
    public PlayEngine getPlayEngine() {
        return playEngine;
    }

    public void setPlayEngine(PlayEngine playEngine) {
        this.playEngine = playEngine;
    }

    public abstract void play(IPlayItem playItem, IMessageInput in, IScope thisScope, boolean withReset) throws IOException;

    public abstract void sendMessage(RTMPMessage messageIn);
}
