package org.red5.server.stream.state;

import org.red5.server.stream.PlayEngine;

import java.io.IOException;

public abstract class AbstractPlayEngineState {

    private PlayEngine playEngine;

    public PlayEngine getPlayEngine() {
        return playEngine;
    }

    public void setPlayEngine(PlayEngine playEngine) {
        this.playEngine = playEngine;
    }

    public abstract void play() throws IOException;
}
