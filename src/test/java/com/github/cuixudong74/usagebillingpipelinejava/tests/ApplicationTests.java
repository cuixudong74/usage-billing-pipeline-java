package com.github.cuixudong74.usagebillingpipelinejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * @version 1.0
 */
@SpringBootTest(properties = "billing.data-dir=target/application-context-test-data")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
