package com.bookloop;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication @EnableScheduling
public class BookLoopApplication {
  public static void main(String[] a) { SpringApplication.run(BookLoopApplication.class, a); }

  /** Creates a default admin (admin@bookloop.edu / admin123) on first run. Change it after logging in. */
  @Bean CommandLineRunner seed(JdbcTemplate db) {
    return a -> {
      if (db.queryForObject("SELECT COUNT(*) FROM students WHERE role='ADMIN'", Integer.class) == 0)
        db.update("INSERT INTO students(name,email,phone,department,semester,password_hash,status,role) VALUES('Admin','admin@bookloop.edu','0','Admin',1,?,'VERIFIED','ADMIN')",
            new BCryptPasswordEncoder().encode("admin123"));
    };
  }
}