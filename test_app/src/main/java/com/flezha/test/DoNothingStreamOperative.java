package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.operative.StreamOperative;

/**
 * Mirror of the Rust SDK's {@code DoNothingStreamOperative}: echoes the input
 * bytes on the plugin-bound {@code echo} channel so the controller's
 * {@code :on_output} extensions see one event per input. The integration tests
 * using {@code attach_output_msg(do_nothing)} depend on this echo.
 */
public final class DoNothingStreamOperative implements StreamOperative {

    @Override
    public String factoryId() {
        return "do_nothing";
    }

    @Override
    public void channels(ChannelBuilder c) {
        var sDoNothingTasklet = c.input(String.class, "input").into(DoNothingTasklet.class);
        sDoNothingTasklet.sink("echo");
    }
}
