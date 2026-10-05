package com.flezha.examples.spawn;

import com.flezha.sdk.Flezha;

/** Both relays are declared on one runner: the detector asks, the worker answers. */
public final class Main {

    public static void main(String[] args) throws Exception {
        Flezha.createRunner(args.length > 0 ? args[0] : "my_app")
                .registerFactory(AlertDetectorRelay.class)
                .registerFactory(TriageRelay.class)
                .run();
    }

    private Main() {}
}
