package org.cocfarm.marble;

import java.io.IOException;

/** Each pause, resume or stop invalidates in-flight observations. */
final class RunControl {
    static final class Changed extends IOException {
        Changed() { super("Run controls changed"); }
    }

    interface StartProcess { Process start() throws IOException; }
    private volatile int epoch;

    int epoch() { return epoch; }
    synchronized void invalidate() { epoch++; }

    void require(int observed) throws Changed {
        if (epoch != observed) throw new Changed();
    }

    synchronized Process issue(int observed, StartProcess command) throws IOException {
        require(observed);
        return command.start();
    }
}
