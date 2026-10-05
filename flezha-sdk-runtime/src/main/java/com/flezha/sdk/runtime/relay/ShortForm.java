package com.flezha.sdk.runtime.relay;

import com.flezha.sdk.FlezhaException;
import com.flezha.sdk.authoring.Relay.Checkpoint;
import com.flezha.sdk.authoring.Relay.Durable;
import com.flezha.sdk.authoring.Relay.EffectivelyOnce;
import com.flezha.sdk.authoring.Relay.Level;
import com.flezha.sdk.authoring.Relay.Run;
import com.flezha.sdk.authoring.Relay.Steps;
import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.channel.EdgeStream;
import com.flezha.sdk.channel.OverflowPolicy;
import com.flezha.sdk.channel.Stream;
import com.flezha.sdk.internal.ShortFormDeclaration;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.tasklet.Tasklet;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import javax.annotation.Nonnull;

/**
 * The short form, read at registration: a tasklet class annotated
 * {@link com.flezha.sdk.authoring.Relay @Relay} becomes a relay whose topology wires the
 * entry channel into the class and, when a sink is written, the class to that plugin sink, under
 * the settings tree the annotation writes.
 */
final class ShortForm {

    /** The unwritten value of the annotation's numeric settings. */
    private static final long NONE = com.flezha.sdk.authoring.Relay.NONE;

    private ShortForm() {}

    /** The relay {@code cls}'s short form {@code d} declares. */
    @Nonnull
    static Relay of(@Nonnull Class<?> cls, @Nonnull ShortFormDeclaration d) {
        TaskletModel model = refuse(cls, d);
        Wiring wiring = new Wiring(cls, model.inputType(), d);
        Relay.Run run = run(d.id(), d.settings().run());
        Relay.Durable durable = durable(d.id(), d.settings().durable());
        long maxDurationMs = d.settings().maxDurationMs();
        return new Relay() {
            @Override
            public String factoryId() {
                return d.id();
            }

            @Override
            public String description() {
                return d.settings().description();
            }

            @Override
            public Optional<Duration> maxDuration() {
                return maxDurationMs == NONE ? Optional.empty() : Optional.of(Duration.ofMillis(maxDurationMs));
            }

            @Override
            public Relay.Run run() {
                return run;
            }

            @Override
            public Relay.Durable durable() {
                return durable;
            }

            @Override
            public void channels(ChannelBuilder c) {
                wiring.declare(c);
            }
        };
    }

    private static Relay.Run run(String id, Run run) {
        oneOf(id, "run", "flow | steps", run.flow().length, run.steps().length);
        if (run.steps().length == 1) {
            Steps steps = run.steps()[0];
            return new Relay.Run.Steps(steps.each(), steps.onFailure());
        }
        if (run.flow().length == 0 || run.flow()[0].effectivelyOnce().length == 0) {
            return Relay.Run.flow();
        }
        oneOf(id, "exactly", "at_least_once | effectively_once", run.flow()[0].effectivelyOnce().length);
        EffectivelyOnce tier = run.flow()[0].effectivelyOnce()[0];
        oneOf(id, "ack", "per_input | checkpoint", tier.checkpoint().length);
        Relay.Ack ack = tier.checkpoint().length == 0 ? new Relay.Ack.PerInput() : checkpoint(tier.checkpoint()[0]);
        OptionalLong correction =
                tier.correctionMs() == NONE ? OptionalLong.empty() : OptionalLong.of(tier.correctionMs());
        return new Relay.Run.Flow(new Relay.Exactly.EffectivelyOnce(ack, correction, tier.beyondWindow()));
    }

    private static Relay.Ack checkpoint(Checkpoint checkpoint) {
        return new Relay.Ack.Checkpoint(
                checkpoint.every() == NONE ? OptionalInt.empty() : OptionalInt.of((int) checkpoint.every()),
                checkpoint.maxDelayMs() == NONE ? OptionalLong.empty() : OptionalLong.of(checkpoint.maxDelayMs()),
                checkpoint.outputs());
    }

    private static Relay.Durable durable(String id, Durable durable) {
        oneOf(
                id,
                "durable",
                "false | local | remote",
                durable.value() ? 0 : 1,
                durable.local().length,
                durable.remote().length);
        if (!durable.value()) {
            return new Relay.Durable.Memory();
        }
        if (durable.remote().length == 1) {
            return new Relay.Durable.Remote(sleepAfter(durable.remote()[0]));
        }
        return durable.local().length == 1
                ? new Relay.Durable.Local(sleepAfter(durable.local()[0]))
                : Relay.Durable.local();
    }

    private static Relay.Durable.SleepAfter sleepAfter(Level level) {
        if (level.sleepAfterMs() == Level.RUN_DEFAULT) {
            return new Relay.Durable.SleepAfter.RunDefault();
        }
        return level.sleepAfterMs() == Level.NEVER
                ? new Relay.Durable.SleepAfter.Never()
                : new Relay.Durable.SleepAfter.After(Duration.ofMillis(level.sleepAfterMs()));
    }

    /**
     * Refuse a "one of" with more than one branch written: an annotation holds its branches as
     * arrays, which the tree's sum type cannot, so this is the one place the short form checks it.
     */
    private static void oneOf(String id, String setting, String expected, int... written) {
        int total = 0;
        for (int count : written) {
            total += count;
        }
        if (total > 1) {
            throw FlezhaException.of(
                    "topology.invalid_setting",
                    Map.of(
                            "declaration",
                            "relay '" + id + "'",
                            "setting",
                            setting,
                            "value",
                            total + " branches",
                            "expected",
                            expected));
        }
    }

    /** The class's model, once every short-form refusal has passed. */
    private static TaskletModel refuse(Class<?> cls, ShortFormDeclaration d) {
        if (!Tasklet.class.isAssignableFrom(cls)) {
            throw FlezhaException.of(
                    "topology.short_form_not_a_tasklet",
                    Map.of("class", cls.getName(), "annotation", ShortFormDeclaration.ANNOTATION));
        }
        if (Relay.class.isAssignableFrom(cls)) {
            throw FlezhaException.of("topology.short_form_with_topology", Map.of("factory_id", d.id()));
        }
        if (d.id().isEmpty()) {
            throw FlezhaException.of("topology.missing_factory_id", Map.of("relay", cls.getName()));
        }
        Tasklet.Spec spec = cls.getAnnotation(Tasklet.Spec.class);
        if (spec != null && !spec.name().equals(d.id())) {
            throw FlezhaException.of(
                    "topology.short_form_name_mismatch",
                    Map.of("class", cls.getName(), "factory_id", d.id(), "name", spec.name()));
        }
        TaskletModel model = TaskletModel.read(cls, null);
        if (!d.sink().isEmpty() && model.typesResolved() && model.outputType() == Void.class) {
            throw FlezhaException.of("topology.sink_without_output", Map.of("factory_id", d.id()));
        }
        if (!d.onFull().isEmpty() && overflow(d.onFull()) == null) {
            throw FlezhaException.of(
                    "topology.invalid_mode", Map.of("factory_id", d.id(), "knob", "onFull", "value", d.onFull()));
        }
        return model;
    }

    private static OverflowPolicy overflow(String onFull) {
        for (OverflowPolicy policy : OverflowPolicy.values()) {
            if (policy.name().equals(onFull.toUpperCase(Locale.ROOT))) {
                return policy;
            }
        }
        return null;
    }

    /** The one-tasklet topology: the entry channel into the class, and the class to its sink. */
    private record Wiring(Class<?> cls, Class<?> inputType, ShortFormDeclaration d) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        void declare(ChannelBuilder c) {
            EdgeStream entry = c.input(inputType, d.input().isEmpty() ? "input" : d.input());
            if (!d.onFull().isEmpty()) {
                entry = entry.onFull(overflow(d.onFull()));
            }
            Stream out = entry.into((Class) cls);
            if (!d.sink().isEmpty()) {
                out.sink(d.sink());
            }
        }
    }
}
