package com.rumeshchathuranga.springapi;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full dev-profile context, so it needs a reachable MySQL and a
 * DB_PASSWORD in the environment:
 *
 *     set -a; . ./.env; set +a; ./mvnw test
 *
 * The secrets below are stubbed here rather than in a test application.yaml —
 * src/test/resources does not exist, so adding an application.yaml there would
 * shadow src/main/resources/application.yaml entirely and silently drop the
 * actuator, cookie and JWT-TTL config this test is supposed to exercise.
 * New @SpringBootTest classes should copy this properties block.
 */
@SpringBootTest(properties = {
        "spring.jwt.secret=test_only_secret_at_least_32_bytes_long_0123456789",
        "stripe.secretKey=sk_test_dummy",
        "stripe.webhookSecretKey=whsec_dummy"
})
class SpringApiApplicationTests {

    @Test
    void contextLoads() {
    }

}
