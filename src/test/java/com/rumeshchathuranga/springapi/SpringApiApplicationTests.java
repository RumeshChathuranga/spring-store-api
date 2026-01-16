package com.rumeshchathuranga.springapi;

import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SpringApiApplicationTests {

    @BeforeAll
    static void setup() {
        // Load .env file for the test environment
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory("./")
                    .ignoreIfMissing()
                    .load();

            dotenv.entries().forEach(entry -> {
                System.setProperty(entry.getKey(), entry.getValue());
            });
        } catch (Exception e) {
            System.err.println("Warning: Could not load .env file in tests: " + e.getMessage());
        }
    }

    @Test
    void contextLoads() {
        // The context will now load successfully because properties are set
    }

}