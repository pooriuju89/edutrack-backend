package com.edutrack.config;

import com.edutrack.model.User;
import com.edutrack.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Bean
    CommandLineRunner seedDefaultUsers(UserRepository userRepo, PasswordEncoder encoder) {
        return args -> {
            seedUser(userRepo, encoder, "admin@school.edu",     "Admin123!",   "System Administrator", User.Role.ADMIN);
            seedUser(userRepo, encoder, "principal@school.edu", "Principal123!", "Mr. Priya Fernando",  User.Role.PRINCIPAL);
            seedUser(userRepo, encoder, "scanner@school.edu",   "Scanner123!", "Gate Operator",         User.Role.SCANNER);
        };
    }

    private void seedUser(UserRepository repo, PasswordEncoder encoder,
                          String email, String rawPassword, String name, User.Role role) {
        if (!repo.existsByEmail(email)) {
            repo.save(User.builder()
                    .email(email)
                    .password(encoder.encode(rawPassword))
                    .fullName(name)
                    .role(role)
                    .active(true)
                    .build());
            log.info("Seeded default {} user: {}", role, email);
        }
    }
}
