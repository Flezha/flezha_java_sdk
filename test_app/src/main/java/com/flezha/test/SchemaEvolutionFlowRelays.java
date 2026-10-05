package com.flezha.test;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;
import com.flezha.sdk.runtime.state.KryoCodec;
import com.flezha.sdk.state.Change;
import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Twin of the Rust test app's schema-evolution fixtures: the same class either
 * side of a deploy that edits its own state type.
 *
 * <p>Both halves declare the SAME tasklet name, so a relay started under
 * {@code schema_evolution_v1} and restarted under {@code schema_evolution_v2}
 * restores from the very WAL v1 wrote — a deploy, with the two binaries
 * collapsed into one runner for the fixture. Output is {@code total:currency}
 * so the migrated field is observable on the wire.
 */
public final class SchemaEvolutionFlowRelays {

    private SchemaEvolutionFlowRelays() {}

    /** v1: {@code {total}} at the default schema version. */
    public static final class V1 implements Relay {

        @Override
        public String factoryId() {
            return "schema_evolution_v1";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(Task.class).sink("total");
        }

        public static final class CartV1 {
            public long total;
        }

        public static final class AddV1 implements Change<CartV1> {
            public long delta;

            public AddV1() {}

            AddV1(long delta) {
                this.delta = delta;
            }

            @Override
            public void applyTo(CartV1 state) {
                state.total += delta;
            }
        }

        @Tasklet.Spec(name = "evolving_cart")
        public static final class Task implements Tasklet<String, CartV1, byte[]> {

            @Override
            public List<Class<?>> kryoTypes() {
                return List.of(CartV1.class, AddV1.class);
            }

            @Override
            public void run(String input, Ctx<CartV1, byte[]> ctx) throws TaskletException {
                long delta = parse(input);
                long total = ctx.state().total + delta;
                ctx.change(new AddV1(delta));
                ctx.emit((total + ":").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** v2: {@code {total, currency}} at schema version 2, reading down to 1. */
    public static final class V2 implements Relay {

        @Override
        public String factoryId() {
            return "schema_evolution_v2";
        }

        @Override
        public void channels(ChannelBuilder c) {
            c.input(String.class, "input").into(Task.class).sink("total");
        }

        public static final class CartV2 {
            public long total;
            public String currency = "";
        }

        public static final class AddV2 implements Change<CartV2> {
            public long delta;
            public String note = "";

            public AddV2() {}

            AddV2(long delta, String note) {
                this.delta = delta;
                this.note = note;
            }

            @Override
            public void applyTo(CartV2 state) {
                state.total += delta;
            }
        }

        @Tasklet.Spec(name = "evolving_cart", schemaVersion = 2, minReadableSchemaVersion = 1)
        public static final class Task implements Tasklet<String, CartV2, byte[]> {

            /**
             * The v1 type table, in v1's registration order — Kryo ids are
             * positional, so a migration decodes with the ids the old version
             * wrote, not with this version's.
             */
            private static final KryoCodec V1_CODEC =
                    new KryoCodec(List.of(V1.CartV1.class, V1.AddV1.class));

            @Override
            public List<Class<?>> kryoTypes() {
                return List.of(CartV2.class, AddV2.class);
            }

            @Override
            public CartV2 migrateState(int fromVersion, byte[] bytes) {
                if (fromVersion != 1) {
                    throw new IllegalStateException("no migration from " + fromVersion);
                }
                V1.CartV1 old = V1_CODEC.deserialize(bytes, V1.CartV1.class);
                CartV2 migrated = new CartV2();
                migrated.total = old.total;
                migrated.currency = "EUR";
                return migrated;
            }

            @Override
            public AddV2 migrateChange(int fromVersion, byte[] bytes) {
                if (fromVersion != 1) {
                    throw new IllegalStateException("no migration from " + fromVersion);
                }
                V1.AddV1 old = V1_CODEC.deserialize(bytes, V1.AddV1.class);
                return new AddV2(old.delta, "migrated");
            }

            @Override
            public void run(String input, Ctx<CartV2, byte[]> ctx) throws TaskletException {
                long delta = parse(input);
                CartV2 current = ctx.state();
                long total = current.total + delta;
                String currency = current.currency.isEmpty() ? "EUR" : current.currency;
                ctx.change(new AddV2(delta, "live"));
                ctx.emit((total + ":" + currency).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static long parse(String input) {
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
