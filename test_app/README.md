# Java Compute Engine

Reference Java test app that mirrors `test/apps/rust_runner/`. Used
by the shared conformance suite under `FLEZHA_E2E_RUNTIME=java mix test
--include system` and by the `mix bench --runtime java` regression bench. The
factory ids it registers, the transports it supports and how it is launched are
declared in `conformance.json` beside this file — see
`docs/sdk/conformance.md`.

The app declares its Relay classes via
`Flezha.createRunner(ns).registerFactory(...)` — the integration-matrix core
being:

- `do_nothing` — `DoNothingRelay` + `DoNothingTasklet`
- `store_input` — `StoreInputRelay` + `StoreInputTasklet`
- `compare_filter` — `CompareFilterRelay` + `CompareFilterTasklet`

The factory id list matches `test/apps/rust_runner/`'s `do_nothing` /
`store_input` / `compare_filter` set; the `relay-message-handlers`
factories are absent from both apps until that capability ships.

Every tasklet is a class implementing `Tasklet<Input, State, Output>` and carrying
`@Tasklet.Spec(name = ...)`; nothing is generated. It also carries the §5 short forms, tasklet
classes annotated `@Relay` that are whole relays:
`SugarDoNothing`, `SugarStore`, `SugarNamedInput`, the sinkless `SugarStateOnly`, the steps
short forms `SugarSteps` and the `per_input` `SugarStepsPerInput`; and the 2-tasklet
`SugarEntry` → `SugarDownstream` reference relay, wired by `SugarStaticCellRelay`.
`Main` registers each short form by its class with `registerFactory`, as it registers every
other relay; `SugarTwinTest` asserts they register under their ids and process input as the
equivalent hand-written relays do. See `docs/sdk/authoring_contract.md`.

## Build

```sh
mvn -f test/apps/java_runner/pom.xml package
java -jar test/apps/java_runner/target/java_runner.jar my_namespace
```
