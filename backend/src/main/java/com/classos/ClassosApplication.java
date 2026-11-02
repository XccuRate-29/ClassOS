package com.classos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// UserDetailsServiceAutoConfiguration is excluded because authentication is
// handled by our own JWT flow; otherwise Spring creates a default in-memory user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling  // background sweeper that closes expired attendance sessions
public class ClassosApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClassosApplication.class, args);
    }
}
