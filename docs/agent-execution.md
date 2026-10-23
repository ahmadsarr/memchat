# Agent execution and server integration

`AgentLoop.run` is synchronous and executes on its caller's thread. It does not
assume a main thread or create a request executor. A Spring application can submit
it to its own bounded executor or TaskExecutor; it should not run blocking work on
a reactive event-loop thread.

Create a fresh `AgentCancellation` for each execution. Keep it with the application's
request/job state so another controller or service can cancel that execution:

```java
AgentCancellation cancellation = new AgentCancellation();
Future<?> task = executor.submit(() -> {
    agent.run(instruction, cancellation);
    return null;
});

// From the controller/service handling cancellation:
cancellation.cancel();
```

Cancellation throws `CancellationException` at the next execution boundary. It
does not interrupt the executor's worker, cancel another request, or reset itself
for the next run. The existing `run(String)` entry point remains available.
Thread interruption is also supported and propagates as `InterruptedException`;
it is a complementary mechanism for callers that own the worker's lifecycle.

Cancellation is cooperative. A model request, memory compaction, tool invocation,
or listener wait already in progress is not forcibly aborted. The caller must
wait for the run to finish before closing the agent. The signal prevents later
operations after it is observed; it cannot roll back tool effects already applied.
A task cancelled through this signal completes exceptionally, so applications must
handle that outcome instead of reporting success.

`AgentLoop` owns mutable conversation memory. Do not share one instance as a Spring
singleton across unrelated conversations. Use an agent per conversation and
serialize that conversation's runs; overlapping runs on the same instance are
rejected. Configure Recall/archive repositories with the intended storage scope
as well: distinct working contexts alone do not isolate persistent storage.

`close()` is idempotent after execution. Closing during a run fails immediately:
request cancellation, await completion, then close. No Spring dependency is needed
in the agent library.
