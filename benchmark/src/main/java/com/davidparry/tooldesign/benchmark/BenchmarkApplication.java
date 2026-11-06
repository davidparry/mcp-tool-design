package com.davidparry.tooldesign.benchmark;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import com.davidparry.tooldesign.benchmark.config.DotEnv;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BenchmarkApplication {

    public static void main(String[] args) {
        DotEnv.load(Path.of(".env"));
        System.exit(SpringApplication.exit(SpringApplication.run(BenchmarkApplication.class, args)));
    }
}
