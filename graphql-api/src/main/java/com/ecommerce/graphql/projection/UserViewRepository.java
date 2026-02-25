package com.ecommerce.graphql.projection;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserViewRepository extends MongoRepository<UserView, String> {
}
