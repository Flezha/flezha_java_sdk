/**
 * Channel and unbounded declarations: the graph connecting entry points, tasklets and sinks.
 *
 * <p>These types are declared inside an operator's {@code channels(ChannelBuilder)} method, the
 * one graph body both operator kinds write, and are never constructed directly.
 * {@link com.flezha.sdk.channel.ChannelBuilder} is the entry point, and every call returns the next
 * handle in the chain, so a complete graph is expressed as one fluent expression. The declaration
 * is read once at registration and validated as a whole; a malformed graph is refused there rather
 * than failing during processing.
 *
 * <p>{@link com.flezha.sdk.channel.ChannelBuilder#input(Class, String)} declares an entry channel
 * fed by the controller. A {@code byte[]} payload is delivered unchanged, a {@code String} is
 * decoded as UTF-8, and any other type is deserialised at the entry edge with the SDK's default
 * JSON codec. An overload accepts an explicit parser for other wire formats, which must be
 * deterministic; a parse failure is classified like a failing invocation and follows the same
 * retry and dead-letter path.
 *
 * <p>{@link com.flezha.sdk.channel.Stream} is a tasklet's output handle. {@code named} labels the
 * channel, and that label is the identity the rest of the system uses, including sink registration
 * and test feeds; unlabelled, a channel takes the producing tasklet's name. {@code into} wires a
 * further tasklet as a consumer, which is how multi-stage graphs are expressed. {@code sink}
 * attaches a plugin, {@code keyed} declares that emits carry a natural key supplied through
 * {@code emitKeyed}. A durable bounded operator journals the handoff at every stage boundary of
 * its graph; the payload codec it uses there is derived by the runtime and is declared nowhere.
 *
 * <p>{@link com.flezha.sdk.channel.EdgeStream} carries the transforms applied on the wiring itself:
 * {@code filter} discards values, {@code map} and {@code flatMap} reshape or expand them, and
 * {@code keyBy} assigns each value to a cell. These execute without a cell, a mailbox or a
 * dispatch hop, which makes them the cheap place to reshape a value and the wrong place to do
 * substantial work. {@code keyBy} is total: it always names a cell and never declines, so
 * discarding is {@code filter}'s responsibility and must precede it.
 *
 * <p>{@link com.flezha.sdk.channel.Merge} opens a merged subscription, in which two or more source
 * channels each supply a mapping into one sum type consumed by a single tasklet.
 * {@link com.flezha.sdk.channel.RoutedDocument} supports operator-wide document delivery, in which
 * every key observed on a traffic channel receives each distinct document exactly once, on that
 * key's own next traffic item. {@link com.flezha.sdk.channel.RetryPolicy},
 * {@link com.flezha.sdk.channel.OverflowPolicy} and
 * {@link com.flezha.sdk.channel.BeyondWindowPolicy} declare, respectively, the retry ladder before
 * a payload is parked, the behaviour of a full mailbox, and the treatment of a message arriving
 * outside the correction window.
 */
@javax.annotation.ParametersAreNonnullByDefault
package com.flezha.sdk.channel;
