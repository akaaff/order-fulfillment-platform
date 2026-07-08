package com.orderplatform.order.search;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * customerId is required (not just an optional filter) so this endpoint can
 * never return an unscoped, cross-customer result set - it's meant to answer
 * "my orders", not "all orders", which also matches how the AI support
 * agent's searchMyOrders tool will call this later.
 */
@Service
public class OrderSearchService {

    private static final int MAX_RESULTS = 50;

    private final ElasticsearchOperations operations;

    public OrderSearchService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    public List<OrderSearchDocument> search(String customerId, String status, Instant from, Instant to) {
        Criteria criteria = new Criteria("customerId").is(customerId);

        if (status != null) {
            criteria = criteria.and(new Criteria("status").is(status));
        }
        if (from != null && to != null) {
            criteria = criteria.and(new Criteria("createdAt").between(from, to));
        } else if (from != null) {
            criteria = criteria.and(new Criteria("createdAt").greaterThanEqual(from));
        } else if (to != null) {
            criteria = criteria.and(new Criteria("createdAt").lessThanEqual(to));
        }

        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setPageable(PageRequest.of(0, MAX_RESULTS));

        return operations.search(query, OrderSearchDocument.class)
                .stream()
                .map(SearchHit::getContent)
                .toList();
    }
}
