package com.rumeshchathuranga.springapi;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SpringApiApplication {

    public static void main(String[] args) {

        // Load .env file and set as system properties
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory("./")  // Look in project root
                    .ignoreIfMissing()
                    .load();

            // Set all env variables as system properties
            dotenv.entries().forEach(entry -> {
                System.setProperty(entry.getKey(), entry.getValue());
            });
        } catch (Exception e) {
            System.err.println("Warning: Could not load .env file: " + e.getMessage());
        }
        SpringApplication.run(SpringApiApplication.class, args);
    }

}
