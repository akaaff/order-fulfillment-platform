package com.orderplatform.aiagent.chat;

/** Raised when the Ollama call times out or the circuit breaker is open - never lets a raw resilience4j/HTTP exception reach the client. */
public class AiAgentUnavailableException extends RuntimeException {

    public AiAgentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
