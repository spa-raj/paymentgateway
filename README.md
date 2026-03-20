# VibeVault Payment Gateway

Payment gateway microservice for the VibeVault e-commerce platform. Integrates with **Razorpay** (test mode) for real payment link generation and webhook-based payment verification.

## Tech Stack

- **Runtime:** Java 21, Spring Boot 4.0.3
- **Database:** MySQL (RDS on EKS / local Docker)
- **Messaging:** Apache Kafka (consumer + producer, KRaft mode)
- **Payment Gateway:** Razorpay SDK 1.4.8 (test mode)
- **Auth:** OAuth2 Resource Server (JWT from userservice)
- **Migration:** Flyway
- **Infrastructure:** AWS EKS, Helm, GitHub Actions CI/CD

## API Endpoints

| Method | Endpoint | Auth | Description | Status |
|--------|----------|------|-------------|--------|
| `GET` | `/payments/order/{orderId}` | JWT | Get payment by order ID (owner only) | 200 |
| `GET` | `/payments/{paymentId}` | JWT | Get payment by payment ID (owner only) | 200 |
| `POST` | `/payments/webhook/razorpay` | None | Razorpay webhook callback | 200 |

## Kafka Events

### Consumes

| Topic | Event | Action |
|-------|-------|--------|
| `order-events` | `ORDER_CREATED` | Create Razorpay payment link, store PENDING payment |

### Produces to `payment-events` topic

| Event | Trigger |
|-------|---------|
| `PAYMENT_CONFIRMED` | Razorpay webhook: `payment_link.paid` |
| `PAYMENT_FAILED` | Razorpay webhook: `payment_link.expired` or `payment_link.cancelled` |

## Saga Flow

```
Order Service → ORDER_CREATED (order-events)
  → Payment Gateway consumes → creates Razorpay payment link → PENDING
  → User pays on Razorpay test page
  → Razorpay webhook → verify signature → PAYMENT_CONFIRMED (payment-events)
  → Order Service updates order to CONFIRMED
```

## Razorpay Integration

- **Strategy Pattern:** `PaymentGateway` interface → `RazorpayPaymentGateway` (extensible for Stripe)
- **Payment Links:** Created via `razorpayClient.paymentLink.create()` with amount (paise), customer info, notes
- **Webhook Verification:** `Utils.verifyWebhookSignature()` with HMAC-SHA256
- **Test Card:** `4111 1111 1111 1111`, any future expiry, any CVV

## Production Hardening

- **Persist before link:** PENDING payment saved to DB before Razorpay API call (prevents orphaned links)
- **Concurrent safety:** `DataIntegrityViolationException` caught, re-queries existing payment
- **State machine:** Cannot confirm FAILED payment or fail CONFIRMED payment (`InvalidPaymentStateException`)
- **Idempotency:** `orderEventId` unique constraint, duplicate events return existing payment
- **Duplicate event prevention:** `PaymentTransitionResult` — events only published on actual state transitions
- **Fail-fast:** `@Validated` + `@NotBlank` on Razorpay keys — app won't start with missing config
- **Webhook resilience:** 200 for state conflicts, 400 for bad payloads, 500 for transient failures (Razorpay retries)

## Local Development

### Prerequisites
- Java 21
- Docker & Docker Compose
- Razorpay test account (key ID + key secret)
- Other services running (userservice, productservice, cartservice, orderservice on vibevault-network)
- ngrok for webhook testing (`ngrok http 8084`)

### Setup
```bash
# Create .env file with Razorpay credentials
cat > .env << 'EOF'
RAZORPAY_KEY_ID=rzp_test_xxxxx
RAZORPAY_KEY_SECRET=xxxxx
RAZORPAY_WEBHOOK_SECRET=xxxxx
EOF

docker network create vibevault-network 2>/dev/null || true
docker compose up --build
```

### Razorpay Webhook Setup
1. Run `ngrok http 8084`
2. Razorpay Dashboard → Settings → Webhooks → Add
3. URL: `https://your-ngrok-url.ngrok-free.dev/payments/webhook/razorpay`
4. Events: `payment_link.paid`, `payment_link.expired`, `payment_link.cancelled`
5. Copy webhook secret to `.env`

### Test
```bash
./scripts/test-payment-apis.sh
```

**28+ API integration tests** with interactive Razorpay payment step. Tests full saga: checkout → order → payment link → pay → webhook → CONFIRMED.

## Unit Tests

**18 tests** (13 service + 4 controller + 1 context):
```bash
./mvnw verify
```

## Port

`8084` (configurable via `PORT` env var)
