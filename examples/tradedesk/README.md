# Tradedesk: the Java project for guides Part 2

The toy trader the [Part 2 guides](../../../../docs/guides/guides.md) build, as a
standalone project. Nothing here depends on the Flezha source tree: the
`pom.xml` names one released coordinate, the way your own project would.

So far it holds the runner and the paper-order workflow. The guides add an
operative at a time, and each one is registered in `Main.java` before `start()`.

| File | What is in it |
|---|---|
| `Main.java` | the runner: this process joins the `trading` namespace and serves the desk's operatives |
| `PaperOrderOperative.java` | the workflow: the entry channel, the three steps in order, and the channels their output leaves on. The order an instance is started with is the `OrderTicket` record here |
| `ReserveFunds.java` | step one: puts the money aside, and releases it again if the order is undone |
| `PlaceOrder.java` | step two: places the paper order, and cancels it if the order is undone |
| `ConfirmFill.java` | step three: books the fill, and refuses an order above the desk's limit |

What a step remembers, and the change that records it, sit inside the step's own
class, because nothing else uses them.

## Run it

You need a controller running, the `trading` namespace created and a key for it
([`docs/guides/tradedesk/authorization.md`](../../../../docs/guides/tradedesk/authorization.md)
and
[`namespaces.md`](../../../../docs/guides/tradedesk/namespaces.md)).

```sh
mvn -q package
FLEZHA_NAMESPACE_SECRET=<trading key> java -jar target/tradedesk.jar
```

It blocks until shutdown, which is a SIGTERM or a `close()` from another thread.
The namespace is the first argument and defaults to `trading`.

From another terminal, the controller lists it:

```sh
flz runner list --namespace trading
```

## Start the workflow

One instance is one order. `--init-values` carries the order, and the file sink
attached at the start writes down everything the instance emits:

```sh
flz operative start paper_order --id order_1 --kind workflow --namespace trading \
  --init-values '{"symbol": "AAPL", "quantity": 10}' \
  --plugins '[{"id":"order_1_writer","provider":"operative_file_sink","operative_id":"order_1","opts":{"path":"order_1.txt"},"hooks":[["on_output",{}]]}]'
```

```sh
cat app_data/files/order_1.txt
```

```
res-1: AAPL 10
ord-2: AAPL 10
filled: AAPL 10
```

One line per step: the money is reserved, the order is placed, the fill is
booked. The instance then completes on its own, which is why
`flz operative list` no longer shows it and `flz operative listen` has nothing
left to attach to.

## Watch it undo itself

The desk books at most 100 at a time, so a larger order is refused by the last
step. Start one the same way, with a quantity above the limit:

```sh
flz operative start paper_order --id order_2 --kind workflow --namespace trading \
  --init-values '{"symbol": "AAPL", "quantity": 500}' \
  --plugins '[{"id":"order_2_writer","provider":"operative_file_sink","operative_id":"order_2","opts":{"path":"order_2.txt"},"hooks":[["on_output",{}]]}]'
```

```sh
cat app_data/files/order_2.txt
```

```
res-4: AAPL 500
ord-5: AAPL 500
cancelled ord-5
released res-4
```

The first two steps ran, the third refused the order, and after its retries
were spent the instance ended abnormally. Every step that had finished was then
undone, in reverse: the order was cancelled, then the money was released. The
runner's terminal shows the retries that came first:

```
[flezha] workflow order_2 procedure confirm_fill entity - attempt 1/3 failed (retrying in 386 ms): the desk books at most 100 a time, and this is 500
```

## Building from a checkout

The SDK is not on Maven Central yet, so `com.flezha:flezha-sdk:0.1.0` has to be
in your local Maven repository before this resolves. From the repository root:

```sh
scripts/build-native.sh --output sdk/java/flezha-sdk-native/src/main/resources/native
mvn -f sdk/java/pom.xml -DskipTests install
```

That is a one-time step. Once the SDK is published, it goes away and the command
above is all you need.

## Where to go next

The echo in [`../echo`](../echo/README.md) is the Part 1 project, and
[`docs/guides/runner_deployment.md`](../../../../docs/guides/runner_deployment.md)
walks through what a procedure, an operative and a runner each are.
