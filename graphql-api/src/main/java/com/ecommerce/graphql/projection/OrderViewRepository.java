package com.ecommerce.graphql.projection;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OrderViewRepository extends MongoRepository<OrderView, String> {
    List<OrderView> findByUserId(String userId);
}
