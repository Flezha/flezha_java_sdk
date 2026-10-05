# Flezha Java SDK

Java 21 SDK for authoring Flezha compute engines.

**Support tier:**
[tier 1](https://github.com/flezha/flezha/blob/main/docs/publishing/maturity.md#sdk-java),
run by the maintainer in a live deployment, passes the full conformance run in CI and is
permanently supported.

You write tasklets that handle one message at a time, and relays that
declare how those tasklets are connected. A controller then owns everything
around them: placing each relay on a host, persisting per-cell state,
enforcing the delivery guarantee you declared, retrying failures, relocating work
when a host is withdrawn, and reconnecting when a connection drops. Recovery
resumes a cell at the point it reached rather than restarting it, which is the
property the rest of this document keeps returning to.

Functionally at parity with the Rust SDK: every relay shape, durability tier
and delivery guarantee available there is available here.

## Add it

```xml
<dependency>
    <groupId>com.flezha</groupId>
    <artifactId>flezha-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

Java 21 or later. `flezha-sdk-testing` is a separate artifact, with `<scope>test</scope>`.
Nothing runs at compile time: the classes you write are the classes the runtime reads.

## A complete example

Three files, and this structure holds however large the application becomes.

**The tasklet** contains the code invoked once per message. Invocation is
serialised per cell, so two messages for the same key are never handled
concurrently and a tasklet needs no internal locking around its own state;
messages for different keys are handled concurrently, possibly on different
hosts. This tasklet declares no state, so it is a pure function of its input.

```java
package com.example.echo;

import com.flezha.sdk.tasklet.Ctx;
import com.flezha.sdk.tasklet.Tasklet;
import com.flezha.sdk.tasklet.TaskletException;

/** The name is durable: it names this tasklet in saved state. Choose it once. */
@Tasklet.Spec(name = "echo")
public final class Echo implements Tasklet<String, Void, String> {

    @Override
    public void run(String input, Ctx<Void, String> ctx) throws TaskletException {
        ctx.emit("echo: " + input);
    }
}
```

`Tasklet<String, Void, String>` reads input, state, output. The state is `Void`,
so nothing is kept between messages and nothing is journaled. A state class of
your own makes the tasklet durable: `ctx.state()` reads it, and `ctx.change(...)`
records a change, a record whose `applyTo` writes the state. The context also
reads the clock, gives replay-stable ids and registers timers, and
`ctx.part(value)` sends a preview of the output to a listener while `run` is
still working. The relay names this class itself; nothing is generated.

**The relay** declares the graph and holds no logic of its own. A tasklet
cannot be deployed alone, because nothing in the system knows where its messages
originate or where its output belongs. The relay supplies that binding, which
makes it the unit the controller schedules onto a host, restarts after a failure,
relocates when a host is withdrawn, and scales across several hosts.

```java
package com.example.echo;

import com.flezha.sdk.channel.ChannelBuilder;
import com.flezha.sdk.relay.Relay;

public final class EchoRelay implements Relay {

    /** Also durable. Renaming it makes the cluster forget what was running. */
    @Override
    public String factoryId() {
        return "echo";
    }

    @Override
    public void channels(ChannelBuilder c) {
        c.input(String.class, "in").into(Echo.class).output("out");
    }
}
```

The wiring expression reads left to right: declare an entry channel named `in`
carrying strings, route it to the echo tasklet, and terminate that tasklet's
output on the plugin layer under the name `out`. Every call returns the next
handle in the chain, so a graph of any size is expressed as one fluent
expression, and the whole declaration is validated at registration rather than
failing later during processing.

The name an `output` carries is an identity rather than documentation: it is the
channel's name, what a plugin filter matches and what a test feeds or reads.
The no-argument `output()` leaves the channel named after its producing
tasklet, and `named(...)` renames a channel that has no output to name it.
Naming one channel both ways is refused.

**The main** starts a runner: the process that connects to a controller and serves
the relays declared on it.

```java
package com.example.echo;

import com.flezha.sdk.Flezha;

public final class Main {
    public static void main(String[] args) throws Exception {
        Flezha.createRunner("my_app")
                .registerFactory(EchoRelay.class)
                .run();
    }
}
```

`run()` connects and blocks until shutdown, which is either a SIGTERM or a
`close()` from another thread. `start()` returns to the caller instead and leaves
the runner to be closed explicitly.

Declare every relay on the builder before the runner starts. One runner can
serve several, and `registerFactory(...)` is the same call whichever way a class
runs.

## Settings

One `Relay` base carries the settings tree. Each "one of" is a sealed
interface and each nested setting a member of its parent only, so a setting
under the wrong parent does not compile:

```java
@Override
public Run run() {
    return new Run.Steps(Each.PER_INPUT, OnFailure.KEEP_PROGRESS);
}

@Override
public Durable durable() {
    return new Durable.Local(Duration.ofMinutes(10));   // sleep after ten idle minutes
}
```

| Setting | Values | Default |
|---|---|---|
| `run()` | `Run.Flow(exactly)` or `Run.Steps(each, onFailure)` | a flow, at least once |
| `exactly` (flow) | `Exactly.AtLeastOnce()` or `Exactly.EffectivelyOnce(ack, correctionMs, beyondWindow)` | at least once |
| `ack` (effectively once) | `Ack.PerInput()` or `Ack.Checkpoint(every, maxDelayMs, outputs)` | per input |
| `each` (steps) | `ONCE`, `PER_INPUT`, `CHAINED` | `ONCE` |
| `onFailure` (steps) | `UNDO_ALL`, `KEEP_PROGRESS` | `UNDO_ALL` |
| `durable()` | `Durable.Memory()`, `Durable.Local(sleepAfter)`, `Durable.Remote(sleepAfter)` | local; a flow never sleeps, a steps class after six hours |
| `maxDuration()` | a wall-clock stop for either run | none |
| `description()` | shown by `flz relay info` | empty |

The short form, `@Relay` on a tasklet class, takes the same tree as nested
annotations: `@Relay(id = "chat", run = @Run(steps = @Steps(each = Each.PER_INPUT)))`.

Anything Flezha refuses throws one unchecked type, `com.flezha.sdk.FlezhaException`:
a registration, a refused connect, an administration call, a spawn read, and a
misuse such as an emit on a channel the tasklet does not own. Branch on its
`code()`, for example `topology.duplicate_factory_id` or `quota.exceeded`, and read
the values its message names from `details()`.

To run this without a controller, replace the main with `LocalRelay`, which
executes the same declared wiring inside the calling JVM and delivers output
to a callback. Nothing is durable in that mode, and declarations requiring a
controller are refused at startup with the responsible declaration named.

## Where to go

- **Learn the SDK in order:** [`flezha-sdk-examples/`](flezha-sdk-examples/README.md).
  Twelve compiled examples, six forming a ladder from hello world to a tested
  graph, and six covering specific problems you may or may not have. They are
  built by the reactor and most are covered by a test, so an example that drifts
  from the SDK breaks the build rather than misleading you.
- **Look up a type or method:** the generated javadoc. Every concept is
  documented on the type it concerns, so the explanation for why a field cannot
  be assigned directly sits on `Ctx` and `Change`, where a reader encounters the
  problem.

      mvn -f sdk/java/pom.xml -pl flezha-sdk-api javadoc:javadoc

  The output is `flezha-sdk-api/target/site/apidocs/index.html`. The plugin skips
  when its output appears current, so delete that directory if an edit does not
  appear.
- **Run a graph without a controller:** `LocalRelay` executes a declared
  graph in the calling JVM. See
  [`local/`](flezha-sdk-examples/src/main/java/com/flezha/examples/local).
- **Threading model, packaging and cross-SDK comparison:** `docs/sdk/java_sdk.md`.
- **Wire protocol and framing:** `docs/sdk/protocol_spec.md`.

## Capabilities

| Capability | Declare | Gives you |
|---|---|---|
| Durable state | a state class, and `Change` records applied with `ctx.change(...)` | State survives restart, rebuilt from a change log. |
| Effectively-once output | `Exactly.EffectivelyOnce` in `run()` + `.keyed()` channels | State, output and consumed offset become durable in ONE record. A crash cannot advance the offset while losing the output. |
| Checkpointed intervals | `Ack.Checkpoint` in `run()` + `ctx.checkpoint()` | The offset is withheld until the state is coherent, so a half-built window is never persisted. |
| Late-input correction | `correctionMs` on `Exactly.EffectivelyOnce` | A late input rewinds the Cell and replays the slice in event-time order, repairing the earlier result. |
| Dead-letter queue | `.retryMax(n)` on the channel | A poison payload is retried, then parked durably, never silently dropped. |
| Edge transforms | `.map(T.class, f)` / `.filter(p)` / `.flatMap(T.class, f)` on the wiring | Reshape, drop or fan out values without a Tasklet, with no cell, no mailbox and no dispatch hop. |
| Dynamic cell mode | `.keyBy(f)` on the wiring | One Cell per key the stream actually carries, created on first sight, and visible where the graph is declared. |
| Relay-wide document | `c.routeLatest(Doc.class, documents, traffic, keyFn)` | Every key seen on the traffic stream receives each distinct document exactly once, on that key's own next traffic item, the one delivery a keyed tasklet cannot get from a broadcast. One tick of latency per key is the price of only ever emitting keys this member owns. |
| Steps relay transaction | `Run.Steps` in `run()` + `channels(...)` | A graph committed stage by stage: a committed stage is not re-run on resume, which continues at the stage it reached. Failure unwinds through each tasklet's `compensate`, declared by writing it. |
| Durable loop / suspension | `Each.PER_INPUT` + `ctx.timerAfter(d)` | One durable iteration per message; parks when idle, holding its journal, and wakes at its earliest pending timer. |
| Cell retry | `.retryMax(n)` / `.retryBackoffMs(ms)` on the edge that feeds the tasklet | In a steps relay, only the failing cell re-runs; a failed attempt's output is dropped, not double-emitted. |

Each is a declaration the controller must honour, so declare what you need rather
than what sounds reassuring: a correction window sizes the controller's input-log
retention, and a tier without keyed outputs is refused at registration.

## Answering and calling

A channel ends in one output or one `call(...)`. The first output an input
causes answers the outside caller of that input, and still reaches the output's
plugins and listeners. `call(target, Answer.class, deadlineMs)` sends each value,
after the chain's transforms and key, to another relay: `CallTarget.id("pricing")`
names an instance, and `CallTarget.factory("agent")` an instance of a factory, the
one the chain's `keyBy` names (`.create(false)` refuses to start it,
`.channel("turn")` picks its entry channel, `.answerChannel("quotes")` names the
callee's channel whose output answers). The request is written as `output()`
writes it.

The call waits for nothing. Its outcome arrives later as a
`Response<Req, Ans>` on a new entry channel, `<channel>.response`, which must
feed a tasklet, the calling one included. A `Response` carries `request()`,
`answer()` (decoded as `input(Answer.class, ...)` decodes) or `error()` (a
`FlezhaException`; `call.timeout` once the deadline passes), `ok()`, `target()`
(the callee's instance id) and `created()` (whether this call started it).

```java
Stream<String> gateway = c.input(String.class, "in").into(Account.class).named("gateway");
gateway.keyBy(line -> line.split(":")[0])
        .call(CallTarget.factory("agent"), String.class, 30_000)
        .into(Outcome.class);   // Outcome implements Tasklet<Response<String, String>, ?, ?>
```

## Modules

| Module | Purpose |
|---|---|
| `flezha-sdk-api` | Public API: `Flezha`, `Relay` and its settings tree, `Tasklet` and `@Tasklet.Spec`, `Ctx`, `Change`, `StableId`, channel builders, the `@Relay` short form. No native dependency. |
| `flezha-sdk-runtime` | Control plane, data plane, framing, worker pool, reconnect, state persistence, steps class registration. |
| `flezha-sdk-native` | JNI bindings + bundled per-OS `libflz_core_ffi`. |
| `flezha-sdk-testing` | `TaskletTestHarness`, added with `<scope>test</scope>`. |
| `flezha-sdk-examples` | Worked examples. Compiled by the build, so they cannot rot. |
| `flezha-sdk` | Fat-jar aggregator, the published artifact. |

## Build

```bash
mvn -f sdk/java/pom.xml -P native-build package   # portable: bundles all 5 native targets
mvn -f sdk/java/pom.xml -P host-only  package     # fast local iteration (NOT portable)
```

Prerequisites: **JDK 21+**, a **Rust stable toolchain** (for the `flz_core_ffi` binding),
and **[cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild)** for the
native cross-build.

## Test

```bash
mvn -f sdk/java/pom.xml test                    # unit suite, no controller needed
mvn -f sdk/java/pom.xml -P coverage verify      # + JaCoCo
FLEZHA_E2E_RUNTIME=java mix test --include system   # from repo root: the shared conformance suite
```

## Environment

| Variable | Default | Notes |
|---|---|---|
| `FLEZHA_TRANSPORT` | `uds` | `uds` or `quic`. |
| `FLEZHA_QUIC_ADDRESSES` | `127.0.0.1:4433` | QUIC only. Comma list of names or literals, re-resolved every attempt. |
| `FLEZHA_CA_CERT_PATH` | `app_data/certs/ca.pem` | QUIC only. |
| `FLEZHA_SERVER_CERT_PATH` | `app_data/certs/cert.pem` | Server mode only. |
| `FLEZHA_SERVER_KEY_PATH` | `app_data/certs/key.pem` | Server mode only. |
| `FLEZHA_SOCKET_FILES_PATH` | resolved by the socket ladder | UDS only. Set, it wins outright; unset, the ladder walks `$XDG_RUNTIME_DIR/flezha`, `/run/flezha`, `app_data/sockets`. |
| `FLEZHA_RUNNER_ID_PATH` | `/var/lib/flezha-runner/id` | Durable stable identity. Unwritable means a fresh id per boot and lost bindings. |
| `FLEZHA_WORKER_POOL_SIZE` | `availableProcessors()` | Shared runner draining every Cell mailbox. |
| `FLEZHA_LOG_LEVEL` | `warn` | `debug`, `info`, `warn` or `error` for the default logger. Unset, blank and unrecognised all mean `warn`. |
| `FLEZHA_NATIVE_LIB_DIR` | `$TMPDIR` | Where `libflz_core_ffi` is extracted. Set it on containers where `/tmp` is `noexec`. |

## Diagnostics

The SDK writes its own diagnostics to standard error, prefixed `[flezha]`, at
`warn` and above. There is no logging dependency and nothing to add: a fresh
application gets the failure paths and none of the chatter, and
`FLEZHA_LOG_LEVEL` moves the line. To route them into the pipeline the
application already runs, hand the runner a `Logger`; it is a functional
interface, so an adapter is one lambda:

```java
runner.logger((level, format, args) ->
        slf4j.atLevel(Level.valueOf(level.name())).log(format, args));
```

`docs/sdk/java_sdk.md` has the rest: what a supplied output is and is not
filtered by, where the substitution lives, and what a failed write does.

## Further reading

- `docs/sdk/protocol_spec.md`: wire protocol, framing, output identity stamps.
- `docs/resilience/data_durability.md`: where state and output are lost vs not.
- `docs/sdk/state_persistence.md`: Kryo registration and the Change machinery.

## License

Apache-2.0, [`LICENSES/Apache-2.0.txt`](../../LICENSES/Apache-2.0.txt). The
per-path map for the whole repository is [`REUSE.toml`](../../REUSE.toml).
