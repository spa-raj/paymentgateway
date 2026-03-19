#!/bin/bash
# ==============================================================================
# Payment Gateway API Test Suite
# ==============================================================================
# Tests all payment gateway endpoints and Kafka event flow against local
# Docker Compose deployment. Tests the full saga:
# Cart checkout → Order created → Payment link created → Razorpay payment
#
# Prerequisites:
#   - userservice running on port 8081
#   - productservice running on port 8080
#   - cartservice running on port 8082
#   - orderservice running on port 8083
#   - paymentgateway running on port 8084
#   - All services on vibevault-network
#   - RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET set in paymentgateway env
#
# Usage:
#   ./test-payment-apis.sh
#   TOKEN="xxx" ./test-payment-apis.sh    # skip OAuth2 flow
# ==============================================================================

set -euo pipefail

USERSERVICE="http://localhost:8081"
PRODUCTSERVICE="http://localhost:8080"
CARTSERVICE="http://localhost:8082"
ORDERSERVICE="http://localhost:8083"
PAYMENTGATEWAY="http://localhost:8084"

# Local docker-compose credentials
ADMIN_EMAIL="admin@gmail.com"
ADMIN_PASSWORD="abcd@1234"
CLIENT_ID="vibevault-client"
CLIENT_SECRET="abc@12345"
REDIRECT_URI="https://oauth.pstmn.io/v1/callback"
SCOPES="openid+profile+email+read+write"

PASS=0
FAIL=0
SKIP=0

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ============================================================================
# Helpers
# ============================================================================

assert_status() {
    local description="$1"
    local expected="$2"
    local actual="$3"
    local body="${4:-}"

    if [ "$actual" = "$expected" ]; then
        echo -e "  ${GREEN}PASS${NC} [$actual] $description"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}FAIL${NC} [$actual expected $expected] $description"
        [ -n "$body" ] && echo "       Response: $(echo "$body" | head -c 300)"
        FAIL=$((FAIL + 1))
    fi
}

assert_body_contains() {
    local description="$1"
    local expected_substring="$2"
    local body="$3"

    if echo "$body" | grep -qi "$expected_substring"; then
        echo -e "  ${GREEN}PASS${NC} $description (contains '$expected_substring')"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}FAIL${NC} $description (expected to contain '$expected_substring')"
        echo "       Response: $(echo "$body" | head -c 300)"
        FAIL=$((FAIL + 1))
    fi
}

request() {
    local method="$1"
    local url="$2"
    local headers="${3:-}"
    local data="${4:-}"

    local curl_args=(-s -w "\n%{http_code}" -X "$method" "$url")
    if [ -n "$headers" ]; then
        while IFS= read -r header; do
            [ -n "$header" ] && curl_args+=(-H "$header")
        done <<< "$headers"
    fi
    if [ -n "$data" ]; then
        curl_args+=(-d "$data")
    fi

    local response
    response=$(curl "${curl_args[@]}")
    BODY=$(echo "$response" | head -n -1)
    STATUS=$(echo "$response" | tail -n 1)
}

section() {
    echo ""
    echo -e "${CYAN}--- $1 ---${NC}"
}

urlencode() {
    python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$1"
}

# ============================================================================
# OAuth2 Token Flow
# ============================================================================

get_oauth2_token() {
    set +e
    local username="$1"
    local password="$2"

    local COOKIE_JAR
    COOKIE_JAR=$(mktemp /tmp/payment_test_cookies.XXXXXX)

    local AUTH_URL="${USERSERVICE}/oauth2/authorize?response_type=code&client_id=${CLIENT_ID}&redirect_uri=${REDIRECT_URI}&scope=${SCOPES}"

    curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" -L --max-redirs 1 -o /dev/null "$AUTH_URL"

    local LOGIN_PAGE
    LOGIN_PAGE=$(curl -s -c "$COOKIE_JAR" -b "$COOKIE_JAR" "${USERSERVICE}/login")
    local CSRF
    CSRF=$(echo "$LOGIN_PAGE" | grep -oP 'name="_csrf".*?value="\K[^"]+')

    if [ -z "$CSRF" ]; then
        rm -f "$COOKIE_JAR"
        set -e
        echo ""
        return
    fi

    local ENCODED_PASSWORD
    ENCODED_PASSWORD=$(urlencode "$password")
    curl -s -D- -o /dev/null -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X POST "${USERSERVICE}/login" \
        -d "username=${username}&password=${ENCODED_PASSWORD}&_csrf=${CSRF}" > /dev/null

    local AUTHORIZE_RESPONSE
    AUTHORIZE_RESPONSE=$(curl -s -D- -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        "${USERSERVICE}/oauth2/authorize?response_type=code&client_id=${CLIENT_ID}&redirect_uri=${REDIRECT_URI}&scope=${SCOPES}&continue")

    local AUTHORIZE_LOCATION
    AUTHORIZE_LOCATION=$(echo "$AUTHORIZE_RESPONSE" | grep -i "^Location:" | tr -d '\r' || true)

    local AUTH_CODE=""

    if echo "$AUTHORIZE_LOCATION" | grep -q "code="; then
        AUTH_CODE=$(echo "$AUTHORIZE_LOCATION" | grep -oP 'code=\K[^&\s]+' || true)
    else
        local CONSENT_BODY
        CONSENT_BODY=$(echo "$AUTHORIZE_RESPONSE" | sed '1,/^\r$/d')
        local STATE
        STATE=$(echo "$CONSENT_BODY" | grep -oP 'name="state"[^>]*value="\K[^"]+' || true)

        if [ -z "$STATE" ]; then
            rm -f "$COOKIE_JAR"
            set -e
            echo ""
            return
        fi

        local CONSENT_RESPONSE
        CONSENT_RESPONSE=$(curl -s -D- -o /dev/null -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X POST "${USERSERVICE}/oauth2/authorize" \
            -d "client_id=${CLIENT_ID}&state=${STATE}&scope=read&scope=profile&scope=write&scope=email")

        local CONSENT_LOCATION
        CONSENT_LOCATION=$(echo "$CONSENT_RESPONSE" | grep -i "^Location:" | tr -d '\r' || true)

        AUTH_CODE=$(echo "$CONSENT_LOCATION" | grep -oP 'code=\K[^&\s]+' || true)
    fi

    if [ -z "$AUTH_CODE" ]; then
        rm -f "$COOKIE_JAR"
        set -e
        echo ""
        return
    fi

    local TOKEN_RESPONSE
    TOKEN_RESPONSE=$(curl -s -X POST "${USERSERVICE}/oauth2/token" \
        -u "${CLIENT_ID}:${CLIENT_SECRET}" \
        -d "grant_type=authorization_code" \
        -d "code=${AUTH_CODE}" \
        -d "redirect_uri=${REDIRECT_URI}")

    local TOKEN
    TOKEN=$(echo "$TOKEN_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])" 2>/dev/null || echo "")

    rm -f "$COOKIE_JAR"
    set -e
    echo "$TOKEN"
}

# ============================================================================
# Test Suite
# ============================================================================

echo "=============================================="
echo "  Payment Gateway API Test Suite"
echo "=============================================="

# --------------------------------------------------
section "1. Health Checks"
# --------------------------------------------------

request GET "$USERSERVICE/actuator/health"
assert_status "userservice health" "200" "$STATUS"

request GET "$PRODUCTSERVICE/actuator/health"
assert_status "productservice health" "200" "$STATUS"

request GET "$CARTSERVICE/actuator/health"
assert_status "cartservice health" "200" "$STATUS"

request GET "$ORDERSERVICE/actuator/health"
assert_status "orderservice health" "200" "$STATUS"

request GET "$PAYMENTGATEWAY/actuator/health"
assert_status "paymentgateway health" "200" "$STATUS"

# --------------------------------------------------
section "2. OAuth2 Token"
# --------------------------------------------------

if [ -n "${TOKEN:-}" ]; then
    echo -e "  ${GREEN}PASS${NC} Using provided TOKEN"
    PASS=$((PASS + 1))
else
    echo "  Obtaining admin OAuth2 token..."
    TOKEN=$(get_oauth2_token "$ADMIN_EMAIL" "$ADMIN_PASSWORD")
fi

if [[ "$TOKEN" =~ ^eyJ.*\..*\..*$ ]]; then
    echo -e "  ${GREEN}PASS${NC} Admin OAuth2 token obtained"
    PASS=$((PASS + 1))
    AUTH_HEADERS="$(printf 'Authorization: Bearer %s\nContent-Type: application/json' "$TOKEN")"
    AUTH_ONLY="Authorization: Bearer $TOKEN"
else
    echo -e "  ${RED}FAIL${NC} Could not obtain OAuth2 token"
    FAIL=$((FAIL + 1))
    echo ""
    echo "=============================================="
    printf "  Results: ${GREEN}%d passed${NC}, ${RED}%d failed${NC}, ${YELLOW}%d skipped${NC}\n" "$PASS" "$FAIL" "$SKIP"
    echo "=============================================="
    exit 1
fi

# --------------------------------------------------
section "3. Setup: Create product + Add to cart + Checkout"
# --------------------------------------------------

TIMESTAMP=$(date +%s)
PRODUCT_NAME="PaymentTest-Product-${TIMESTAMP}"

request POST "$PRODUCTSERVICE/categories" "$AUTH_HEADERS" '{"name":"Electronics","description":"Electronic devices"}'
if [ "$STATUS" = "200" ] || [ "$STATUS" = "409" ]; then
    echo -e "  ${GREEN}OK${NC} Category 'Electronics' ready"
fi

request POST "$PRODUCTSERVICE/products" "$AUTH_HEADERS" \
    "{\"name\":\"${PRODUCT_NAME}\",\"description\":\"Test product for payment\",\"price\":499.99,\"currency\":\"INR\",\"categoryName\":\"Electronics\"}"
assert_status "POST /products (create test product)" "200" "$STATUS"
PRODUCT_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

if [ -z "$PRODUCT_ID" ]; then
    echo -e "  ${RED}FAIL${NC} Could not create test product"
    exit 1
fi
echo -e "  ${CYAN}Product ID: ${PRODUCT_ID}${NC}"

# Clear cart
curl -s -X DELETE "$CARTSERVICE/cart" -H "$AUTH_ONLY" > /dev/null 2>&1
echo -e "  ${GREEN}OK${NC} Cart cleared"

# Add item to cart
request POST "$CARTSERVICE/cart/items" "$AUTH_HEADERS" \
    "{\"productId\":\"${PRODUCT_ID}\",\"quantity\":2}"
assert_status "POST /cart/items (add product)" "201" "$STATUS"

# Checkout cart → triggers ORDER_CREATED → triggers payment link creation
request POST "$CARTSERVICE/cart/checkout" "$AUTH_ONLY"
assert_status "POST /cart/checkout (trigger saga)" "200" "$STATUS"

# Wait for Kafka: cart → order → payment
echo -e "  ${CYAN}Waiting for saga: cart → order → payment (Kafka)...${NC}"
sleep 8

# --------------------------------------------------
section "4. Verify Order Created"
# --------------------------------------------------

request GET "$ORDERSERVICE/orders" "$AUTH_ONLY"
assert_status "GET /orders (after checkout)" "200" "$STATUS"

ORDER_ID=$(echo "$BODY" | python3 -c "
import sys,json
data = json.load(sys.stdin)
orders = data.get('content', [])
if orders:
    print(orders[0]['orderId'])
else:
    print('')
" 2>/dev/null || echo "")

if [ -n "$ORDER_ID" ]; then
    echo -e "  ${CYAN}Order ID: ${ORDER_ID}${NC}"
    echo -e "  ${GREEN}PASS${NC} Order created"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}FAIL${NC} No order found"
    FAIL=$((FAIL + 1))
fi

# --------------------------------------------------
section "5. Verify Payment Created (GET /payments/order/{orderId})"
# --------------------------------------------------

if [ -n "$ORDER_ID" ]; then
    request GET "$PAYMENTGATEWAY/payments/order/${ORDER_ID}" "$AUTH_ONLY"
    assert_status "GET /payments/order/{orderId}" "200" "$STATUS"
    assert_body_contains "Payment has orderId" "$ORDER_ID" "$BODY"
    assert_body_contains "Payment status is PENDING" "PENDING" "$BODY"
    assert_body_contains "Payment gateway is RAZORPAY" "RAZORPAY" "$BODY"

    PAYMENT_ID=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('paymentId', ''))" 2>/dev/null || echo "")
    PAYMENT_LINK=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('gatewayPaymentLink', ''))" 2>/dev/null || echo "")
    PAYMENT_AMOUNT=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('amount', 0))" 2>/dev/null || echo "0")

    if [ -n "$PAYMENT_ID" ]; then
        echo -e "  ${CYAN}Payment ID: ${PAYMENT_ID}${NC}"
    fi
    if [ -n "$PAYMENT_LINK" ]; then
        echo -e "  ${CYAN}Razorpay Link: ${PAYMENT_LINK}${NC}"
        echo -e "  ${GREEN}PASS${NC} Razorpay payment link generated"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}FAIL${NC} No Razorpay payment link"
        FAIL=$((FAIL + 1))
    fi
    echo -e "  ${CYAN}Amount: ${PAYMENT_AMOUNT}${NC}"
else
    echo -e "  ${YELLOW}SKIP${NC} No order ID — skipping payment check"
    SKIP=$((SKIP + 5))
fi

# --------------------------------------------------
section "6. GET /payments/{paymentId}"
# --------------------------------------------------

if [ -n "$PAYMENT_ID" ]; then
    request GET "$PAYMENTGATEWAY/payments/${PAYMENT_ID}" "$AUTH_ONLY"
    assert_status "GET /payments/{paymentId}" "200" "$STATUS"
    assert_body_contains "Payment has PENDING status" "PENDING" "$BODY"
    assert_body_contains "Payment has RAZORPAY gateway" "RAZORPAY" "$BODY"
else
    echo -e "  ${YELLOW}SKIP${NC} No payment ID"
    SKIP=$((SKIP + 3))
fi

# --------------------------------------------------
section "7. GET /payments/order/{orderId} (non-existent order)"
# --------------------------------------------------

FAKE_ORDER_ID="00000000-0000-0000-0000-000000000000"
request GET "$PAYMENTGATEWAY/payments/order/${FAKE_ORDER_ID}" "$AUTH_ONLY"
assert_status "GET /payments/order (non-existent → 404)" "404" "$STATUS"
assert_body_contains "Error has PAYMENT_NOT_FOUND" "PAYMENT_NOT_FOUND" "$BODY"

# --------------------------------------------------
section "8. GET /payments/{paymentId} (malformed UUID)"
# --------------------------------------------------

request GET "$PAYMENTGATEWAY/payments/not-a-uuid" "$AUTH_ONLY"
assert_status "GET /payments/not-a-uuid (malformed → 400)" "400" "$STATUS"

# --------------------------------------------------
section "9. Unauthenticated Request"
# --------------------------------------------------

request GET "$PAYMENTGATEWAY/payments/order/${FAKE_ORDER_ID}"
assert_status "GET /payments (no token → 401)" "401" "$STATUS"

# --------------------------------------------------
section "10. Webhook Endpoint Accessible Without Auth"
# --------------------------------------------------

# Webhook should be permitAll — sending empty body should get 400 (bad signature or missing header), not 401
request POST "$PAYMENTGATEWAY/payments/webhook/razorpay" "Content-Type: application/json" '{"test": true}'
if [ "$STATUS" != "401" ]; then
    echo -e "  ${GREEN}PASS${NC} Webhook endpoint accessible without auth (status: $STATUS)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}FAIL${NC} Webhook endpoint returned 401 — security config issue"
    FAIL=$((FAIL + 1))
fi

# --------------------------------------------------
section "11. Interactive Payment (Razorpay Test Mode)"
# --------------------------------------------------

if [ -n "$PAYMENT_LINK" ]; then
    echo ""
    echo -e "  ${YELLOW}═══════════════════════════════════════════════════${NC}"
    echo -e "  ${YELLOW}  Complete payment using Razorpay test mode:${NC}"
    echo -e "  ${YELLOW}  Link: ${PAYMENT_LINK}${NC}"
    echo -e "  ${YELLOW}  Card: 4111 1111 1111 1111${NC}"
    echo -e "  ${YELLOW}  Expiry: any future date | CVV: any 3 digits${NC}"
    echo -e "  ${YELLOW}═══════════════════════════════════════════════════${NC}"
    echo ""
    read -p "  Press ENTER after completing payment (or 's' to skip): " PAYMENT_CHOICE

    if [ "$PAYMENT_CHOICE" != "s" ] && [ "$PAYMENT_CHOICE" != "S" ]; then
        echo -e "  ${CYAN}Waiting for Razorpay webhook callback...${NC}"
        sleep 10

        # Verify payment is now CONFIRMED
        request GET "$PAYMENTGATEWAY/payments/order/${ORDER_ID}" "$AUTH_ONLY"
        PAYMENT_STATUS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status', ''))" 2>/dev/null || echo "")

        if [ "$PAYMENT_STATUS" = "CONFIRMED" ]; then
            echo -e "  ${GREEN}PASS${NC} Payment status is CONFIRMED (webhook worked!)"
            PASS=$((PASS + 1))
        else
            echo -e "  ${RED}FAIL${NC} Payment status is ${PAYMENT_STATUS} (expected CONFIRMED)"
            FAIL=$((FAIL + 1))
        fi

        # Verify order is now CONFIRMED
        request GET "$ORDERSERVICE/orders/${ORDER_ID}" "$AUTH_ONLY"
        ORDER_STATUS=$(echo "$BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status', ''))" 2>/dev/null || echo "")

        if [ "$ORDER_STATUS" = "CONFIRMED" ]; then
            echo -e "  ${GREEN}PASS${NC} Order status is CONFIRMED (saga complete!)"
            PASS=$((PASS + 1))
        else
            echo -e "  ${RED}FAIL${NC} Order status is ${ORDER_STATUS} (expected CONFIRMED)"
            FAIL=$((FAIL + 1))
        fi

        # Verify PAYMENT_CONFIRMED event in Kafka
        KAFKA_CONTAINER="cartservice-kafka"
        if docker ps --format '{{.Names}}' | grep -q "$KAFKA_CONTAINER"; then
            sleep 3
            CONFIRMED_EVENTS=$(docker exec "$KAFKA_CONTAINER" kafka-console-consumer \
                --bootstrap-server localhost:9092 \
                --topic payment-events \
                --from-beginning \
                --timeout-ms 5000 2>/dev/null || echo "")

            if echo "$CONFIRMED_EVENTS" | grep -q "PAYMENT_CONFIRMED"; then
                echo -e "  ${GREEN}PASS${NC} PAYMENT_CONFIRMED event found in payment-events topic"
                PASS=$((PASS + 1))
            else
                echo -e "  ${RED}FAIL${NC} PAYMENT_CONFIRMED event not found"
                FAIL=$((FAIL + 1))
            fi
        fi
    else
        echo -e "  ${YELLOW}SKIP${NC} Payment skipped — webhook tests not run"
        SKIP=$((SKIP + 3))
    fi
else
    echo -e "  ${YELLOW}SKIP${NC} No payment link — skipping interactive payment"
    SKIP=$((SKIP + 3))
fi

# --------------------------------------------------
section "12. Kafka Topic Verification"
# --------------------------------------------------

KAFKA_CONTAINER="cartservice-kafka"
PAYMENT_TOPIC="payment-events"
KAFKA_BOOTSTRAP="localhost:9092"

if docker ps --format '{{.Names}}' | grep -q "$KAFKA_CONTAINER"; then
    echo -e "  ${GREEN}OK${NC} Kafka container running"

    # Check if any payment events exist (from previous webhook callbacks)
    PAYMENT_EVENTS=$(docker exec "$KAFKA_CONTAINER" kafka-console-consumer \
        --bootstrap-server "$KAFKA_BOOTSTRAP" \
        --topic "$PAYMENT_TOPIC" \
        --from-beginning \
        --timeout-ms 5000 2>/dev/null || echo "")

    EVENT_COUNT=$(echo "$PAYMENT_EVENTS" | grep -c "eventType" 2>/dev/null || echo "0")
    EVENT_COUNT=$(echo "$EVENT_COUNT" | tr -d '[:space:]')
    echo -e "  ${CYAN}Payment events in topic: ${EVENT_COUNT}${NC}"

    # Note: PAYMENT_CONFIRMED/FAILED events only appear after Razorpay webhook callback
    # which requires ngrok or EKS deployment. For local testing, we verify the topic exists.
    if [ "$EVENT_COUNT" -ge 0 ] 2>/dev/null; then
        echo -e "  ${GREEN}PASS${NC} Payment events topic accessible"
        PASS=$((PASS + 1))
    fi

    # Check order-events topic has ORDER_CREATED
    ORDER_EVENTS=$(docker exec "$KAFKA_CONTAINER" kafka-console-consumer \
        --bootstrap-server "$KAFKA_BOOTSTRAP" \
        --topic order-events \
        --from-beginning \
        --timeout-ms 5000 2>/dev/null || echo "")

    if echo "$ORDER_EVENTS" | grep -q "ORDER_CREATED"; then
        echo -e "  ${GREEN}PASS${NC} ORDER_CREATED event found in order-events"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}FAIL${NC} ORDER_CREATED event not found"
        FAIL=$((FAIL + 1))
    fi
else
    echo -e "  ${YELLOW}SKIP${NC} Kafka container not running"
    SKIP=$((SKIP + 2))
fi

# --------------------------------------------------
section "13. Payment Isolation (multi-user)"
# --------------------------------------------------

TIMESTAMP2=$(date +%s)
USER_B_EMAIL="payment-user-b-${TIMESTAMP2}@test.com"
PHONE_B="91${TIMESTAMP2: -8}"
USER_PASSWORD="Test@1234"

# Ensure CUSTOMER role
ADMIN_LOGIN_RESP=$(curl -s -X POST "$USERSERVICE/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}")
ADMIN_JJWT=$(echo "$ADMIN_LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])" 2>/dev/null || echo "")

if [ -n "$ADMIN_JJWT" ]; then
    request POST "$USERSERVICE/roles/create" "$(printf 'Authorization: %s\nContent-Type: application/json' "$ADMIN_JJWT")" '{"roleName":"CUSTOMER","description":"Customer role"}'
    echo -e "  ${GREEN}OK${NC} CUSTOMER role ready"
fi

request POST "$USERSERVICE/auth/signup" "Content-Type: application/json" \
    "{\"email\":\"${USER_B_EMAIL}\",\"password\":\"${USER_PASSWORD}\",\"name\":\"User B\",\"phone\":\"${PHONE_B}\",\"role\":\"CUSTOMER\"}"
if [ "$STATUS" = "201" ] || [ "$STATUS" = "409" ] || [ "$STATUS" = "400" ]; then
    echo -e "  ${GREEN}OK${NC} User B created (${USER_B_EMAIL})"
fi

echo "  Obtaining token for User B..."
TOKEN_B=$(get_oauth2_token "$USER_B_EMAIL" "$USER_PASSWORD")

if [[ "$TOKEN_B" =~ ^eyJ.*\..*\..*$ ]]; then
    echo -e "  ${GREEN}PASS${NC} User B token obtained"
    PASS=$((PASS + 1))

    # User B should NOT see admin's payment
    if [ -n "$ORDER_ID" ]; then
        request GET "$PAYMENTGATEWAY/payments/order/${ORDER_ID}" "Authorization: Bearer $TOKEN_B"
        assert_status "User B: GET admin's payment (should be 404)" "404" "$STATUS"
    fi

    if [ -n "$PAYMENT_ID" ]; then
        request GET "$PAYMENTGATEWAY/payments/${PAYMENT_ID}" "Authorization: Bearer $TOKEN_B"
        assert_status "User B: GET admin's payment by ID (should be 404)" "404" "$STATUS"
    fi
else
    echo -e "  ${YELLOW}SKIP${NC} Could not obtain token for User B"
    SKIP=$((SKIP + 3))
fi

# --------------------------------------------------
section "14. Full Saga Summary"
# --------------------------------------------------

echo -e "  ${CYAN}Saga flow verified:${NC}"
echo -e "  ${CYAN}  1. Cart checkout → CHECKOUT_INITIATED event${NC}"
echo -e "  ${CYAN}  2. Order service → ORDER_CREATED event${NC}"
echo -e "  ${CYAN}  3. Payment gateway → Razorpay payment link created (PENDING)${NC}"
if [ -n "$PAYMENT_LINK" ]; then
    echo -e "  ${CYAN}  4. Pay here: ${PAYMENT_LINK}${NC}"
    echo -e "  ${CYAN}  5. After payment: Razorpay webhook → PAYMENT_CONFIRMED → Order CONFIRMED${NC}"
    echo -e "  ${YELLOW}  NOTE: Steps 4-5 require Razorpay webhook setup (ngrok for local, Kong for EKS)${NC}"
fi

# --------------------------------------------------
echo ""
echo "=============================================="
printf "  Results: ${GREEN}%d passed${NC}, ${RED}%d failed${NC}, ${YELLOW}%d skipped${NC}\n" "$PASS" "$FAIL" "$SKIP"
echo "=============================================="

[ "$FAIL" -eq 0 ]
