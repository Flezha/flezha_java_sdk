/**
 * Partitioning one stream into many independent cells, and reshaping values on the wiring.
 *
 * <p>Each incoming trade is folded into four timeframes, and every distinct symbol-and-timeframe
 * pair maintains its own running candle with its own state, processed in parallel with the others.
 * A hundred symbols across four timeframes is four hundred independent cells, none of which is
 * declared in advance: a cell is created on first sight of its key.
 *
 * <p>{@link com.flezha.examples.keyed.CandleOperative} carries the lesson. Three transforms run on
 * the wiring itself before any user code is invoked: {@code filter} discards values, {@code flatMap}
 * expands one value into several, and {@code keyBy} assigns each result to a cell. These execute
 * without a cell, a mailbox or a dispatch hop, which makes them the appropriate place to reshape
 * a value and the wrong place to perform substantial work.
 *
 * <p>The ordering is significant. {@code keyBy} is total: it always names a cell and has no way
 * to decline one, so discarding unwanted values is {@code filter}'s responsibility and must happen
 * before the key is computed.
 *
 * <p>{@link com.flezha.examples.keyed.CandleTasklet} declares no keying of any kind. It is placed
 * in dynamic mode because a keyed edge feeds it, so the partitioning is visible where the graph is
 * declared rather than buried in the tasklet. This keeps the tasklet reusable under a different
 * keying scheme without modification.
 */
package com.flezha.examples.keyed;
