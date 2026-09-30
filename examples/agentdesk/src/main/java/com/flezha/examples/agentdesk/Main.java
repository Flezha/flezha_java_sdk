package com.flezha.examples.agentdesk;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.FlezhaRunner;

/**
 * Starts the desk's runner: the process that connects to the controller and serves agentdesk's
 * operators, in the {@code agents} namespace.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        FlezhaRunner runner = Flezha.createRunner("agents")
                .registerFactory(AgentOperator.class)
                .start();

        runner.awaitTermination();
    }

    private Main() {}
}
