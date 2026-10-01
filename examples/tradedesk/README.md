# Tradedesk: the Java project for guides Part 2

The toy trader the [Part 2 guides](../../../../docs/guides/guides.md) build, as a
standalone project. Nothing here depends on the Flezha source tree: the
`pom.xml` names one released coordinate, the way your own project would.

It holds the desk's two operators and the runner that serves them. The guides
write them one tasklet at a time.

| File | What is in it |
|---|---|
| `Main.java` | the runner: this process joins the `trading` namespace and registers both factories |
| `TradeWatcherOperator.java` | the ETL stream, a flow operator: the exchange's trade messages in, a cell per symbol, buy signals out |
| `TrackPrices.java` | keeps each symbol's last ten prices, and compares a new price with their average |
| `BuyAboveAverage.java` | starts an order when a price is 2 % or more above its average |
| `OrderExecutorOperator.java` | the workflow, a steps operator: two steps in order, then two side by side. The order an instance is started with is the `OrderTicket` record here |
| `ReserveFunds.java` | step one: puts the money aside, and releases it again if the order is undone |
| `PlaceOrder.java` | step two: places the paper order, and cancels it if the order is undone |
| `ConfirmFill.java` | waits ten seconds for the exchange, confirms the fill and ends the order with its result, and refuses an order above the exchange's limit |
| `RecordOrder.java` | books the placed order, and has nothing to undo |

Every tasklet is a class that implements `Tasklet<Input, State, Output>` and
carries `@Tasklet.Spec` with its name. What a tasklet remembers, the changes that
record it, and the records it takes and hands on, sit inside its own class,
because nothing else uses them.

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

## Start an order by hand

One instance of `order_executor` is one order. `--text` carries the order on the
`order` channel:

```sh
flz operator start order_executor --id order_1 --namespace trading \
  --text '{"symbol": "EUR/USD", "quantity": 10}' --channel order
```

The runner's terminal shows the order being booked. The ids come from
`ctx.stableId()`, so yours differ:

```
[book] EUR/USD 10 as ord-6b2e13f521b6781afd9f710152cd2f25
```

About ten seconds later, once the exchange has answered, the instance ends on
its own, and `flz operator info` shows how:

```sh
flz operator info order_1 --namespace trading
```

```
...
Operator: order_1
  Run: steps (each: once, on_failure: undo_all)
  Phase: finished
  Status: ok
  Finished At (unix s): 1790554778
```

## Watch it undo itself

The paper exchange fills at most 100 at a time, so `confirm_fill` refuses a
larger order. Start one the same way, with a quantity above the limit:

```sh
flz operator start order_executor --id order_2 --namespace trading \
  --text '{"symbol": "EUR/USD", "quantity": 500}' --channel order
```

`confirm_fill` is tried three times, ten seconds each, and then the order ends
in failure. Every
step that had finished is undone, in reverse: the order is cancelled, then the
money is released. `record_order` has no undo, so the booking stays:

```
[book] EUR/USD 500 as ord-0e7ee9553cfe5eac606fd232fd7ab33c
[flezha] tasklet confirm_fill attempt 1/3 failed (retrying in 54 ms): the exchange fills at most 100 a time, and this is 500
[flezha] tasklet confirm_fill attempt 2/3 failed (retrying in 136 ms): the exchange fills at most 100 a time, and this is 500
[undo] cancelled ord-0e7ee9553cfe5eac606fd232fd7ab33c
[undo] released res-54e4641a002cc27f8d030507d8d5aea5
```

```sh
flz operator info order_2 --namespace trading
```

```
...
Operator: order_2
  Run: steps (each: once, on_failure: undo_all)
  Phase: finished
  Status: error
  Error: the exchange fills at most 100 a time, and this is 500
  Finished At (unix s): 1790554813
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
walks through what a tasklet, an operator and a runner each are.
