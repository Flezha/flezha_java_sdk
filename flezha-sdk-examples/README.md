# Flezha Java SDK: local-mode example

[`local/`](src/main/java/com/flezha/examples/local) runs a two-tasklet word
count inside the calling JVM with no controller, no socket and no container.
`Split` and `Tally` are ordinary tasklets: the same classes deploy against a
controller unchanged. Nothing is durable in this mode, so it shows that a graph
behaves as intended, not that it recovers correctly.

The example is compiled by the Maven reactor and covered by a test, so
it breaks the build rather than drifting away from the SDK.

```bash
mvn -f sdk/java/pom.xml test -pl flezha-sdk-examples -am
mvn -f sdk/java/flezha-sdk-examples/pom.xml exec:java -Dexec.mainClass=com.flezha.examples.local.Main
```

See [Local mode](https://github.com/flezha/flezha/blob/main/docs/public/topics/core/running_and_testing.md#local-mode).
