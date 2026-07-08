package com.orderplatform.aiagent.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    public Optional<OrderView> findById(String orderId) {
        try {
            return Optional.ofNullable(
                    restClient.get()
                            .uri("/orders/{id}", orderId)
                            .retrieve()
                            .body(OrderView.class)
            );
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            return Optional.empty();
        }
    }

    public List<OrderSearchResult> search(String customerId, String status) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/orders/search").queryParam("customerId", customerId);
                    if (status != null && !status.isBlank()) {
                        uriBuilder.queryParam("status", status);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(new ParameterizedTypeReference<List<OrderSearchResult>>() {
                });
    }
}
