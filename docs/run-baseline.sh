#!/usr/bin/env bash
# Executable form of docs/api-baseline.http.
#
# The .http file is for interactive poking in the editor; this script is the
# thing you actually run after every migration phase. It asserts HTTP status
# codes and prints a PASS/FAIL summary.
#
# Usage:
#   ./docs/run-baseline.sh                          # against http://localhost:8080
#   BASE_URL=http://store.local ./docs/run-baseline.sh
#   DB_PASSWORD=secret ./docs/run-baseline.sh       # for the category/admin seed
#   DB_HOST=127.0.0.1 DB_PORT=3307 DB_PASSWORD=... ./docs/run-baseline.sh   # against docker compose
#
# Exit code 0 = every assertion matched expectations.
#
# NOTE: several assertions encode PRE-EXISTING BUGS as the expected result
# (marked BUG). When a migration phase fixes one, the assertion here must be
# flipped — that is the signal the fix landed. See docs/MIGRATION.md.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DB_NAME="${DB_NAME:-store_api}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-${DB_USERNAME:-root}}"
DB_PASSWORD="${DB_PASSWORD:-}"

USER_EMAIL="user@baseline.test"
USER_PASSWORD='Passw0rd!'
ADMIN_EMAIL="admin@baseline.test"
ADMIN_PASSWORD='Adm1nPass!'

PASS=0; FAIL=0; SKIP=0
declare -a FAILURES=()

c_green=$'\e[32m'; c_red=$'\e[31m'; c_yellow=$'\e[33m'; c_dim=$'\e[2m'; c_reset=$'\e[0m'

# check <label> <expected-status> <curl args...>
# Captures the body into $BODY for callers that need to extract an id.
check() {
  local label="$1" expected="$2"; shift 2
  local tmp status
  tmp="$(mktemp)"
  status="$(curl -s -o "$tmp" -w '%{http_code}' "$@")"
  BODY="$(cat "$tmp")"; rm -f "$tmp"
  if [[ "$status" == "$expected" ]]; then
    printf '%s  PASS%s  %-58s %s\n' "$c_green" "$c_reset" "$label" "$status"
    ((PASS++))
  else
    printf '%s  FAIL%s  %-58s got %s, want %s\n' "$c_red" "$c_reset" "$label" "$status" "$expected"
    printf '%s        body: %.160s%s\n' "$c_dim" "$BODY" "$c_reset"
    ((FAIL++)); FAILURES+=("$label (got $status, want $expected)")
  fi
}

note() { printf '%s  ----%s  %s\n' "$c_yellow" "$c_reset" "$1"; ((SKIP++)); }
section() { printf '\n%s== %s ==%s\n' $'\e[1m' "$1" "$c_reset"; }

jsonfield() { sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^,\"}]*\).*/\1/p" <<<"$2" | head -1; }

J='Content-Type: application/json'

printf '%sBaseline against %s%s\n' $'\e[1m' "$BASE_URL" "$c_reset"

###############################################################################
section "0. Platform (actuator + /error dispatch) — added in Phase 1"
###############################################################################
check "0.1 GET /actuator/health"               200 "$BASE_URL/actuator/health"
check "0.2 GET /actuator/health/liveness"      200 "$BASE_URL/actuator/health/liveness"
check "0.3 GET /actuator/health/readiness"     200 "$BASE_URL/actuator/health/readiness"
check "0.4 GET /actuator/metrics stays private" 401 "$BASE_URL/actuator/metrics"

###############################################################################
section "1. Registration"
###############################################################################
check "1.1 POST /users (register user)"        201 -X POST "$BASE_URL/users" -H "$J" \
  -d "{\"name\":\"Baseline User\",\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}"
check "1.2 POST /users (register admin)"       201 -X POST "$BASE_URL/users" -H "$J" \
  -d "{\"name\":\"Baseline Admin\",\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
check "1.3 POST /users duplicate email  [BUG: 200 not 409]" 200 -X POST "$BASE_URL/users" -H "$J" \
  -d "{\"name\":\"Dup\",\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}"
check "1.4 POST /users validation failure"     400 -X POST "$BASE_URL/users" -H "$J" \
  -d '{"name":"","email":"MiXeD@baseline.test","password":"abc"}'
check "1.5 POST /users malformed JSON"         400 -X POST "$BASE_URL/users" -H "$J" \
  -d '{ "name": "broken", }'

###############################################################################
section "2. Seed (categories + admin promotion — not creatable via API)"
###############################################################################
if command -v mysql >/dev/null && mysql --protocol=TCP -h "$DB_HOST" -P "$DB_PORT" \
      -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
      < "$(dirname "$0")/seed-baseline.sql" >/dev/null 2>&1; then
  printf '%s  PASS%s  %-58s seeded\n' "$c_green" "$c_reset" "2.1 seed categories + promote admin"
  ((PASS++))
else
  note "2.1 seed FAILED — set DB_PASSWORD. Admin/product tests will fail."
fi

###############################################################################
section "3. Authentication"
###############################################################################
check "3.1 POST /auth/login (user)"            200 -X POST "$BASE_URL/auth/login" -H "$J" \
  -d "{\"email\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}"
TOKEN="$(jsonfield token "$BODY")"

check "3.2 POST /auth/login (admin)"           200 -X POST "$BASE_URL/auth/login" -H "$J" \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
ADMIN_TOKEN="$(jsonfield token "$BODY")"

AUTH="Authorization: Bearer $TOKEN"
AUTH_ADMIN="Authorization: Bearer $ADMIN_TOKEN"

check "3.3 POST /auth/login wrong password"    401 -X POST "$BASE_URL/auth/login" -H "$J" \
  -d "{\"email\":\"$USER_EMAIL\",\"password\":\"nope\"}"
# Was 401 before Phase 1: MissingRequestCookieException is not handled by
# GlobalExceptionHandler, so Boot forwarded to /error, which no SecurityRules
# bean permitted -- anyRequest().authenticated() masked the 400 as 401.
# PlatformSecurityRules now permits /error, so the real status surfaces.
check "3.4 POST /auth/refresh no cookie"       400 -X POST "$BASE_URL/auth/refresh"
check "3.5 GET /auth/me"                       200 "$BASE_URL/auth/me" -H "$AUTH"
USER_ID="$(jsonfield id "$BODY")"
check "3.6 GET /auth/me (admin)"               200 "$BASE_URL/auth/me" -H "$AUTH_ADMIN"
ADMIN_ID="$(jsonfield id "$BODY")"
check "3.7 GET /auth/me no token"              401 "$BASE_URL/auth/me"
check "3.8 GET /auth/me garbage token"         401 "$BASE_URL/auth/me" -H "Authorization: Bearer not.a.token"

###############################################################################
section "4. Products"
###############################################################################
check "4.1 GET /products (public)"             200 "$BASE_URL/products"
check "4.2 POST /products as ADMIN"            201 -X POST "$BASE_URL/products" -H "$J" -H "$AUTH_ADMIN" \
  -d '{"name":"Baseline Widget","price":19.99,"description":"baseline","categoryId":1}'
PRODUCT_ID="$(jsonfield id "$BODY")"
check "4.3 POST /products second"              201 -X POST "$BASE_URL/products" -H "$J" -H "$AUTH_ADMIN" \
  -d '{"name":"Baseline Gadget","price":5.50,"description":"baseline 2","categoryId":2}'
PRODUCT_ID2="$(jsonfield id "$BODY")"
check "4.4 POST /products as USER"             403 -X POST "$BASE_URL/products" -H "$J" -H "$AUTH" \
  -d '{"name":"Rejected","price":1.00,"description":"x","categoryId":1}'
check "4.5 POST /products anonymous"           401 -X POST "$BASE_URL/products" -H "$J" \
  -d '{"name":"Rejected","price":1.00,"description":"x","categoryId":1}'
check "4.6 POST /products bad category"        400 -X POST "$BASE_URL/products" -H "$J" -H "$AUTH_ADMIN" \
  -d '{"name":"Bad","price":1.00,"description":"x","categoryId":99}'
check "4.7 GET /products?categoryId=1"         200 "$BASE_URL/products?categoryId=1"
check "4.8 PUT /products/{id} as ADMIN"        200 -X PUT "$BASE_URL/products/$PRODUCT_ID" -H "$J" -H "$AUTH_ADMIN" \
  -d '{"name":"Baseline Widget v2","price":24.99,"description":"updated","categoryId":1}'
check "4.9 PUT /products/999999"               404 -X PUT "$BASE_URL/products/999999" -H "$J" -H "$AUTH_ADMIN" \
  -d '{"name":"Ghost","price":1.00,"description":"x","categoryId":1}'

###############################################################################
section "5. Users"
###############################################################################
check "5.1 GET /users"                         200 "$BASE_URL/users" -H "$AUTH"
check "5.2 GET /users?sortBy=name"             200 "$BASE_URL/users?sortBy=name" -H "$AUTH"
check "5.3 GET /users?sortBy=email"            200 "$BASE_URL/users?sortBy=email" -H "$AUTH"
check "5.4 GET /users/{ownId}"                 200 "$BASE_URL/users/$USER_ID" -H "$AUTH"
check "5.5 GET /users/999999"                  404 "$BASE_URL/users/999999" -H "$AUTH"
check "5.6 GET /users anonymous"               401 "$BASE_URL/users"
check "5.7 PUT /users/{ownId}"                 200 -X PUT "$BASE_URL/users/$USER_ID" -H "$J" -H "$AUTH" \
  -d "{\"name\":\"Baseline Renamed\",\"email\":\"$USER_EMAIL\"}"

###############################################################################
section "6. Carts"
###############################################################################
check "6.1 POST /carts (public)"               201 -X POST "$BASE_URL/carts"
CART_ID="$(jsonfield id "$BODY")"
check "6.2 POST /carts/{id}/items"             201 -X POST "$BASE_URL/carts/$CART_ID/items" -H "$J" \
  -d "{\"productId\":$PRODUCT_ID}"
check "6.3 POST same item again (qty 2)"       201 -X POST "$BASE_URL/carts/$CART_ID/items" -H "$J" \
  -d "{\"productId\":$PRODUCT_ID}"
check "6.4 POST second product"                201 -X POST "$BASE_URL/carts/$CART_ID/items" -H "$J" \
  -d "{\"productId\":$PRODUCT_ID2}"
check "6.5 GET /carts/{id}"                    200 "$BASE_URL/carts/$CART_ID"
check "6.6 POST update quantity"               200 -X POST "$BASE_URL/carts/$CART_ID/items/$PRODUCT_ID" -H "$J" \
  -d '{"quantity":5}'
check "6.7 quantity 0 rejected"                400 -X POST "$BASE_URL/carts/$CART_ID/items/$PRODUCT_ID" -H "$J" \
  -d '{"quantity":0}'
check "6.8 quantity 101 rejected"              400 -X POST "$BASE_URL/carts/$CART_ID/items/$PRODUCT_ID" -H "$J" \
  -d '{"quantity":101}'
check "6.9 add nonexistent product"            404 -X POST "$BASE_URL/carts/$CART_ID/items" -H "$J" \
  -d '{"productId":999999}'
check "6.10 GET nonexistent cart"              404 "$BASE_URL/carts/00000000-0000-0000-0000-000000000000"
check "6.11 DELETE one item"                   204 -X DELETE "$BASE_URL/carts/$CART_ID/items/$PRODUCT_ID2"

###############################################################################
section "7. Checkout  (needs live Stripe test keys)"
###############################################################################
check "7.1 POST /carts (checkout cart)"        201 -X POST "$BASE_URL/carts"
CHECKOUT_CART="$(jsonfield id "$BODY")"
check "7.2 add item to checkout cart"          201 -X POST "$BASE_URL/carts/$CHECKOUT_CART/items" -H "$J" \
  -d "{\"productId\":$PRODUCT_ID}"
check "7.3 POST /checkout anonymous"           401 -X POST "$BASE_URL/checkout" -H "$J" \
  -d "{\"cartId\":\"$CHECKOUT_CART\"}"
check "7.4 POST /checkout missing cartId"      400 -X POST "$BASE_URL/checkout" -H "$J" -H "$AUTH" -d '{}'
check "7.5 POST /checkout nonexistent cart"    400 -X POST "$BASE_URL/checkout" -H "$J" -H "$AUTH" \
  -d '{"cartId":"00000000-0000-0000-0000-000000000000"}'

tmp="$(mktemp)"
st="$(curl -s -o "$tmp" -w '%{http_code}' -X POST "$BASE_URL/checkout" -H "$J" -H "$AUTH" \
      -d "{\"cartId\":\"$CHECKOUT_CART\"}")"
CO_BODY="$(cat "$tmp")"; rm -f "$tmp"
ORDER_ID="$(jsonfield orderId "$CO_BODY")"
if [[ "$st" == "200" ]]; then
  printf '%s  PASS%s  %-58s 200 (order %s)\n' "$c_green" "$c_reset" "7.6 POST /checkout" "$ORDER_ID"; ((PASS++))
  check "7.7 POST /checkout now-empty cart"    400 -X POST "$BASE_URL/checkout" -H "$J" -H "$AUTH" \
    -d "{\"cartId\":\"$CHECKOUT_CART\"}"
else
  note "7.6 POST /checkout -> $st (Stripe keys not live). Order tests degraded."
fi

###############################################################################
section "8. Orders"
###############################################################################
check "8.1 GET /orders"                        200 "$BASE_URL/orders" -H "$AUTH"
check "8.2 GET /orders anonymous"              401 "$BASE_URL/orders"
check "8.3 GET /orders/999999"                 404 "$BASE_URL/orders/999999" -H "$AUTH"
if [[ -n "${ORDER_ID:-}" ]]; then
  check "8.4 GET /orders/{id} as owner"        200 "$BASE_URL/orders/$ORDER_ID" -H "$AUTH"
  check "8.5 GET /orders/{id} as non-owner"    403 "$BASE_URL/orders/$ORDER_ID" -H "$AUTH_ADMIN"
else
  note "8.4/8.5 skipped — no order was created"
fi

###############################################################################
section "9. Admin + authorization matrix"
###############################################################################
check "9.1 GET /admin/hello as ADMIN"          200 "$BASE_URL/admin/hello" -H "$AUTH_ADMIN"
check "9.2 GET /admin/hello as USER"           403 "$BASE_URL/admin/hello" -H "$AUTH"
check "9.3 GET /admin/hello anonymous"         401 "$BASE_URL/admin/hello"
check "9.4 GET /swagger-ui/index.html"         200 "$BASE_URL/swagger-ui/index.html"
check "9.5 GET /v3/api-docs"                   200 "$BASE_URL/v3/api-docs"
check "9.6 GET unmapped path -> 401 not 404"   401 "$BASE_URL/this/does/not/exist"

# --- assertions that encode CURRENT BROKEN BEHAVIOR -------------------------
# Flip these to 403 in Phase 4 / Phase 7 when the fixes land.
check "9.7 BUG user reads admin record (Phase 4 -> 403)"   200 "$BASE_URL/users/$ADMIN_ID" -H "$AUTH"
check "9.8 BUG user overwrites admin  (Phase 4 -> 403)"    200 -X PUT "$BASE_URL/users/$ADMIN_ID" -H "$J" -H "$AUTH" \
  -d "{\"name\":\"Hijacked\",\"email\":\"$ADMIN_EMAIL\"}"
check "9.9 BUG anonymous reads any cart (Phase 7 -> 403)"  200 "$BASE_URL/carts/$CART_ID"

###############################################################################
section "10. Destructive (runs last)"
###############################################################################
check "10.1 POST change-password"              200 -X POST "$BASE_URL/users/$USER_ID/change-password" -H "$J" -H "$AUTH" \
  -d "{\"oldPassword\":\"$USER_PASSWORD\",\"newPassword\":\"NewPassw0rd!\"}"
check "10.2 BUG login w/ new password fails (Phase 4 -> 200)" 401 -X POST "$BASE_URL/auth/login" -H "$J" \
  -d "{\"email\":\"$USER_EMAIL\",\"password\":\"NewPassw0rd!\"}"
check "10.3 DELETE /carts/{id}/items"          204 -X DELETE "$BASE_URL/carts/$CART_ID/items"
check "10.4 GET cart is empty"                 200 "$BASE_URL/carts/$CART_ID"
check "10.5 DELETE /products/{id} as ADMIN"    204 -X DELETE "$BASE_URL/products/$PRODUCT_ID2" -H "$AUTH_ADMIN"
check "10.6 DELETE /products/{id} as USER"     403 -X DELETE "$BASE_URL/products/$PRODUCT_ID" -H "$AUTH"
check "10.7 DELETE /products/999999"           404 -X DELETE "$BASE_URL/products/999999" -H "$AUTH_ADMIN"

###############################################################################
printf '\n%s================================================%s\n' $'\e[1m' "$c_reset"
printf '  %sPASS %d%s   %sFAIL %d%s   %sSKIP %d%s\n' \
  "$c_green" "$PASS" "$c_reset" "$c_red" "$FAIL" "$c_reset" "$c_yellow" "$SKIP" "$c_reset"
if ((FAIL)); then
  printf '\n  Failures:\n'
  for f in "${FAILURES[@]}"; do printf '    - %s\n' "$f"; done
fi
printf '%s================================================%s\n' $'\e[1m' "$c_reset"

# Clean up so the next run starts from a known state.
curl -s -o /dev/null -X DELETE "$BASE_URL/users/$USER_ID"  -H "$AUTH"       2>/dev/null
curl -s -o /dev/null -X DELETE "$BASE_URL/users/$ADMIN_ID" -H "$AUTH_ADMIN" 2>/dev/null

((FAIL == 0))
