package com.davidparry.tooldesign.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.davidparry.tooldesign.app.domain.BusinessClock;
import com.davidparry.tooldesign.app.fixtures.FixtureLoader;

/** Every service test starts from the seed fixtures on the fixed business clock. */
@SpringBootTest
abstract class ServiceTestSupport {

    @Autowired
    FixtureLoader fixtures;

    @Autowired
    BusinessClock clock;

    @BeforeEach
    void freshStore() {
        this.fixtures.load();
        this.clock.reset();
    }
}
