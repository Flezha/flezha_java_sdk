/**
 * Durable per-cell state, and the reason a tasklet never assigns to its own fields.
 *
 * <p>The tasklet here counts the messages a cell has seen, and the count survives a restart.
 * Compared with the {@code echo} example, the tasklet declares a state class in place of
 * {@code Void} and a change type that writes it. That is the whole of what makes a tasklet
 * durable, and the wiring is untouched.
 *
 * <p>The mechanism is a change log rather than object serialisation. Flezha records the ordered
 * list of changes the tasklet applied and reconstructs the state object after a restart by
 * replaying that list, on top of the most recent snapshot where one exists. A field assigned
 * directly from {@code run} never enters the log and is therefore absent from the reconstructed
 * object. This is the single most common mistake against the SDK, and it is silent: a direct
 * assignment behaves correctly for as long as the process stays alive, and is only lost at the
 * moment recovery matters.
 *
 * <p>Three members carry the pattern in
 * {@link com.flezha.examples.state.CountTasklet}. The nested {@code Increment} record is the
 * change, and must carry everything needed to redo the mutation later, which is why its timestamp
 * is a field rather than something read during replay. Its {@code applyTo} is the only method
 * permitted to write state, and is called both when {@code ctx.change} applies it and again for
 * each logged change during reconstruction. {@code run} records changes through
 * {@code ctx.change} and emits, and reads the clock through {@code ctx.now()} rather than the
 * system clock, because it will be executed a second time during recovery and must produce the
 * same result.
 *
 * <p>Nothing is registered by hand: the SDK finds the state class and every change nested in the
 * tasklet or in the state class, and encodes them itself.
 *
 * <p>Declaring a change type also makes a cell readable from outside the pipeline. Nothing
 * further is required: {@code flz state get} returns the current value of any cell of a running
 * relay, which is how a dashboard or an operational query reads a value without waiting for it
 * to reach a sink.
 */
package com.flezha.examples.state;
