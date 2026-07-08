package com.orderplatform.notification.config;

import com.orderplatform.events.EventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICATION_FACTOR = 1;

    @Bean
    public NewTopic orderConfirmedTopic() {
        return new NewTopic(EventTopics.ORDER_CONFIRMED, PARTITIONS, REPLICATION_FACTOR);
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return new NewTopic(EventTopics.ORDER_CANCELLED, PARTITIONS, REPLICATION_FACTOR);
    }
}
