package org.iaproject.memchat.tui;

import org.iaproject.memchat.agent.AgentEvent;
import org.iaproject.memchat.agent.AgentRunResult;
import org.iaproject.memchat.agent.AgentRunState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TuiReducerTest {
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
