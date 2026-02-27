package com.ecommerce.catalog.service;

import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductRepository;
import com.ecommerce.events.ProductUpsertCommand;
import com.ecommerce.events.ProductUpsertedEvent;
import com.ecommerce.events.TopicNames;
import com.ecommerce.events.TraceHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class CatalogEventHandler {
    private static final Logger log = LoggerFactory.getLogger(CatalogEventHandler.class);

    private final ProductRepository productRepository;
    private final KafkaTemplate<String, ProductUpsertedEvent> productEventProducer;

    public CatalogEventHandler(ProductRepository productRepository,
                               KafkaTemplate<String, ProductUpsertedEvent> productEventProducer) {
        this.productRepository = productRepository;
        this.productEventProducer = productEventProducer;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERT_COMMAND, groupId = "catalog-service")
    public void onProductUpsertCommand(ConsumerRecord<String, ProductUpsertCommand> record) {
        String correlationId = extractCorrelationId(record.headers());
        MDC.put(TraceHeaders.CORRELATION_ID, correlationId);
        try {
            ProductUpsertCommand command = record.value();
            log.info("Kafka consumed: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                    correlationId, record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), command);

            Product product = new Product();
            product.setId(command.productId());
            product.setName(command.name());
            product.setDescription(command.description());
            product.setPrice(command.price());
            product.setStock(command.stock());
            productRepository.save(product);

            ProductUpsertedEvent event = new ProductUpsertedEvent(
                    product.getId(),
                    product.getName(),
                    product.getDescription(),
                    product.getPrice(),
                    product.getStock()
            );
            sendEvent(TopicNames.PRODUCT_UPSERTED, product.getId(), event, correlationId);
        } finally {
            MDC.remove(TraceHeaders.CORRELATION_ID);
        }
    }

    private void sendEvent(String topic, String key, ProductUpsertedEvent payload, String correlationId) {
        ProducerRecord<String, ProductUpsertedEvent> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add(TraceHeaders.CORRELATION_ID, correlationId.getBytes(StandardCharsets.UTF_8));

        productEventProducer.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Kafka produce failed: correlationId={} topic={} key={} payload={} error={}",
                        correlationId, topic, key, payload, ex.getMessage(), ex);
                return;
            }
            var metadata = result.getRecordMetadata();
            log.info("Kafka produced: correlationId={} topic={} partition={} offset={} timestamp={} key={} payload={}",
                    correlationId, metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(), key, payload);
        });
    }

    private String extractCorrelationId(Headers headers) {
        Header header = headers.lastHeader(TraceHeaders.CORRELATION_ID);
        if (header == null || header.value() == null || header.value().length == 0) {
            return UUID.randomUUID().toString();
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
