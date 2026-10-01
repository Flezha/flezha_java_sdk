package com.flezha.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.runtime.operator.OperatorClassRegistry;
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
            "bench_loop_steps",
            "bounded_mailbox_drop_nothing",
            "bounded_mailbox_drop_newest",
            "bounded_mailbox_drop_oldest",
            "branch_steps",
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
            "chat_loop_steps",
            "chat_rewind_steps",
            "checkpoint_batched_store",
            "checkpoint_store",
            "coalescing_counter",
            "commit_block_steps",
            "compare_filter",
            "diamond_steps",
            "do_nothing",
            "dynamic_cell",
            "echo_steps",
            "event_timer_steps",
            "failed_stage_steps",
            "failing_turn_undo_all_steps",
            "failing_turn_loop_steps",
            "cell_stress",
            "hybrid_channel",
            "keyed_block_steps",
            "keyed_loop_steps",
            "keyed_saga_steps",
            "merged_input",
            "mixed_operator",
            "optout_commit_block",
            "outbox_block_steps",
            "part_echo",
            "part_echo_steps",
            "partition_relay",
            "persistence_off_counter",
            "poison_probe",
            "reply_block_loop_steps",
            "resume_probe",
            "retry_steps",
            "saga_block_steps",
            "schema_evolution_v1",
            "schema_evolution_v2",
            "session_timeout",
            "sleeping_counter",
            "spawn_hold_requester",
            "spawn_once_requester",
            "spawn_requester",
            "stateful_counter",
            "stateless_loop_steps",
            "static_cell",
            "static_cell_steps",
            "store_input",
            "sugar_do_nothing",
            "sugar_named_input",
            "sugar_state_only",
            "sugar_static_cell",
            "sugar_store_input",
            "sugar_steps",
            "sugar_steps_per_input",
            "sync_durable_steps",
            "tier_chain",
            "tier_commit_block_steps",
            "tier_store",
            "timer_loop_steps",
            "transform_chain",
            "typed_once_steps",
            "undo_outbox_steps",
            "wedge_probe");

    @BeforeEach
    void reset() {
        OperatorClassRegistry.reset();
        OperatorClassRegistry.reset();
    }

    @Test
    @DisplayName("every factory matches the Rust test app's live set")
    void sameIds() {
        Main.define(Flezha.createRunner("factory_ids_ns"));

        Set<String> live = new HashSet<>(OperatorClassRegistry.factoryIds());
        live.addAll(OperatorClassRegistry.factoryIds());
        assertEquals(RUST_FACTORY_IDS, live);
    }
}
