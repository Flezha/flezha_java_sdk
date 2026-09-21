/**
 * Tasklet-requested spawning: a stream tasklet asking the cluster for a workflow worker.
 *
 * <p>A detector watching a stream identifies something warranting substantial work and wants a
 * worker assigned to it, without an external dispatcher and without becoming one itself.
 *
 * <p>{@code ctx.spawnWorkflow} with {@link com.flezha.sdk.tasklet.SpawnOptions} naming an id is
 * spawn-or-deliver against that single instance identifier. The first alert for a subject starts a
 * worker with that alert as its first input; subsequent alerts for the same subject are delivered to
 * the worker already handling it. Concurrent requests, whether from other cells or other hosts,
 * are resolved controller-side onto exactly one instance, so there is no check-then-create race for
 * the author to defend against. {@link com.flezha.sdk.tasklet.IfRunning} selects that behaviour.
 *
 * <p>Without an id, {@code ctx.spawnWorkflow("triage", payload)} starts a new instance under an id
 * the controller generates, and never delivers into a running one. A spawn reissued after a lost or
 * timed-out answer then starts a second instance, and a replayed invocation answers an empty id,
 * because the generated one existed only in the live run's answer. Pass an id when at most one
 * instance, or the id on replay, matters.
 *
 * <p>The call is fire-and-forget. It returns a handle before the controller has decided anything,
 * and a refusal, whether from authorization, a quota, an unknown factory or an unreachable
 * controller, is reported by a read of that handle ({@code await()} or {@code tryResult()}) rather
 * than failing the calling tasklet. This is deliberate: a detector that stops detecting because a
 * worker could not be started is a worse outcome than a worker that was not started. A handle is
 * read inside the invocation that made it; a failure nobody read logs itself when the invocation
 * ends.
 *
 * <p>With authorization on, the default in a release, the namespace's {@code runner@<namespace>}
 * principal needs the {@code spawner} role, or every spawn is refused:
 * {@code flz role bind runner@<namespace> spawner <namespace>}. The role also lets this runner's
 * {@code admin()} start and send to operatives in its namespace, and nothing else.
 *
 * <p>{@link com.flezha.examples.spawn.Main} declares both operatives on one runner, since the
 * requesting stream operative and the workflow operative it requests are ordinary declarations with
 * no special relationship beyond the factory id.
 */
package com.flezha.examples.spawn;
