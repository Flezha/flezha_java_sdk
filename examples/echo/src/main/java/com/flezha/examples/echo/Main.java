package com.flezha.examples.echo;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.FlezhaRunner;
import com.flezha.sdk.admin.Admin;
import com.flezha.sdk.admin.OperatorSpec;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Starts a runner: a process that connects to the controller and serves your operators.
 *
 * <p>Declare every operator you want served before calling {@code run()}. One runner can serve
 * several, and {@code registerFactory(...)} is the same call for the steps run.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // run() is start() followed by awaitTermination(). Splitting them keeps the handle,
        // which is the only way to reach admin(), and still blocks until shutdown.
        FlezhaRunner runner = Flezha.createRunner(args.length > 0 ? args[0] : "default")
                .registerFactory(EchoOperator.class)
                .start();

        // Everything `flz operator` and `flz plugin` do is also a method here. Uncomment to
        // drive this namespace from the application instead of from a terminal:
        //
        // Admin admin = runner.admin();
        // admin.startOperator(OperatorSpec.of("echo").id("my_echo"));
        //
        // ObjectNode sink = JsonNodeFactory.instance.objectNode();
        // sink.put("id", "writer");
        // sink.put("provider", "operator_file_sink");
        // sink.put("operator_id", "my_echo");
        // sink.putObject("opts").put("path", "echo-out.txt");
        // sink.putArray("hooks").addArray().add("on_output").addObject();
        // admin.enablePlugin("writer", sink);
        //
        // admin.send("my_echo", "hello world", "in");
        //
        // docs/sdk/admin.md has the whole surface. An enforced controller refuses all of it until
        // `flz role bind runner@default app default`.

        runner.awaitTermination();
    }

    private Main() {}
}
