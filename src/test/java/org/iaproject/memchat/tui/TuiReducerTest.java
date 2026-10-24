package org.iaproject.memchat.tui;

import org.iaproject.memchat.agent.AgentEvent;
import org.iaproject.memchat.agent.AgentRunResult;
import org.iaproject.memchat.agent.AgentRunState;
import org.iaproject.memchat.tools.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiReducerTest {
    @Test
    void failedToolRemainsFailedEvenWhenTheAgentEventuallySucceeds() {
        var state = TuiReducer.reduce(TuiState.initial("test"), toolStarted("call-1"));
        state = TuiReducer.reduce(state, toolCompleted("call-1", ToolResult.failure("search", "Service unavailable")));
        assertEquals(TuiState.ToolStatus.ERROR, state.activities().getFirst().status());
        assertEquals("Service unavailable", state.activities().getFirst().result());
        assertTrue(state.notice().contains("Service unavailable"));
        assertEquals(TuiState.Status.THINKING, state.status());
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, "Recovered answer"));
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.RUN_COMPLETED, "SUCCEEDED"));
        assertEquals(TuiState.ToolStatus.ERROR, state.activities().getFirst().status());
        assertEquals(TuiState.Status.IDLE, state.status());
        assertTrue(state.transcript().stream().anyMatch(entry -> entry.role() == TuiState.Role.ERROR
                && entry.text().contains("Service unavailable")));
    }

    @Test
    void correlatesRepeatedCallsToTheSameToolByCallId() {
        var state = TuiReducer.reduce(TuiState.initial("test"), toolStarted("first"));
        state = TuiReducer.reduce(state, toolCompleted("first", ToolResult.failure("search", "timeout")));
        state = TuiReducer.reduce(state, toolStarted("retry"));
        state = TuiReducer.reduce(state, toolCompleted("retry", ToolResult.success("search", "found")));
        assertEquals(2, state.activities().size());
        assertEquals(TuiState.ToolStatus.ERROR, state.activities().getFirst().status());
        assertEquals(TuiState.ToolStatus.OK, state.activities().getLast().status());
        assertEquals("found", state.activities().getLast().result());
        assertEquals(12, state.activities().getLast().durationMillis());
        assertNull(state.notice());
    }

    @Test
    void completionAfterClearingTheViewStillReportsTheFailure() {
        var state = TuiReducer.clear(TuiReducer.reduce(TuiState.initial("test"), toolStarted("one")));
        state = TuiReducer.reduce(state, toolCompleted("one", ToolResult.failure("search", null)));
        assertEquals(TuiState.ToolStatus.ERROR, state.activities().getFirst().status());
        assertTrue(state.notice().contains("Erreur inconnue"));
    }

    private static AgentEvent toolStarted(String callId) {
        return new AgentEvent(AgentEvent.Type.TOOL_EXECUTION, "search", null, null,
                new AgentEvent.ToolExecution(callId, "search", null, -1));
    }

    private static AgentEvent toolCompleted(String callId, ToolResult result) {
        return new AgentEvent(AgentEvent.Type.TOOL_COMPLETED, "search", null, null,
                new AgentEvent.ToolExecution(callId, "search", result, 12));
    }

    @Test
    void displaysFailureFromCompletionWithoutAnErrorEvent() {
        TuiState state = TuiReducer.userMessage(TuiState.initial("test"), "hello");
        var result = failedRun("Connection refused");
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.RUN_COMPLETED,
                "FAILED", result.runId(), result));
        assertEquals(TuiState.Status.ERROR, state.status());
        assertEquals(new TuiState.TranscriptEntry(TuiState.Role.ERROR, "MODEL: Connection refused"),
                state.transcript().getLast());
    }

    @Test
    void failureWithoutAMessageFallsBackToItsTypeAndClosesTools() {
        TuiState state = TuiReducer.reduce(TuiState.initial("test"),
                new AgentEvent(AgentEvent.Type.TOOL_EXECUTION, "search"));
        var result = failedRun(null);
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.RUN_COMPLETED,
                "FAILED", result.runId(), result));
        assertEquals("MODEL: java.io.IOException", state.transcript().getLast().text());
        assertEquals(TuiState.ToolStatus.ERROR, state.activities().getFirst().status());
    }

    private static AgentRunResult failedRun(String message) {
        return new AgentRunResult(java.util.UUID.randomUUID(), AgentRunState.FAILED, null,
                new AgentRunResult.Failure(AgentRunResult.Stage.MODEL, "java.io.IOException", message),
                1, false);
    }

    @Test
    void reducesConversationToolActivityAndUsage() {
        TuiState state = TuiState.initial("test-model");
        state = TuiReducer.userMessage(state, "hello");
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.TOOL_EXECUTION, "search"));
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.AGENT_MESSAGE, "**done**"));

        assertEquals(2, state.transcript().size());
        assertEquals("search", state.activities().getFirst().name());
        assertEquals(TuiState.ToolStatus.OK, state.activities().getFirst().status());
        assertEquals(TuiState.Status.IDLE, state.status());
    }

    @Test
    void rendersErrorsAndClearsTheView() {
        TuiState state = TuiState.initial("test")
                .withInput("draft");
        state = TuiReducer.reduce(state, new AgentEvent(AgentEvent.Type.ERROR, "timeout"));
        assertEquals(TuiState.Role.ERROR, state.transcript().getFirst().role());
        assertEquals(TuiState.Status.ERROR, state.status());
        assertTrue(TuiReducer.clear(state).transcript().isEmpty());
    }
}
