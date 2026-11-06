package com.davidparry.tooldesign.benchmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BenchmarkApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(BenchmarkApplication.class, args)));
    }
}
