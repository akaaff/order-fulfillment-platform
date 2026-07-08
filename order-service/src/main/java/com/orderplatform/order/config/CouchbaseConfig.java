package com.orderplatform.order.config;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Exposes the raw Couchbase SDK Bucket/Collection beans (built on top of the
 * Cluster bean Spring Boot autoconfigures from spring.couchbase.*) so
 * services can use the SDK's transactions API directly instead of going
 * through the Spring Data repository abstraction.
 */
@Configuration
public class CouchbaseConfig {

    @Value("${orderplatform.couchbase.bucket}")
    private String bucketName;

    @Bean
    public Bucket ordersBucket(Cluster cluster) {
        Bucket bucket = cluster.bucket(bucketName);
        bucket.waitUntilReady(Duration.ofSeconds(30));
        return bucket;
    }

    @Bean
    public Collection ordersCollection(Bucket ordersBucket) {
        return ordersBucket.defaultCollection();
    }
}
