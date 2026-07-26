# Monolith → Microservices on local Kubernetes — Phased Migration Guide

## Context

`spring-api` is a Spring Boot 4.0.1 / Java 25 e-commerce monolith (`com.rumeshchathuranga:store`) organized as vertical feature slices (`auth`, `users`, `products`, `carts`, `orders`, `payments`, `admin`, `common`). It runs against a single MySQL schema `store_api` with 10 tables managed by Flyway. There is no Dockerfile, no CI, no k8s manifests, no actuator, and effectively no test suite (one `contextLoads`).

The goal is to decompose it into 4 independently deployable services running on a local minikube cluster, primarily as a learning exercise. The slice-per-feature layout and the pluggable `SecurityRules` SPI mean the seams are already half-cut — but the *data* is not: every candidate boundary is crossed by a physical foreign key, and `CheckoutService` spans carts + orders + auth + payments inside one `@Transactional`.

**Decisions already made (do not re-litigate):**

| Decision | Choice |
|---|---|
| Granularity | 4 services: `identity`, `catalog`, `cart`, `order` |
| Data | Database per service (separate MySQL instance each) |
| Comms | Synchronous REST over k8s DNS first; Kafka as a late optional phase |
| Cluster | minikube |
| Repo layout | Monorepo, Maven multi-module, shared `common-security` module |
| Edge | Spring Cloud Gateway pod behind nginx Ingress; JWT validated at edge **and** in each service |
| Pre-existing bugs | Fixed in whichever phase already touches that code |

**Target topology**

```
                 minikube
  Ingress(nginx) ──► gateway ──┬──► identity-service ──► identity-mysql
   store.local                 ├──► catalog-service  ──► catalog-mysql
                               ├──► cart-service     ──► cart-mysql
                               └──► order-service    ──► order-mysql
                                          │
                                          ├─ REST ─► cart-service
                                          ├─ REST ─► catalog-service
                                          └─ HTTPS ► Stripe
```

---

## How to use this guide

Each phase is sized for **one chat session**. Every phase has a copy-pasteable **Kickoff prompt** — start a fresh chat, paste it, and the agent has enough context to work.

**Rules that apply to every phase:**
- Work on a branch per phase: `git checkout -b phase-N-<name>`. Commit at the end of the phase, only once its Verify section passes.
- Never start phase N+1 until phase N's Verify section is green. Each phase leaves the system in a runnable state.
- Phases 0–3 change *zero* behavior. That is deliberate — get the infrastructure right while the app is still simple.

This guide lives at `docs/MIGRATION.md` so it travels with the code. Keep it updated as you go — if a phase turns out differently than planned, edit the phase before moving on.

---

## Phase 0 — Tooling and baseline

**Goal:** Working local toolchain and a verified baseline so you can tell migration breakage from pre-existing breakage.

**Tasks**
1. Install and verify: `docker` (or podman + `podman-docker`), `minikube`, `kubectl`, `helm`, `k9s` (optional but worth it), `stern` (optional).
2. `minikube start --cpus=4 --memory=8g --driver=docker` — then `minikube addons enable ingress metrics-server`.
   - If your laptop can't spare 8 GB, use `--memory=6g` and plan to scale replicas to 1 everywhere.
3. Verify the monolith still runs today: `./mvnw spring-boot:run` against local MySQL, hit `GET /products` and `POST /auth/login`.
4. Capture a baseline: write `docs/api-baseline.http` (or a Postman/Bruno collection) exercising every endpoint listed in [README.md](../README.md). This is your regression suite for the whole migration — you have no automated tests.
5. Write `docs/seed-baseline.sql` — see the gotcha below.

**Two things the API cannot create, discovered while writing the baseline:**
- **Categories.** There is no category controller anywhere in the codebase, but `POST /products` returns 400 unless `categoryId` matches an existing row. Categories must be seeded with SQL.
- **An ADMIN user.** `UserService.registerUser` hardcodes `user.setRole(Role.USER)`, so `/admin/**` and the product write endpoints are unreachable through the API alone. The admin must be promoted with SQL.

Both live in `docs/seed-baseline.sql`. Each new service inherits this problem — when catalog-service gets its own DB in Phase 6, it needs its own category seed, and identity-service in Phase 4 needs the admin promotion.

**Machine sizing (recorded 2026-07-25):** this laptop has 16 cores / 15 GB RAM, but ~9.6 GB is normally in use by other project containers (`helpdesk-db`, `codehealth-db`, `its-main-b3-public-app`). The cluster was created with `--memory=4g`, which is fine through Phase 3 (one app pod + one MySQL pod). **By Phase 9 you need 9 pods** — stop those other containers, or recreate the cluster with `minikube delete && minikube start --memory=8g`.

**Verify:** `minikube status` all Running; `kubectl get nodes` Ready; the baseline collection passes against the local monolith.

<details><summary><b>Kickoff prompt</b></summary>

> I'm starting Phase 0 of the microservices migration described in `docs/MIGRATION.md`. Help me set up minikube + kubectl + helm + k9s on Fedora, verify the cluster is healthy with the ingress and metrics-server addons, and then build `docs/api-baseline.http` — a complete HTTP request collection covering every endpoint in the monolith (auth, users, products, carts, orders, checkout, admin), including the login-then-use-token flow. Read `README.md` and the controllers to get the endpoints right.

</details>

---

## Phase 1 — Containerize and make config 12-factor

**Goal:** One Docker image, all config from environment variables, health endpoints for k8s probes. Still a monolith.

The blocker here is [SpringApiApplication.java](../src/main/java/com/rumeshchathuranga/springapi/SpringApiApplication.java): it loads `.env` via dotenv-java into **JVM system properties before `SpringApplication.run`**. That's hostile to containers. Since dotenv uses `ignoreIfMissing()` and Spring resolves `${JWT_SECRET}` from the environment anyway, the whole block can be deleted with no loss.

**Tasks**
1. Delete the dotenv block from `SpringApiApplication.main` and drop `io.github.cdimascio:dotenv-java` from [pom.xml](../pom.xml). Update the `@BeforeAll` in `SpringApiApplicationTests` accordingly.
2. Externalize DB config in [application-dev.yaml](../src/main/resources/application-dev.yaml): `${DB_URL:jdbc:mysql://localhost:3306/store_api}`, `${DB_USERNAME:root}`, `${DB_PASSWORD}`. Remove the hardcoded `Ruma@1220`. Do the same for the `flyway-maven-plugin` block in `pom.xml` (use `${env.DB_*}` properties).
   - Note: that password is already in git history. Rotating your local MySQL root password is out of scope for this phase but worth doing.
3. Fill in [application-prod.yaml](../src/main/resources/application-prod.yaml) — it currently sets only the datasource URL, no username/password/driver.
4. Add `spring-boot-starter-actuator`. Configure:
   ```yaml
   management:
     endpoints.web.exposure.include: health,info,metrics,prometheus
     endpoint.health.probes.enabled: true
     health.livenessState.enabled: true
     health.readinessState.enabled: true
   server.shutdown: graceful
   spring.lifecycle.timeout-per-shutdown-phase: 30s
   ```
5. Make the refresh-token cookie's `Secure` flag configurable — `AuthController.login` hardcodes `setSecure(true)`, which silently breaks the refresh flow over plain HTTP in-cluster. Drive it from `app.cookie.secure:${COOKIE_SECURE:true}` and set it false in the dev overlay. Also set `SameSite=Lax`.
6. **Permit `/error` in the security chain.** Found during Phase 0: any exception not handled by `GlobalExceptionHandler` or a controller-local `@ExceptionHandler` gets forwarded by Boot to `/error`, which no `SecurityRules` bean permits — so `anyRequest().authenticated()` rejects the forward and the client sees **401 instead of the real status**. Reproduce with `POST /auth/refresh` (no cookie): the app logs `Resolved [MissingRequestCookieException]` and `Response: 400`, but the caller gets 401. Add `registry.requestMatchers("/error").permitAll()` to a shared rule bean. This matters more after the split — every new service inherits the same masking, and `common-security` (Phase 3) is the right home for the fix.
7. Write a multi-stage `Dockerfile` at repo root:
   - Stage 1 `eclipse-temurin:25-jdk`: copy `.mvn`, `mvnw`, `pom.xml` first and run `./mvnw dependency:go-offline` so dependency layers cache; then copy `src` and `./mvnw package -DskipTests`.
   - Stage 2 `eclipse-temurin:25-jre`: extract Spring Boot layers (`java -Djarmode=tools -jar app.jar extract --layers --destination /app`) and copy each layer as its own `COPY` for cache efficiency. Run as a non-root user. `EXPOSE 8080`. Entrypoint `java -XX:MaxRAMPercentage=75 -jar app.jar` (or the extracted launcher).
8. Write `.dockerignore` (exclude `target/`, `.git/`, `.env`, `.idea/`).
9. Write `docker-compose.yml`: `mysql:8.4` with a healthcheck + named volume, and the app with `depends_on: condition: service_healthy`, env vars for `DB_*`, `JWT_SECRET`, `STRIPE_*`.

**Verify:** `docker compose up --build` → app healthy; `curl localhost:8080/actuator/health` returns `{"status":"UP"}`; `/actuator/health/readiness` and `/actuator/health/liveness` both respond; the Phase 0 baseline collection passes against the containerized app; `docker compose down -v && docker compose up` re-runs Flyway cleanly on an empty DB.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 1 of `docs/MIGRATION.md`: containerize the monolith and make its config 12-factor. Remove the dotenv-java bootstrapping from `SpringApiApplication`, externalize all DB credentials and secrets to env vars (removing the hardcoded password from `application-dev.yaml` and the flyway-maven-plugin block in `pom.xml`), fill in `application-prod.yaml`, add spring-boot-starter-actuator with liveness/readiness probe groups and graceful shutdown, make the refresh-token cookie's Secure flag configurable, then write a layered multi-stage Dockerfile, `.dockerignore`, and a `docker-compose.yml` with MySQL. Verify with `docker compose up --build` and the baseline collection in `docs/api-baseline.http`.

</details>

---

## Phase 2 — Run the monolith on minikube

**Goal:** Learn Kubernetes fundamentals with only one application to reason about. No code changes.

**Tasks**
1. Create `k8s/` with a `store` Namespace.
2. MySQL: `StatefulSet` (1 replica) + headless `Service` + `volumeClaimTemplates` (PVC via minikube's `standard` StorageClass) + a `Secret` holding the root password.
3. App: `ConfigMap` (non-secret config — profile, website URL, JWT expirations), `Secret` (`JWT_SECRET`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET_KEY`, `DB_PASSWORD`), `Deployment` (1 replica), `Service` (ClusterIP :8080).
4. Probes on the Deployment — Boot 4 + JPA + Flyway is slow to start, so a `startupProbe` matters:
   ```yaml
   startupProbe:   { httpGet: {path: /actuator/health, port: 8080}, failureThreshold: 30, periodSeconds: 5 }
   livenessProbe:  { httpGet: {path: /actuator/health/liveness,  port: 8080}, periodSeconds: 10 }
   readinessProbe: { httpGet: {path: /actuator/health/readiness, port: 8080}, periodSeconds: 5 }
   ```
5. `Ingress` (nginx class) with host `store.local`. Add `$(minikube ip) store.local` to `/etc/hosts`.
6. Image workflow — no registry needed:
   ```bash
   eval $(minikube docker-env)
   docker build -t store-monolith:dev .
   ```
   Set `imagePullPolicy: IfNotPresent` (never `Always`, or k8s will try Docker Hub and fail).
7. Write `docs/k8s-cheatsheet.md` covering the commands you'll live in: `kubectl get/describe/logs -f/exec -it/port-forward/rollout restart/rollout status`, `kubectl get events --sort-by=.lastTimestamp`, and `k9s`.

**Verify:** `kubectl get pods -n store` all Running/Ready; `curl http://store.local/products` returns data; `kubectl logs` shows Flyway migrations applied; `kubectl delete pod <app-pod>` → it self-heals and comes back Ready; `kubectl rollout restart deploy/store-monolith -n store` succeeds.

**Common failures to expect:** `ImagePullBackOff` (forgot `eval $(minikube docker-env)` or wrong pull policy); `CrashLoopBackOff` because MySQL wasn't ready — either add an initContainer that waits on the DB port, or accept that k8s restarts until it succeeds.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 2 of `docs/MIGRATION.md`: deploy the containerized monolith to minikube. Create a `k8s/` directory with a `store` namespace, a MySQL StatefulSet with a PVC and Secret, and the app's ConfigMap/Secret/Deployment/Service/Ingress — including startup, liveness, and readiness probes against the actuator endpoints. Use the `eval $(minikube docker-env)` local-image workflow with `imagePullPolicy: IfNotPresent`. Also write `docs/k8s-cheatsheet.md`. Verify pods are Ready and `http://store.local/products` works end to end.

</details>

---

## Phase 3 — Maven multi-module + extract `common-security`

**Goal:** Restructure into the monorepo skeleton and pull the JWT machinery into a shared library. Still exactly one deployable.

This is the pivot point. Every service will need to validate JWTs, and today that logic is entangled with the `users` package (`Jwt.getRole()` returns `users.Role`, `AuthService.getCurrentUser()` hits `UserRepository`).

**Target layout**
```
spring-api/
├── pom.xml                 (packaging: pom, parent, <modules>)
├── common-security/
├── store-monolith/         (everything else, for now)
├── k8s/
└── docs/
```

**Tasks**
1. Convert root [pom.xml](../pom.xml) to `packaging: pom` with `<modules>`, moving all shared dependency/plugin config into `<dependencyManagement>` and `<pluginManagement>`. Add Testcontainers BOM to `dependencyManagement` now — you'll need it in every extraction phase.
2. Create `common-security` (plain jar, not a Boot app) containing:
   - `SecurityRules` SPI (from [common/SecurityRules.java](../src/main/java/com/rumeshchathuranga/springapi/common/SecurityRules.java))
   - `JwtAuthenticationFilter`, `JwtService`, `JwtConfig`, `Jwt` (from `auth/`)
   - **`Role` enum moved out of `users/`** into `common-security` — this is what currently welds auth to users
   - `ErrorDto`, `GlobalExceptionHandler`, `SwaggerSecurityRules`, `OpenApiConfig`
   - A new `CurrentUser` component: reads the `Long` principal and `ROLE_*` authority straight from the `SecurityContext`, with `id()` and `isAdmin()`. **This replaces `AuthService.getCurrentUser()` for every service except identity** — no DB hit, no `User` entity needed.
   - A `BaseSecurityConfig` that reproduces the current `SecurityConfig` chain (STATELESS, CSRF off, `List<SecurityRules>` then `anyRequest().authenticated()`, 401 entry point / 403 handler, JWT filter) **minus** the `DaoAuthenticationProvider`/`UserDetailsService`/`PasswordEncoder` beans — only identity-service needs those.
   - Register it via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` so a service only needs the dependency.
3. Move everything else into `store-monolith` and add the `common-security` dependency. `SecurityConfig` in the monolith shrinks to just the auth-provider beans.
4. Update the `Dockerfile` for a multi-module build (`./mvnw -pl store-monolith -am package -DskipTests`) and adjust the jar path.
5. Add a `Makefile` with `build`, `image`, `deploy`, `logs`, `restart` targets — you'll run these hundreds of times.

**Known hazard:** `CLAUDE.md` notes that `SecurityRules` beans are applied in injection order, which is undefined, so matchers must not overlap. Once rules are split across services this stops mattering *between* services but still matters *within* one.

**Verify:** `./mvnw clean install` succeeds; `make image deploy` puts the same working app back on minikube; the Phase 0 baseline collection passes unchanged. **Nothing about the API should have changed.**

<details><summary><b>Kickoff prompt</b></summary>

> Phase 3 of `docs/MIGRATION.md`: restructure the repo into a Maven multi-module monorepo. Convert the root pom to packaging `pom` with dependencyManagement (add the Testcontainers BOM), create a `common-security` library module containing the SecurityRules SPI, JwtAuthenticationFilter/JwtService/JwtConfig/Jwt, the `Role` enum moved out of the `users` package, ErrorDto, GlobalExceptionHandler, OpenApiConfig, a new `CurrentUser` helper that reads the Long principal from the SecurityContext without a DB lookup, and a `BaseSecurityConfig` auto-configuration. Move everything else into a `store-monolith` module. Update the Dockerfile for the multi-module build and add a Makefile. This phase must not change API behavior at all — verify with `docs/api-baseline.http`.

</details>

---

## Phase 4 — Extract `identity-service` (auth + users)

**Goal:** First real split. Two apps, two databases.

Identity is the right first extraction because the JWT already carries `sub`(userId), `email`, `name`, and `role` — so after this split, the remaining monolith needs **zero** synchronous calls to identity for normal operation. It just validates the token.

**Moves into `identity-service`:** all of `auth/` and `users/` (User, Address, Profile, controllers, services, repositories, `UserMapper`, DTOs, `Lowercase`/`LowercaseValidator`, exceptions, `AuthSecurityRules`, `UserSecurityRules`) plus `admin/` (it's just a smoke-test endpoint; keep it here or delete it).

**Database split**
- New `identity_db`: `users`, `addresses`, `profiles`, and `user_favorite_products` (the old `wishlist` table, with the `product_id` FK **dropped** — plain `BIGINT`).
- New Flyway baseline `V1__identity_schema.sql` derived from `V1__initial_migration.sql` + `V3__add_role_to_users.sql`.
- Monolith DB: drop the `orders.customer_id → users.id` FK; drop `users`, `addresses`, `profiles`, `wishlist`.
- Write `docs/data-migration/phase4.sql` to copy existing rows across (`mysqldump --no-create-info` of the three tables → import). Keep IDs identical.

**Code changes in the remaining monolith**
- `Order.customer` (`@ManyToOne User`) → `private Long customerId`. `isPlacedBy(User)` → `isPlacedBy(Long userId)`.
- `OrderRepository.getOrdersByCustomer(User)` → `findByCustomerId(Long)`.
- `OrderService` and `CheckoutService` drop `AuthService`; use `CurrentUser.id()` from `common-security`.
- `OrderMapper`/`OrderDto` — check whether they expose customer data; if so, either drop it or carry `customerEmail` from the JWT claim at order-creation time.
- Delete `User`, `UserRepository`, `UserServiceImpl`, and the `DaoAuthenticationProvider`/`AuthenticationManager`/`PasswordEncoder` beans. The monolith becomes a pure token validator.
- Remove `@ManyToMany favoriteProducts` from the picture entirely on this side.

**Bug fixes owed this phase (all in code you're already touching)**
- [users/UserService.java](../src/main/java/com/rumeshchathuranga/springapi/users/UserService.java) `changePassword` writes `request.getNewPassword()` **unencoded** — encode it with the `PasswordEncoder`.
- `UserController` imports `java.nio.file.AccessDeniedException` while the service throws `org.springframework.security.access.AccessDeniedException`, so that handler never fires. Fix the import.
- `GET/PUT/DELETE /users/{id}` and `POST /users/{id}/change-password` have **no ownership or role check** — any authenticated user can read, modify, or delete any other user. Add `@EnableMethodSecurity` + `@PreAuthorize("#id == authentication.principal or hasRole('ADMIN')")`, or an explicit check against `CurrentUser.id()`.
- `UserMapper.toDto` fabricates `createdAt` as `LocalDateTime.now()` though no such column exists. Either add a `created_at` column in the new baseline migration (preferred) or drop the field from `UserDto`.

**Kubernetes**
- New `identity-mysql` StatefulSet + Secret + PVC, and `identity-service` Deployment/Service/ConfigMap/Secret.
- Both services need the **same `JWT_SECRET`** — the current setup is HMAC (symmetric) via `JwtConfig.getSecretKey()`. Put it in one Secret and mount it into both. (Asymmetric RS256 + JWKS is the better long-term answer; note it as future work, don't do it now.)
- No Ingress routing work yet — that's Phase 5. For now expose identity via `kubectl port-forward` to test.

**Tests (start here — you have none):** `@DataJpaTest` + Testcontainers MySQL for `UserRepository`, and `@WebMvcTest` for `AuthController`/`UserController` covering login, refresh, register, and the new ownership checks.

**Verify:** both pods Ready; `POST /auth/login` against identity returns a token; that same token is accepted by the monolith on `GET /orders`; `GET /orders/{id}` still 403s for a non-owner; the ownership checks reject cross-user access; changing a password then logging in with the new password works (proves the encoding fix).

<details><summary><b>Kickoff prompt</b></summary>

> Phase 4 of `docs/MIGRATION.md`: extract `identity-service` from the monolith. Create a new Maven module containing the `auth/`, `users/`, and `admin/` packages with its own `identity_db` MySQL instance and a fresh Flyway baseline (users, addresses, profiles, and the wishlist table renamed to `user_favorite_products` with the product_id FK dropped). In the remaining monolith, replace `Order.customer` (@ManyToOne User) with a plain `Long customerId`, drop the `orders.customer_id` FK, and replace all `AuthService.getCurrentUser()` usage with the `CurrentUser` helper from common-security — the monolith should no longer have a User entity or any authentication provider beans, only JWT validation.
>
> While you're in this code, fix these pre-existing bugs: `UserService.changePassword` stores the password unencoded; `UserController` catches `java.nio.file.AccessDeniedException` instead of the Spring Security one; `/users/{id}` GET/PUT/DELETE and change-password have no ownership or admin check; `UserMapper.toDto` fabricates `createdAt` for a column that doesn't exist.
>
> Add k8s manifests for identity-mysql and identity-service, sharing the JWT_SECRET Secret with the monolith. Write a data-migration SQL script preserving IDs. Add Testcontainers-based repository tests and @WebMvcTest controller tests. Verify a token minted by identity-service is accepted by the monolith.

</details>

---

## Phase 5 — API Gateway and Ingress routing

**Goal:** One entry point. Now that there are two services, routing has to become real.

**Tasks**
1. New `gateway` module — Spring Cloud Gateway (reactive/webflux server variant).
   - ⚠️ **Version risk, check this first:** Spring Boot 4.0.1 is very new; confirm the Spring Cloud release train's Boot 4 compatibility before writing code. If no compatible train exists yet, the fallbacks are (a) pin the gateway module alone to the latest Boot 3.5.x — it shares nothing but the JWT secret, so this is harmless; or (b) skip the gateway pod and do path-based routing in the nginx Ingress with each service validating its own JWT. Decide this in the first 10 minutes of the phase, don't fight it.
2. Routes (Kubernetes DNS — no Eureka, no discovery client needed):
   ```yaml
   /auth/**, /users/**, /admin/**  ->  http://identity-service:8080
   /products/**                    ->  http://catalog-service:8080   # added Phase 6
   /carts/**                       ->  http://cart-service:8080      # added Phase 7
   /orders/**, /checkout/**        ->  http://order-service:8080
   ```
   Until Phase 6/7, everything not `/auth|/users|/admin` routes to `store-monolith`.
3. Global JWT filter at the gateway: validate the token once, reject 401 early, and forward `X-User-Id` / `X-User-Role` downstream. Services **still** validate independently — the gateway is not a security boundary inside the cluster.
4. CORS configured centrally at the gateway (there is currently no CORS config anywhere in the codebase).
5. Move the `Ingress` to point at `gateway` only. Services become ClusterIP-internal.
6. Aggregated Swagger: configure `springdoc.swagger-ui.urls` on the gateway listing each service's `/v3/api-docs`, so one UI at `http://store.local/swagger-ui.html` covers everything.
7. Optional but useful: add rate limiting and a `X-Request-Id` correlation header at the gateway.

**Verify:** Every request in `docs/api-baseline.http` works through `http://store.local/...` with no direct port-forwards; a request with no token to a protected route gets 401 *from the gateway*; the aggregated Swagger UI lists both services' endpoints.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 5 of `docs/MIGRATION.md`: add a Spring Cloud Gateway module in front of identity-service and the remaining monolith. First verify Spring Cloud Gateway's compatibility with Spring Boot 4.0.1 — if there's no compatible release train, pin just the gateway module to Boot 3.5.x and tell me. Configure path-based routes over Kubernetes DNS service names, a global JWT-validation filter that forwards X-User-Id and X-User-Role downstream (services still validate independently), central CORS config, and aggregated springdoc Swagger UI listing each service's /v3/api-docs. Repoint the nginx Ingress at the gateway only. Verify every request in `docs/api-baseline.http` works through `http://store.local`.

</details>

---

## Phase 6 — Extract `catalog-service` (products)

**Goal:** The first extraction that forces a real synchronous inter-service call. This is the hardest data phase.

`products` has no outbound dependencies, but `cart_items.product_id` and `order_items.product_id` both FK into it, and `CartService` injects `ProductRepository` directly.

**Moves into `catalog-service`:** all of `products/` (Product, Category, ProductController, ProductRepository, CategoryRepository, ProductMapper, ProductDto, ProductNotFoundException, ProductSecurityRules). Note there is no `ProductService` today — the controller talks to repositories directly. Add one during the move.

**Database split**
- New `catalog_db`: `products`, `categories`. Flyway baseline from the `V1` subset.
- Monolith DB: drop the `cart_items.product_id → products` and `order_items.product_id → products` FKs; drop `products`, `categories`.

**The key design decision — snapshot vs. lookup**

| Table | Strategy | Why |
|---|---|---|
| `order_items` | **Snapshot.** Already stores `unit_price` and `total_price`. **Add a `product_name` column** and populate at order time. | An order is a historical record. It must never change because a product was renamed or repriced, and reading an order must never depend on catalog being up. |
| `cart_items` | **Lookup.** Keep only `product_id` + `quantity`; enrich on read via `CatalogClient`. | A cart is a live view — it *should* reflect current prices. |

This is the single most important architectural idea in the whole migration. Make sure you understand it before writing code.

**Code changes**
- `CartItem.product` (`@ManyToOne Product`) → `private Long productId`. `CartItem.getTotalPrice()` can no longer compute from the entity — the price now comes from the enriched DTO at the service layer.
- `CartService` drops `ProductRepository`, gains `CatalogClient`.
- Add a **bulk endpoint** to catalog: `GET /products?ids=1,2,3`. Without it, rendering a cart is an N+1 of remote calls.
- `CatalogClient` using Spring's `RestClient` against `http://catalog-service:8080`, with **Resilience4j**: circuit breaker, retry (idempotent GETs only), and a connect/read timeout. Configure a fallback — a cart read with catalog down should degrade (return items with `null` product detail and a warning) rather than 500.
- `Order.fromCart(Cart, User)` — the static factory in [orders/Order.java](../src/main/java/com/rumeshchathuranga/springapi/orders/Order.java) reaches into `Cart`, `CartItem`, *and* `Product`. Replace it with an `OrderFactory` service that takes a cart DTO plus a `Map<Long, ProductDto>` of resolved products. This is the piece that becomes orchestration.
- `OrderItem`'s constructor snapshots price from a `Product` entity — rework to take `ProductDto`.

**Kubernetes:** `catalog-mysql` StatefulSet + `catalog-service` Deployment/Service; add the `/products/**` route to the gateway.

**Tests:** `@DataJpaTest` + Testcontainers for `ProductRepository`; `@RestClientTest` (or WireMock) for `CatalogClient` including the circuit-breaker-open path.

**Verify:** `GET /products` through the gateway; `GET /carts/{id}` returns items enriched with current product name and price; scale catalog to 0 replicas (`kubectl scale deploy/catalog-service --replicas=0`) and confirm the cart read degrades gracefully instead of hanging — then scale back and confirm recovery. `GET /orders/{id}` must still work fully **with catalog at 0 replicas** (proves the snapshot works).

<details><summary><b>Kickoff prompt</b></summary>

> Phase 6 of `docs/MIGRATION.md`: extract `catalog-service`. Move the `products/` package into a new module with its own `catalog_db` and Flyway baseline (products, categories), adding the missing `ProductService` layer and a bulk `GET /products?ids=1,2,3` endpoint. In the remaining monolith, drop the FKs from `cart_items.product_id` and `order_items.product_id`, replace `CartItem.product` with a plain `Long productId`, and add a `product_name` snapshot column to `order_items`.
>
> The design rule: order_items snapshot product data at order time (an order must be readable with catalog down), while cart_items look up live data via a new `CatalogClient` (RestClient + Resilience4j circuit breaker, retry, timeout, and a degrade-gracefully fallback). Replace the static `Order.fromCart(Cart, User)` factory with an `OrderFactory` service taking a cart plus a resolved `Map<Long, ProductDto>`.
>
> Add catalog-mysql and catalog-service k8s manifests and the gateway route. Add Testcontainers repository tests and WireMock-based CatalogClient tests. Verify that with `catalog-service` scaled to 0 replicas, reading an order still fully works and reading a cart degrades gracefully.

</details>

---

## Phase 7 — Extract `cart-service` (carts)

**Goal:** Third split. Easier than Phase 6 because the hard product-decoupling work is already done.

**Moves into `cart-service`:** all of `carts/` (Cart, CartItem, CartController, CartService, CartRepository, CartMapper, CartDto, CartItemDto, `cartProductDto` — rename it to `CartProductDto` while you're there — request DTOs, exceptions, `CartSecurityRules`) plus the `CatalogClient` built in Phase 6.

**Database split:** new `cart_db` with `carts` (`binary(16)` UUID PK, keep it) and `cart_items`. Monolith DB drops both tables.

**Bug fix owed this phase — cart ownership.** Today `/carts/**` is `permitAll` with no owner binding at all, and `CheckoutRequest` accepts an arbitrary `cartId` — so guessing or leaking a cart UUID lets anyone read or check out someone else's cart. Fix:
- Add a nullable `user_id BIGINT` column to `carts`.
- On `POST /carts`: if the caller is authenticated, stamp `user_id` from `CurrentUser.id()`; anonymous carts stay `NULL` (guest checkout still works).
- On any read/write of a cart with non-null `user_id`, require the caller to match (or be ADMIN).
- At checkout, require that the cart is either unowned or owned by the caller, and stamp ownership at that moment.

**Code changes in the remaining monolith (now effectively order-service):**
- `CheckoutService` currently injects `CartRepository` **and** `CartService` **and** `OrderRepository`. Replace both cart dependencies with a `CartClient` (`GET /carts/{id}`, `DELETE /carts/{id}/items`).
- `CartNotFoundException` / `CartEmptyException` are referenced by `CheckoutController`'s `@ExceptionHandler` — map the client's error responses back onto local exception types.

**Kubernetes:** `cart-mysql` + `cart-service` manifests; gateway route for `/carts/**`.

**Verify:** full guest flow through the gateway — create cart → add items → view (enriched from catalog) → checkout. Then the authenticated flow: user A cannot read or check out user B's cart (this is the bug fix, test it explicitly). Cart data survives a `cart-service` pod restart.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 7 of `docs/MIGRATION.md`: extract `cart-service`. Move the `carts/` package and the `CatalogClient` into a new module with its own `cart_db` (carts with the binary(16) UUID PK, cart_items). Rename `cartProductDto` to `CartProductDto`.
>
> Fix the cart-ownership hole while you're here: `/carts/**` is currently fully public with no owner binding, so anyone with a cart UUID can read or check out someone else's cart. Add a nullable `user_id` to `carts`, stamp it from `CurrentUser.id()` when an authenticated user creates a cart, keep anonymous carts working for guest checkout, and enforce ownership on every read/write of an owned cart.
>
> In the remaining monolith, replace `CheckoutService`'s `CartRepository` and `CartService` dependencies with a `CartClient` (RestClient + Resilience4j), mapping remote errors back to `CartNotFoundException`/`CartEmptyException`. Add cart-mysql/cart-service manifests and the gateway route. Verify the guest checkout flow end to end and that user A cannot access user B's cart.

</details>

---

## Phase 8 — Finalize `order-service` and the checkout saga

**Goal:** Rename the remnant, and make distributed checkout actually correct.

**Tasks**
1. Rename module `store-monolith` → `order-service`. It now holds `orders/` + `payments/` only. Delete the leftovers: `HomeController` + `templates/index.html` + the Thymeleaf dependencies (no longer meaningful behind a gateway), and the stale `script.sql` at repo root. Its DB becomes `order_db` (`orders`, `order_items`).
2. **Rewrite checkout as an orchestrated saga with compensation.** The current [CheckoutService.checkout](../src/main/java/com/rumeshchathuranga/springapi/payments/CheckoutService.java) is one `@Transactional` doing cart-read → order-save → Stripe-call → cart-clear, with `orderRepository.delete(order)` as rollback. That no longer works: two of those four steps are now remote, and you must not hold a DB transaction open across an HTTP call to Stripe.

   New flow:
   ```
   1. CartClient.getCart(cartId)            -> 400 if missing/empty
   2. verify caller owns the cart
   3. CatalogClient.getProducts(ids)        -> resolve current prices
   4. [local tx] save Order(PENDING) + items with name/price snapshots
   5. PaymentGateway.createCheckoutSession(order)   (outside the tx)
   6. CartClient.clearCart(cartId)          (best-effort, log on failure)
   7. return { orderId, checkoutUrl }
   ```
   Compensation: if step 5 fails, mark the order `CANCELLED` — **do not delete it**. An order row is an audit record, and a failed payment attempt is information you want. If step 6 fails, log and move on; a stale cart is harmless and the next checkout is idempotent.
3. **Idempotency.** Add an `Idempotency-Key` header on `POST /checkout` stored on the order, so a client retry after a timeout doesn't create a second order and a second Stripe session.
4. **Bug fixes owed this phase** (all in [payments/StripePaymentGateway.java](../src/main/java/com/rumeshchathuranga/springapi/payments/StripePaymentGateway.java) — the webhook path is currently dead code):
   - `parseWebhookRequest` matches `"Payment_intent.succeeded"` / `"Payment_intent.payment_failed"`. Stripe sends lowercase `payment_intent.succeeded`. **No webhook has ever updated an order status.**
   - `createCheckoutSession` writes metadata key `order_id`; `extractOrderId` reads `orderId`. Even with the casing fixed, it would NPE.
   - `handleWebhookEvent` uses `orElseThrow()` on the order lookup — a webhook for an unknown order should be logged and 200'd, not 500'd (Stripe retries 5xx).
   - Make webhook handling idempotent: Stripe delivers at-least-once, so guard against re-processing the same event id, and don't transition an order backwards out of a terminal state.
5. Testing Stripe webhooks against the cluster: `kubectl port-forward svc/order-service 8080:8080 -n store` then `stripe listen --forward-to localhost:8080/checkout/webhook`.
6. Remove `CheckoutController`'s injected-but-unused `OrderRepository`.

**Verify:** full purchase flow through the gateway with a Stripe test card; the order transitions `PENDING → PAID` via a real webhook (this has never worked before — confirm it now does); killing `cart-service` mid-checkout produces a clean 4xx/5xx and no orphaned order in a bad state; replaying the same webhook event twice leaves the order unchanged; retrying `POST /checkout` with the same idempotency key returns the same order.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 8 of `docs/MIGRATION.md`: finalize `order-service`. Rename the `store-monolith` module to `order-service` (holding only `orders/` and `payments/`, DB renamed `order_db`), and delete the now-meaningless `HomeController`, `templates/index.html`, Thymeleaf dependencies, and the stale root `script.sql`.
>
> Rewrite `CheckoutService.checkout` as an orchestrated saga: fetch cart via CartClient, verify ownership, resolve prices via CatalogClient, persist the PENDING order in a short local transaction, call Stripe *outside* the transaction, then best-effort clear the cart. On payment failure mark the order CANCELLED rather than deleting it. Add `Idempotency-Key` support so a client retry doesn't create duplicate orders.
>
> Fix the dead webhook path in `StripePaymentGateway`: the event-type switch uses `"Payment_intent.succeeded"` but Stripe sends lowercase `payment_intent.succeeded`; `createCheckoutSession` writes metadata key `order_id` while `extractOrderId` reads `orderId`; `handleWebhookEvent` throws on unknown orders instead of logging and returning 200. Make webhook processing idempotent against Stripe's at-least-once delivery. Also remove `CheckoutController`'s unused injected `OrderRepository`.
>
> Verify with a real `stripe listen` forwarded into the cluster that an order actually transitions PENDING → PAID.

</details>

---

## Phase 9 — Production-grade Kubernetes

**Goal:** Turn five hand-written manifest sets into something maintainable, and learn the operational side.

**Tasks**
1. **Kustomize**: `k8s/base/` per component + `k8s/overlays/dev/` with replica counts, resource limits, image tags, and `COOKIE_SECURE=false`. `kubectl apply -k k8s/overlays/dev`.
2. **Resources**: requests/limits on every pod. JVM flag `-XX:MaxRAMPercentage=75` so the heap respects the container limit — without it, a Java pod will get OOMKilled by the kubelet.
3. **HPA** on one service (catalog is the natural demo) using metrics-server; drive load with `hey` or `k6` and watch it scale.
4. **Rollouts**: `RollingUpdate` with `maxUnavailable: 0`, `terminationGracePeriodSeconds: 45` (must exceed the graceful-shutdown timeout from Phase 1), and a `PodDisruptionBudget`. Practice `kubectl rollout undo`.
5. **NetworkPolicy** (needs a CNI that enforces them — on minikube use `--cni=calico`): only the gateway may reach services; only the owning service may reach its own MySQL. This is where DB-per-service stops being a convention and becomes enforced.
6. **Secrets hygiene**: replace literal secrets in YAML. Simplest honest option for local work is a `make secrets` target running `kubectl create secret generic ... --from-env-file=.env.k8s`, with `.env.k8s` gitignored. Sealed-secrets or SOPS if you want to learn that too.
7. **Dev loop**: add `skaffold.yaml` (or `tilt`) so `skaffold dev` rebuilds and redeploys the changed service on save. This turns a 3-minute manual cycle into ~20 seconds and is worth the setup time.
8. Bump every service to 2 replicas and confirm nothing breaks — this is where hidden statefulness surfaces. (It shouldn't: all services are `SessionCreationPolicy.STATELESS`.)

**Verify:** `kubectl apply -k k8s/overlays/dev` builds the whole stack from scratch on a fresh `minikube delete && minikube start`; a rolling update completes with zero failed requests under continuous load; the NetworkPolicy provably blocks cart-service from reaching catalog-mysql; `skaffold dev` round-trips a code change in under a minute.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 9 of `docs/MIGRATION.md`: make the Kubernetes setup production-grade. Restructure `k8s/` into Kustomize base + overlays/dev. Add resource requests/limits with `-XX:MaxRAMPercentage=75` on every JVM, an HPA on catalog-service, RollingUpdate with maxUnavailable 0 plus PodDisruptionBudgets and a terminationGracePeriodSeconds that exceeds the graceful-shutdown timeout, and NetworkPolicies restricting each service to its own database and to gateway-only ingress. Replace literal Secrets in YAML with a `make secrets` target reading a gitignored `.env.k8s`. Add a `skaffold.yaml` for a fast rebuild-redeploy dev loop. Then scale everything to 2 replicas and verify a rolling update completes with zero failed requests under load from `hey` or `k6`.

</details>

---

## Phase 10 — Observability *(optional but strongly recommended)*

**Goal:** Be able to answer "which service made this slow / who threw this error" without tailing four log streams.

**Tasks**
1. Replace [common/LoggingFilter.java](../src/main/java/com/rumeshchathuranga/springapi/common/LoggingFilter.java) — it uses `System.out.println`, which is unusable in a cluster. Move to SLF4J with `logstash-logback-encoder` for JSON output, and put `traceId`/`userId` in the MDC.
2. Micrometer + Prometheus: `/actuator/prometheus` on every service, `kube-prometheus-stack` via Helm, Grafana dashboards for JVM + HTTP latency + Resilience4j circuit-breaker state.
3. Distributed tracing: Micrometer Tracing + OpenTelemetry exporter → Jaeger or Tempo. Trace one checkout end to end across gateway → order → cart → catalog → Stripe. Seeing that waterfall is the single best payoff of the whole migration.
4. Custom business metrics: orders created, checkout failures by cause, circuit-breaker trips.

**Verify:** one Grafana dashboard shows all four services; a single checkout produces one connected trace spanning every hop; deliberately breaking catalog shows the circuit breaker opening on the dashboard.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 10 of `docs/MIGRATION.md`: add observability. Replace the `System.out.println`-based `LoggingFilter` with SLF4J + logstash-logback-encoder JSON logging carrying traceId and userId in the MDC. Add Micrometer/Prometheus metrics on every service, install kube-prometheus-stack via Helm, and build a Grafana dashboard covering JVM, HTTP latency, and Resilience4j circuit-breaker state. Add Micrometer Tracing with an OpenTelemetry exporter to Jaeger so a single checkout produces one connected trace across gateway → order → cart → catalog → Stripe.

</details>

---

## Phase 11 — Kafka and the async saga *(optional, advanced)*

**Goal:** Convert the synchronous checkout orchestration into an event-driven saga with the transactional outbox pattern.

Only attempt this after Phase 10 — without tracing, debugging an async saga is miserable.

**Tasks**
1. Kafka in-cluster via Strimzi (the operator approach, more educational) or the Bitnami Helm chart (faster). Budget +1–2 GB RAM.
2. Topics: `order.created`, `order.cancelled`, `payment.succeeded`, `payment.failed`, `cart.checked-out`.
3. **Transactional outbox**: each service writes domain events to a local `outbox` table in the *same* transaction as its state change; a relay (a `@Scheduled` poller is fine to start; Debezium CDC if you want the real thing) publishes to Kafka. This is what makes "save order AND publish event" atomic without distributed transactions.
4. Convert cart-clearing to async: order-service emits `order.created`, cart-service consumes it and clears the cart. Checkout returns faster and no longer fails because cart-service was briefly down.
5. Consumer idempotency: dedupe by event id — Kafka is at-least-once.
6. Dead-letter topics + a retry policy for poison messages.

**Verify:** checkout completes with cart-service scaled to 0, and the cart is cleared automatically once it comes back; killing the relay mid-flight loses no events; replaying a topic from the beginning produces no duplicate side effects.

<details><summary><b>Kickoff prompt</b></summary>

> Phase 11 of `docs/MIGRATION.md`: convert the checkout saga to event-driven. Deploy Kafka to minikube via Strimzi, define the order/payment/cart topics, and implement the transactional outbox pattern — each service writes events to a local outbox table in the same transaction as its state change, with a relay publishing to Kafka. Move cart-clearing off the synchronous checkout path: order-service emits `order.created`, cart-service consumes and clears. Add consumer-side idempotency keyed on event id, plus dead-letter topics. Verify that checkout succeeds with cart-service at 0 replicas and the cart is cleared once it returns.

</details>

---

## Cross-cutting notes

**Testing.** The repo has one `contextLoads` test, so every phase from 4 onward is otherwise unguarded. The `docs/api-baseline.http` collection from Phase 0 is the real safety net — run it after every phase. Each extraction phase should also leave behind Testcontainers-backed repository tests and `@WebMvcTest` controller tests for the service it created. Don't defer this; a broken extraction found three phases later is very expensive.

**Version risk.** Spring Boot 4.0.1 on Java 25 is bleeding-edge. Spring Cloud Gateway (Phase 5), Resilience4j's Spring Boot starter (Phase 6), Micrometer Tracing (Phase 10), and Spring Kafka (Phase 11) may each lag. In every case the fallback is the same: pin that one module to the latest Boot 3.5.x. The modules share nothing but the JWT secret and HTTP contracts, so mixed Boot versions are genuinely fine.

**JWT secret.** All services currently share one HMAC secret (`JwtConfig.getSecretKey()`), which means any service could *mint* tokens, not just validate them. Acceptable for this exercise. The correct end state is RS256 with identity-service holding the private key and publishing a JWKS endpoint the others fetch. Worth doing as a Phase 12 if you want it.

**Things deliberately dropped along the way:** the Thymeleaf `index.html` and `HomeController` (Phase 8), the root `script.sql` (stale, Phase 8), dotenv-java (Phase 1), `AddressRepository`/`ProfileRepository` (unused by anything — decide in Phase 4 whether to build address/profile endpoints or delete them), and `AdminController` (a `"Hello World"` smoke test).

**Ordering rationale**, in case you're tempted to reshuffle: identity goes first because the JWT already carries everything downstream services need, so it creates zero new synchronous calls. Catalog goes second because it's the hardest data split (two FKs, and the snapshot-vs-lookup decision), and everything after it is easier once that's settled. Cart goes third because Phase 6 already did its product-decoupling work. Order is last by elimination — it's whatever remains.

---

## Overall verification

The migration is complete when, from a clean slate:

```bash
minikube delete && minikube start --cpus=4 --memory=8g --addons=ingress,metrics-server
make secrets
skaffold run          # or: make image && kubectl apply -k k8s/overlays/dev
kubectl get pods -n store        # 9 pods Ready: gateway + 4 services + 4 MySQL
```

and then, entirely through `http://store.local`:

1. Register a user, log in, refresh the token.
2. Browse products; create a product as ADMIN and get 403 as a normal user.
3. Create a cart, add items, view it enriched with live catalog prices.
4. Check out with a Stripe test card; the order lands `PENDING`.
5. The Stripe webhook flips it to `PAID`.
6. `GET /orders/{id}` returns the order **with catalog-service scaled to 0** (snapshot integrity).
7. User A gets 403 reading user B's order and user B's cart.
8. `kubectl rollout restart` on any service completes with zero dropped requests under load.
