package com.flezha.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flezha.sdk.Flezha;
import com.flezha.sdk.runtime.operative.StreamOperativeClassRegistry;
import com.flezha.sdk.runtime.workflow.WorkflowOperativeClassRegistry;
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
            "bench_loop_workflow",
            "bounded_mailbox_drop_nothing",
            "bounded_mailbox_drop_newest",
            "bounded_mailbox_drop_oldest",
            "branch_workflow",
            "candle",
            "chat_loop_workflow",
            "chat_rewind_workflow",
            "checkpoint_batched_store",
            "checkpoint_store",
            "coalescing_counter",
            "commit_block_workflow",
            "compare_filter",
            "diamond_workflow",
            "do_nothing",
            "dynamic_cell",
            "echo_workflow",
            "cell_stress",
            "hybrid_channel",
            "keyed_block_workflow",
            "keyed_saga_workflow",
            "merged_input",
            "mixed_operative",
            "optout_commit_block",
            "outbox_block_workflow",
            "partition_relay",
            "persistence_off_counter",
            "poison_probe",
            "reply_block_loop_workflow",
            "resume_probe",
            "retry_workflow",
            "saga_block_workflow",
            "schema_evolution_v1",
            "schema_evolution_v2",
            "session_timeout",
            "sleeping_counter",
            "spawn_once_requester",
            "spawn_requester",
            "stateful_counter",
            "stateless_loop_workflow",
            "static_cell",
            "static_cell_workflow",
            "store_input",
            "sugar_do_nothing",
            "sugar_named_input",
            "sugar_state_only",
            "sugar_static_cell",
            "sugar_store_input",
            "sugar_workflow",
            "sugar_workflow_per_input",
            "sync_durable_workflow",
            "tier_commit_block_workflow",
            "tier_store",
            "transform_chain",
            "typed_once_workflow",
            "undo_outbox_workflow",
            "wedge_probe",
            "workflow_timer_refused");

    @BeforeEach
    void reset() {
        StreamOperativeClassRegistry.reset();
        WorkflowOperativeClassRegistry.reset();
    }

    @Test
    @DisplayName("every factory matches the Rust test app's live set")
    void sameIds() {
        Main.define(Flezha.createRunner("factory_ids_ns"));

        Set<String> live = new HashSet<>(StreamOperativeClassRegistry.factoryIds());
        live.addAll(WorkflowOperativeClassRegistry.factoryIds());
        assertEquals(RUST_FACTORY_IDS, live);
    }
}
