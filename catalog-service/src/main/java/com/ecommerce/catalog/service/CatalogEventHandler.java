package com.ecommerce.catalog.service;

import com.ecommerce.catalog.domain.Product;
import com.ecommerce.catalog.domain.ProductRepository;
import com.ecommerce.events.ProductUpsertCommand;
import com.ecommerce.events.ProductUpsertedEvent;
import com.ecommerce.events.TopicNames;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogEventHandler {

    private final ProductRepository productRepository;
    private final KafkaTemplate<String, ProductUpsertedEvent> productEventProducer;

    public CatalogEventHandler(ProductRepository productRepository,
                               KafkaTemplate<String, ProductUpsertedEvent> productEventProducer) {
        this.productRepository = productRepository;
        this.productEventProducer = productEventProducer;
    }

    @KafkaListener(topics = TopicNames.PRODUCT_UPSERT_COMMAND, groupId = "catalog-service")
    public void onProductUpsertCommand(ProductUpsertCommand command) {
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
        productEventProducer.send(TopicNames.PRODUCT_UPSERTED, product.getId(), event);
    }
}
