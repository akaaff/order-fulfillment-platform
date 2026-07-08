package com.orderplatform.aiagent.chat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Wraps every call into the local Ollama model with a timeout and a circuit
 * breaker, so a slow or hung model can't tie up request threads or cascade
 * into a pile of stuck requests - it fails fast instead. Ollama's chat call
 * is blocking, so the timeout is enforced by running it on a virtual thread
 * and having Resilience4j's TimeLimiter give up on (not necessarily kill)
 * that future rather than by making the whole call chain reactive.
 */
@Component
public class GuardedChatCaller {

    private final ChatClient chatClient;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GuardedChatCaller(
            ChatClient chatClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry
    ) {
        this.chatClient = chatClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("ollama");
        this.timeLimiter = timeLimiterRegistry.timeLimiter("ollama");
    }

    public String call(String systemPrompt, String question, Object scopedTools) {
        Supplier<String> blockingCall = () -> chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .tools(scopedTools)
                .call()
                .content();

        Supplier<String> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, blockingCall);

        try {
            return timeLimiter.executeFutureSupplier(() -> CompletableFuture.supplyAsync(guarded, executor));
        } catch (Exception e) {
            throw new AiAgentUnavailableException("The assistant is temporarily unavailable, please try again shortly.", e);
        }
    }
}
