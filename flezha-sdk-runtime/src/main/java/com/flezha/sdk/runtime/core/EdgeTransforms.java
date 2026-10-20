package com.flezha.sdk.runtime.core;

import com.flezha.sdk.runtime.relay.ConsumerEdge;
import com.flezha.sdk.runtime.relay.EdgeYield;
import java.util.List;

/**
 * Applying one consumer edge's fused transform closure to one value: the
 * values it yields, or the value itself on an edge that declares no transform.
 *
 * <p>Three callers evaluate this rule — {@link CellRouting#valuesForCell}
 * at delivery, {@link CellRouting#prepare} when the core asks a payload for
 * its values, keys or event time, and {@link EmitContext} producer-side. The
 * value a handler sees has crossed the chain exactly once: a flow producer
 * evaluates the edge as its activation completes, the prepare crossing for a
 * keyed consumer's entry payload, and delivery only for the payloads neither
 * transformed.
 */
final class EdgeTransforms {

    private EdgeTransforms() {}

    static List<Object> valuesOf(ConsumerEdge edge, Object value) {
        EdgeYield yielded = edge.transformValue(value);
        return yielded == null ? List.of(value) : yielded.values();
    }
}
