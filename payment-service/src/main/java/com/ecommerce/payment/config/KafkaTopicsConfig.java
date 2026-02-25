package com.ecommerce.payment.config;

import com.ecommerce.events.TopicNames;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic paymentCompletedTopic() {
        return TopicBuilder.name(TopicNames.PAYMENT_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentFailedTopic() {
        return TopicBuilder.name(TopicNames.PAYMENT_FAILED).partitions(3).replicas(1).build();
    }
}
