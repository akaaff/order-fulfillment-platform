package com.orderplatform.aiagent.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * Talks directly to order-service (not through the gateway - this is an
 * internal service-to-service call). Bounded connect/read timeouts so a
 * hung order-service can't also hang this service's request thread.
 *
 * <p>Every call forwards the original caller's own JWT rather than minting
 * a separate service-account credential - order-service's defense-in-depth
 * re-validation (and its customerId-from-JWT scoping) needs a real token to
 * check, and forwarding the same one preserves "acting on behalf of this
 * specific customer" all the way through instead of introducing a second,
 * looser trust boundary.
 */
@Component
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(RestClient.Builder builder, @Value("${orderplatform.order-service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3_000);
        requestFactory.setReadTimeout(5_000);

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public Optional<OrderView> findById(String orderId, String rawToken) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri("/orders/{id}", orderId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                            .retrieve()
                            .body(OrderView.class)
            );
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        }
    }

    public List<OrderSearchResult> search(String status, String rawToken) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/orders/search");
                    if (status != null && !status.isBlank()) {
                        uriBuilder.queryParam("status", status);
                    }
                    return uriBuilder.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)
                .retrieve()
                .body(new ParameterizedTypeReference<List<OrderSearchResult>>() {
                });
    }
}
