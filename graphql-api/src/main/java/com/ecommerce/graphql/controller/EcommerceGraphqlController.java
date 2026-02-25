package com.ecommerce.graphql.controller;

import com.ecommerce.graphql.dto.PlaceOrderInput;
import com.ecommerce.graphql.dto.UpsertProductInput;
import com.ecommerce.graphql.projection.OrderView;
import com.ecommerce.graphql.projection.OrderViewRepository;
import com.ecommerce.graphql.projection.ProductView;
import com.ecommerce.graphql.projection.ProductViewRepository;
import com.ecommerce.graphql.service.CommandPublisher;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class EcommerceGraphqlController {

    private final ProductViewRepository productViewRepository;
    private final OrderViewRepository orderViewRepository;
    private final CommandPublisher commandPublisher;

    public EcommerceGraphqlController(ProductViewRepository productViewRepository,
                                      OrderViewRepository orderViewRepository,
                                      CommandPublisher commandPublisher) {
        this.productViewRepository = productViewRepository;
        this.orderViewRepository = orderViewRepository;
        this.commandPublisher = commandPublisher;
    }

    @QueryMapping
    public List<ProductView> products() {
        return productViewRepository.findAll();
    }

    @QueryMapping
    public List<OrderView> orders(@Argument("userId") String userId) {
        if (userId == null || userId.isBlank()) {
            return orderViewRepository.findAll();
        }
        return orderViewRepository.findByUserId(userId);
    }

    @QueryMapping
    public OrderView order(@Argument("orderId") String orderId) {
        return orderViewRepository.findById(orderId).orElse(null);
    }

    @MutationMapping
    public String upsertProduct(@Argument("input") UpsertProductInput input) {
        return commandPublisher.upsertProduct(input);
    }

    @MutationMapping
    public String placeOrder(@Argument("input") PlaceOrderInput input) {
        return commandPublisher.placeOrder(input);
    }
}
