package com.ecommerce.graphql.projection;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProductViewRepository extends MongoRepository<ProductView, String> {
}
