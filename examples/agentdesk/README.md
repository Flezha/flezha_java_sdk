# Agentdesk: the Java project for guides Part 3

The chat sessions the [Part 3 guides](../../../../docs/guides/advanced/advanced_topics.md)
build, as a standalone project. Nothing here depends on the Flezha source tree: the
`pom.xml` names one released coordinate, the way your own project would.

Every conversation is a session of its own: an instance of the bounded operator `agent`,
which runs one turn per message and keeps the conversation as its state. A turn is
answered by a demo model that lives in this project. It is a stand-in, not a model:
it answers every message with the same sentence, one token at a time, and reports how
many tokens it used. `DemoModel.java` is where a real model's client would be called.

| File | What is in it |
|---|---|
| `Main.java` | the runner: this process joins the `agents` namespace and registers the `agent` factory |
| `AgentOperator.java` | a session, a bounded operator: one turn per message, and a failed turn fails alone |
| `Reply.java` | answers a message with the demo model, sends the answer as it grows, and keeps the conversation |
| `Deliver.java` | hands the finished answer to whoever called |
| `DemoModel.java` | the demo model |
| `Dockerfile` | the runner's image: the jar on a Java 21 runtime |
| `controller/Dockerfile` | the controller's image: Flezha's own, with the plugin packages in `controller/plugins/` |
| `compose.yaml` | one controller and one runner, in Docker Compose |

## Run it

You need Java 21, Maven, and Docker with Docker Compose.

```sh
mvn -q package
docker compose up -d --build --wait
alias flz='docker compose exec controller bin/flz'
```

The alias runs `flz` inside the controller's container. The runner joins `agents` on its
own; the controller lists it:

```sh
flz runner list --namespace agents
```

## Talk to a session

Start a session, then send it a turn and wait for its answer:

```sh
flz operator start agent --id anna --namespace agents
flz operator call anna --namespace agents --text 'Hello'
```

```
{"text":"Message 1: you said \"Hello\". I am a demo model.","tokens":10}
```

`docker compose down -v` stops both containers and removes the sessions with their volumes.

## Building from a checkout

The SDK is not on Maven Central yet, so `com.flezha:flezha-sdk:0.1.0` has to be in your
local Maven repository before this resolves, and the controller's image
`flezha/flezha:0.1.0` has to be built. From the repository root:

```sh
scripts/build-native.sh --output sdk/java/flezha-sdk-native/src/main/resources/native
mvn -f sdk/java/pom.xml -DskipTests install
docker build -f docker/prod/Dockerfile.controller -t flezha/flezha:0.1.0 .
```

That is a one-time step. Once the SDK and the image are published, it goes away and the
commands above are all you need.

## Where to go next

The tradedesk in [`../tradedesk`](../tradedesk/README.md) is the Part 2 project, and
[`docs/sdk/calls.md`](../../../../docs/sdk/calls.md) is the contract `flz operator call`
follows.
