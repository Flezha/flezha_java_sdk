package com.flezha.examples.echo;

import com.flezha.sdk.authoring.Procedure;

/**
 * A processing stage of the operative's graph, invoked once per message. This one prefixes the
 * input, and what {@code run} returns is emitted.
 *
 * <p>The annotation processor generates {@code EchoProcedure} from this class at compile time,
 * reading the input and output types from the {@code run} signature. The operative names that
 * generated class. {@code run} takes no state parameter, so nothing is kept between messages and
 * nothing is journaled.
 *
 * <p>{@code id} is the procedure's stable identity. State is filed under it, so changing it later
 * declares a different procedure rather than renaming this one.
 *
 * <p>Where the result goes is not decided here. The operative decides that, so the graph can be
 * rewired without touching this class.
 */
@Procedure(id = "echo")
public final class Echo {

    public String run(String input) {
        return "echo: " + input;
    }
}
