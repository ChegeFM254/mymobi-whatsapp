package com.mfstechnologies.mymobi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MyMobiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyMobiApplication.class, args);
    }
}