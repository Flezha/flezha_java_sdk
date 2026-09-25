package com.flezha.sdk.runtime.bounded;

import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;

/**
 * Test double for the runtime's bounded class registry, standing at the name
 * {@code FlezhaRunner} resolves it by. The statics are the unbounded double's,
 * inherited, because {@code RuntimeBridge} finds a public static method on a
 * superclass by name as readily as on the class itself.
 */
public final class BoundedOperatorClassRegistry extends UnboundedOperatorClassRegistry {

    private BoundedOperatorClassRegistry() {}
}
