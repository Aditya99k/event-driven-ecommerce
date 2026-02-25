# Event-Driven E-Commerce Microservices

This repository has been rebuilt into an event-driven e-commerce platform using:
- Apache Kafka (event backbone)
- MongoDB (service data + GraphQL read models)
- Redis (inventory stock state)
- GraphQL (gateway/BFF)
- Spring Boot (microservices)

## Services

- `graphql-api` (port `8080`): GraphQL entrypoint for queries/mutations. Publishes commands and maintains read projections.
- `catalog-service` (port `8081`): Handles product upsert commands and emits product events.
- `order-service` (port `8082`): Handles order requests and saga state transitions.
- `inventory-service` (port `8083`): Maintains stock in Redis, reserves/rejects inventory.
- `payment-service` (port `8084`): Processes payment requests and emits payment outcomes.
- `common-events`: Shared Kafka contracts.

## Event Flow (Saga)

1. `upsertProduct` mutation -> `catalog.product-upsert-command`
2. `catalog-service` persists product -> emits `catalog.product-upserted`
3. `placeOrder` mutation -> `order.requested`
4. `order-service` creates order -> emits `order.created`
5. `inventory-service` checks Redis stock:
   - success -> emits `inventory.reserved`
   - failure -> emits `inventory.rejected`
6. On `inventory.reserved`, `order-service` emits `payment.requested`
7. `payment-service` emits:
   - `payment.completed` or
   - `payment.failed`
8. `order-service` updates final status and emits `order.status-changed`
9. `graphql-api` consumes events and updates MongoDB projections for queries.

## Run Infra

```bash
docker compose up -d
```

## Build

```bash
./mvnw clean package -DskipTests
```

## Run Services

Start each service in separate terminals:

```bash
./mvnw -pl graphql-api spring-boot:run
./mvnw -pl catalog-service spring-boot:run
./mvnw -pl order-service spring-boot:run
./mvnw -pl inventory-service spring-boot:run
./mvnw -pl payment-service spring-boot:run
```

## GraphQL Endpoint

- URL: `http://localhost:8080/graphql`
- GraphiQL: `http://localhost:8080/graphiql`

## Sample Mutations / Queries

```graphql
mutation {
  upsertProduct(input: {
    name: "Noise Cancelling Headphones"
    description: "Wireless over-ear"
    price: 199.99
    stock: 15
  })
}
```

```graphql
mutation {
  placeOrder(input: {
    userId: "user-1"
    items: [{ productId: "<PRODUCT_ID>", quantity: 1, unitPrice: 199.99 }]
  })
}
```

```graphql
query {
  orders(userId: "user-1") {
    id
    status
    reason
    totalAmount
  }
}
```
