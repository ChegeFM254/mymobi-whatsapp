package com.mfstechnologies.mymobi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * WORKSTREAM G (outbox pattern): @EnableScheduling turns on Spring's
 * @Scheduled support, needed for OutboxDispatcher's polling loop - this
 * is the first scheduled task in the application.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MyMobiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyMobiApplication.class, args);
    }
}
