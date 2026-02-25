package com.ecommerce.catalog.service;

import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductRepository;
import com.ecommerce.events.ProductUpsertCommand;
import com.ecommerce.events.ProductUpsertedEvent;
import com.ecommerce.events.TopicNames;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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
        ProductUpsertCommand command = record.value();
        log.info(
                "Kafka consumed: topic={} partition={} offset={} timestamp={} key={} payload={}",
                record.topic(), record.partition(), record.offset(), record.timestamp(), record.key(), command
        );
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
        productEventProducer.send(TopicNames.PRODUCT_UPSERTED, product.getId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error(
                                "Kafka produce failed: topic={} key={} payload={} error={}",
                                TopicNames.PRODUCT_UPSERTED, product.getId(), event, ex.getMessage(), ex
                        );
                        return;
                    }
                    var metadata = result.getRecordMetadata();
                    log.info(
                            "Kafka produced: topic={} partition={} offset={} timestamp={} key={} payload={}",
                            metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp(),
                            product.getId(), event
                    );
                });
    }
}
