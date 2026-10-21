/**
 * The Flezha Java SDK's authoring surface. Every package an author writes against is exported;
 * {@code com.flezha.sdk.internal}, the bridge to the runtime, is exported to the runtime alone.
 */
@SuppressWarnings("module")
module com.flezha.sdk.api {
    requires transitive com.fasterxml.jackson.databind;

    exports com.flezha.sdk;
    exports com.flezha.sdk.authoring;
    exports com.flezha.sdk.channel;
    exports com.flezha.sdk.control;
    exports com.flezha.sdk.relay;
    exports com.flezha.sdk.state;
    exports com.flezha.sdk.tasklet;
    exports com.flezha.sdk.internal to
            flezha.sdk.runtime,
            flezha.sdk.testing;
}
