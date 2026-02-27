# Event-Driven E-Commerce Microservices

This repository has been rebuilt into an event-driven e-commerce platform using:
- Apache Kafka (event backbone)
- MongoDB (service data + GraphQL read models)
- Redis (inventory stock state)
- GraphQL (gateway/BFF)
- ELK (centralized log aggregation and search)
- Spring Boot (microservices)

## Production-Grade Hardening Added

- End-to-end correlation tracing:
  - `X-Correlation-Id` is accepted/generated at gateway and propagated through GraphQL and Kafka headers.
  - Consumer services load correlation into MDC so all logs are traceable by one correlation id.
- Idempotent order creation:
  - `order-service` ignores duplicate `order.requested` events for an already existing `orderId`.
- Payment compensation:
  - `inventory-service` stores reservation snapshots per order.
  - On `payment.failed`, stock is automatically restored.
  - On `payment.completed`, reservation snapshot is cleared.

## Services

- `graphql-api` (port `8080`): GraphQL entrypoint for queries/mutations. Publishes commands and maintains read projections.
- `api-gateway` (port `8090`): Edge gateway for GraphQL with JWT guard and correlation-id tracing.
- `catalog-service` (port `8081`): Handles product upsert commands and emits product events.
- `order-service` (port `8082`): Handles order requests and saga state transitions.
- `inventory-service` (port `8083`): Maintains stock in Redis, reserves/rejects inventory.
- `payment-service` (port `8084`): Processes payment requests and emits payment outcomes.
- `user-service` (port `8085`): Handles user upsert commands and emits user events.
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

## Detailed Architecture and Topic-Level Flow

### High-Level Data/Control Flow

```mermaid
flowchart LR
    Client["Client (Postman/Web App)"] --> APIGW["api-gateway :8090\nJWT + CorrelationId"]
    APIGW --> GQL["graphql-api :8080\nGraphQL BFF + Command Publisher + Projections"]

    subgraph Kafka["Kafka Topics"]
      T1["catalog.product-upsert-command"]
      T2["catalog.product-upserted"]
      T3["user.upsert-command"]
      T4["user.upserted"]
      T5["order.requested"]
      T6["order.created"]
      T7["inventory.reserved"]
      T8["inventory.rejected"]
      T9["payment.requested"]
      T10["payment.completed"]
      T11["payment.failed"]
      T12["order.status-changed"]
    end

    GQL -->|produce| T1
    GQL -->|produce| T3
    GQL -->|produce| T5

    T1 -->|consume| CATALOG["catalog-service :8081\nMongo owner: catalog-db.products"]
    CATALOG -->|produce| T2

    T3 -->|consume| USER["user-service :8085\nMongo owner: user-db.users"]
    USER -->|produce| T4

    T5 -->|consume| ORDER["order-service :8082\nMongo owner: order-db.orders\nIdempotent order create"]
    ORDER -->|produce| T6

    T6 -->|consume| INVENTORY["inventory-service :8083\nRedis owner: inventory stock + reservation snapshots"]
    INVENTORY -->|produce| T7
    INVENTORY -->|produce| T8

    T7 -->|consume| ORDER
    ORDER -->|produce| T9

    T9 -->|consume| PAYMENT["payment-service :8084\nPayment decision service"]
    PAYMENT -->|produce| T10
    PAYMENT -->|produce| T11

    T10 -->|consume| ORDER
    T11 -->|consume| ORDER
    ORDER -->|produce| T12

    T11 -->|consume compensation| INVENTORY
    T10 -->|consume cleanup| INVENTORY

    T2 -->|consume projection| GQL
    T4 -->|consume projection| GQL
    T6 -->|consume projection| GQL
    T8 -->|consume projection| GQL
    T10 -->|consume projection| GQL
    T12 -->|consume projection| GQL

    GQL --> GQLDB["graphql-db\nproduct_view, user_view, order_view"]
    Client <-->|query/mutation| APIGW
```

### Detailed Sequence (Mutation to Final State)

```mermaid
sequenceDiagram
    autonumber
    participant U as User Client
    participant GW as api-gateway
    participant GQ as graphql-api
    participant K as Kafka
    participant CS as catalog-service
    participant US as user-service
    participant OS as order-service
    participant IS as inventory-service
    participant PS as payment-service
    participant M1 as catalog-db
    participant M2 as user-db
    participant M3 as order-db
    participant R as Redis
    participant MV as graphql-db views

    Note over U,GW: All protected requests carry Authorization Bearer JWT
    Note over GW,GQ: X-Correlation-Id generated/propagated

    U->>GW: mutation upsertUser(input)
    GW->>GQ: /graphql + JWT + X-Correlation-Id
    GQ->>K: produce user.upsert-command
    K->>US: consume user.upsert-command
    US->>M2: save users
    US->>K: produce user.upserted
    K->>GQ: consume user.upserted
    GQ->>MV: upsert user_view

    U->>GW: mutation upsertProduct(input)
    GW->>GQ: /graphql + JWT + X-Correlation-Id
    GQ->>K: produce catalog.product-upsert-command
    K->>CS: consume catalog.product-upsert-command
    CS->>M1: save products
    CS->>K: produce catalog.product-upserted
    K->>IS: consume catalog.product-upserted
    IS->>R: set inventory:stock:<productId>
    K->>GQ: consume catalog.product-upserted
    GQ->>MV: upsert product_view

    U->>GW: mutation placeOrder(input userId/items)
    GW->>GQ: /graphql + JWT + X-Correlation-Id
    GQ->>K: produce order.requested
    K->>OS: consume order.requested
    OS->>M3: idempotency check + save order CREATED
    OS->>K: produce order.created
    K->>IS: consume order.created
    IS->>R: stock check + decrement + reserve snapshot(orderId)

    alt Stock insufficient
        IS->>K: produce inventory.rejected
        K->>OS: consume inventory.rejected
        OS->>M3: update order INVENTORY_REJECTED
        OS->>K: produce order.status-changed
        K->>GQ: consume inventory.rejected/order.status-changed
        GQ->>MV: update order_view rejected
    else Stock reserved
        IS->>K: produce inventory.reserved
        K->>OS: consume inventory.reserved
        OS->>M3: update order INVENTORY_RESERVED
        OS->>K: produce order.status-changed
        OS->>K: produce payment.requested
        K->>PS: consume payment.requested

        alt Payment success
            PS->>K: produce payment.completed
            K->>OS: consume payment.completed
            OS->>M3: update order PAYMENT_COMPLETED
            OS->>K: produce order.status-changed
            K->>IS: consume payment.completed
            IS->>R: delete reservation snapshot(orderId)
            K->>GQ: consume payment.completed/order.status-changed
            GQ->>MV: update order_view completed
        else Payment failure
            PS->>K: produce payment.failed
            K->>OS: consume payment.failed
            OS->>M3: update order PAYMENT_FAILED
            OS->>K: produce order.status-changed
            K->>IS: consume payment.failed
            IS->>R: restore stock from reservation snapshot + delete snapshot
            K->>GQ: consume order.status-changed
            GQ->>MV: update order_view failed
        end
    end
```

### Service Responsibility and Topic Matrix

| Service | Owns Data | Consumes Topics | Produces Topics | Core Work |
|---|---|---|---|---|
| `api-gateway` | none | HTTP | HTTP (forward) | JWT validation, `X-Correlation-Id` generation/propagation, routing to GraphQL |
| `graphql-api` | `graphql-db.product_view`, `graphql-db.user_view`, `graphql-db.order_view` | `catalog.product-upserted`, `user.upserted`, `order.created`, `inventory.rejected`, `payment.completed`, `order.status-changed` | `catalog.product-upsert-command`, `user.upsert-command`, `order.requested` | BFF layer, command publishing, read-model projection updates |
| `catalog-service` | `catalog-db.products` | `catalog.product-upsert-command` | `catalog.product-upserted` | Product upsert write model |
| `user-service` | `user-db.users` | `user.upsert-command` | `user.upserted` | User upsert write model |
| `order-service` | `order-db.orders` | `order.requested`, `inventory.reserved`, `inventory.rejected`, `payment.completed`, `payment.failed` | `order.created`, `payment.requested`, `order.status-changed` | Order lifecycle, idempotency guard, saga transitions |
| `inventory-service` | Redis keys `inventory:stock:*`, `inventory:reservation:*` | `catalog.product-upserted`, `order.created`, `payment.completed`, `payment.failed` | `inventory.reserved`, `inventory.rejected` | Stock reservation, compensation restore on payment failure |
| `payment-service` | none (event-driven decisioning) | `payment.requested` | `payment.completed`, `payment.failed` | Payment outcome simulation |

### Traceability Guarantees

- HTTP request path: `Client -> api-gateway -> graphql-api` carries `X-Correlation-Id`.
- Kafka path: producers attach `X-Correlation-Id` in Kafka headers.
- Consumers extract header and load into MDC before processing.
- Logs include `correlationId`, topic, partition, offset, timestamp, key, payload.
- Kibana can trace one request across all services using:
  - `correlationId : "<value>"`

## Run Infra

```bash
docker compose up -d
```

Infra endpoints:
- Kafka: `localhost:9092`
- MongoDB: `localhost:27017`
- Redis: `localhost:6379`
- Elasticsearch: `http://localhost:9200`
- Logstash TCP input: `localhost:5044`
- Kibana: `http://localhost:5601`

## Build

```bash
./mvnw clean package -DskipTests
```

## Run Services

Start each service in separate terminals:

```bash
./mvnw -f api-gateway/pom.xml spring-boot:run
./mvnw -f graphql-api/pom.xml spring-boot:run
./mvnw -f catalog-service/pom.xml spring-boot:run
./mvnw -f order-service/pom.xml spring-boot:run
./mvnw -f inventory-service/pom.xml spring-boot:run
./mvnw -f payment-service/pom.xml spring-boot:run
./mvnw -f user-service/pom.xml spring-boot:run
```

## GraphQL Endpoint

- Direct URL: `http://localhost:8080/graphql`
- Through Gateway: `http://localhost:8090/graphql`
- GraphiQL direct: `http://localhost:8080/graphiql`
- GraphiQL through gateway: `http://localhost:8090/graphiql`

## API Gateway Notes

- Gateway runs on `8090` and routes `/graphql` and `/graphiql/**` to `graphql-api`.
- JWT validation is enabled by default for non-public paths (`security.jwt.enabled=true`).
- Public paths: `/actuator/health`, `/actuator/info`, `/auth/token`, `/graphiql`, `/graphiql/**`.
- Protected call example:
  - Add header: `Authorization: Bearer <jwt-token>`
- Correlation header:
  - Gateway injects `X-Correlation-Id` (or propagates existing one) for request tracing.

### Generate JWT for local testing

Request:

```bash
curl -X POST http://localhost:8090/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-1",
    "name": "Aditya",
    "email": "aditya@example.com",
    "ttlSeconds": 3600
  }'
```

Then call GraphQL through gateway:

```bash
curl -X POST http://localhost:8090/graphql \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token-from-auth-response>" \
  -d '{"query":"query { users { id name email } }"}'
```

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

```graphql
mutation {
  upsertUser(input: {
    name: "Aditya"
    email: "aditya@example.com"
  })
}
```

```graphql
query {
  users {
    id
    name
    email
  }
}
```

## Kibana Log Verification

Each service now writes structured JSON logs to Logstash over TCP (`localhost:5044`) and those logs are indexed in Elasticsearch.

1. Open Kibana at `http://localhost:5601`
2. Go to **Stack Management** -> **Data Views**
3. Create data view with pattern: `ecommerce-logs-*`
4. In **Discover**, filter logs with queries like:
   - `service : "order-service"`
   - `message : "Kafka produced"`
   - `message : "Kafka consumed"`
   - `message : "order.created"`
   - `correlationId : "<X-Correlation-Id value>"`

Kafka metadata (`topic`, `partition`, `offset`, `key`, `payload`) is included inside log messages emitted by producers/consumers.
