package com.ecommerce.payment.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentProcessMarkerRepository extends MongoRepository<PaymentProcessMarker, String> {
}
