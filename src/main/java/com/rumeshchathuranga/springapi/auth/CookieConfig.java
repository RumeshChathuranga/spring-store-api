package com.rumeshchathuranga.springapi.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.cookie")
@Data
public class CookieConfig {
    private boolean secure = true;
    private String sameSite = "Lax";
}
