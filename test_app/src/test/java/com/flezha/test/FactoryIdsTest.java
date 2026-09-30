package com.flezha.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.runtime.operator.UnboundedOperatorClassRegistry;
import com.flezha.sdk.runtime.bounded.BoundedOperatorClassRegistry;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the factory ids this app advertises to the set {@code test/apps/rust_runner}
 * advertises — the cross-SDK fixture contract. A Java fixture without a Rust twin
 * (or a twin under a different id) fails here rather than at an e2e that silently
 * skips the Java arm.
 */
class FactoryIdsTest {

    /** The Rust test app's live factory set, verbatim. */
    private static final Set<String> RUST_FACTORY_IDS = Set.of(
            "bench_chain_1",
            "bench_chain_16",
            "bench_chain_4",
            "bench_chain_optout_4",
            "bench_checkpoint",
            "bench_echo",
            "bench_keyed",
            "bench_keyed_nopersist",
            "bench_loop_bounded",
            "bounded_mailbox_drop_nothing",
            "bounded_mailbox_drop_newest",
            "bounded_mailbox_drop_oldest",
            "branch_bounded",
            "call_agent",
            "call_downstream",
            "call_echo",
            "call_gateway",
            "call_orders",
            "call_pricing",
            "call_split",
            "call_tool_session",
            "call_turn",
            "candle",
            "chat_loop_bounded",
            "chat_rewind_bounded",
            "checkpoint_batched_store",
            "checkpoint_store",
            "coalescing_counter",
            "commit_block_bounded",
            "compare_filter",
            "diamond_bounded",
            "do_nothing",
            "dynamic_cell",
            "echo_bounded",
            "event_timer_bounded",
            "failed_stage_bounded",
            "failing_turn_all_or_nothing_bounded",
            "failing_turn_loop_bounded",
            "cell_stress",
            "hybrid_channel",
            "keyed_block_bounded",
            "keyed_loop_bounded",
            "keyed_saga_bounded",
            "merged_input",
            "mixed_operator",
            "optout_commit_block",
            "outbox_block_bounded",
            "part_echo",
            "part_echo_bounded",
            "partition_relay",
            "persistence_off_counter",
            "poison_probe",
            "reply_block_loop_bounded",
            "resume_probe",
            "retry_bounded",
            "saga_block_bounded",
            "schema_evolution_v1",
            "schema_evolution_v2",
            "session_timeout",
            "sleeping_counter",
            "spawn_hold_requester",
            "spawn_once_requester",
            "spawn_requester",
            "stateful_counter",
            "stateless_loop_bounded",
            "static_cell",
            "static_cell_bounded",
            "store_input",
            "sugar_do_nothing",
            "sugar_named_input",
            "sugar_state_only",
            "sugar_static_cell",
            "sugar_store_input",
            "sugar_bounded",
            "sugar_bounded_per_input",
            "sync_durable_bounded",
            "tier_chain",
            "tier_commit_block_bounded",
            "tier_store",
            "timer_loop_bounded",
            "transform_chain",
            "typed_once_bounded",
            "undo_outbox_bounded",
            "wedge_probe");

    @BeforeEach
    void reset() {
        UnboundedOperatorClassRegistry.reset();
        BoundedOperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("every factory matches the Rust test app's live set")
    void sameIds() {
        Main.define(Flezha.createRunner("factory_ids_ns"));

        Set<String> live = new HashSet<>(UnboundedOperatorClassRegistry.factoryIds());
        live.addAll(BoundedOperatorClassRegistry.factoryIds());
        assertEquals(RUST_FACTORY_IDS, live);
    }
}
