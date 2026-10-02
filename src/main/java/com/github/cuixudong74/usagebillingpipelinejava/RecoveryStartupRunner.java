package com.github.cuixudong74.usagebillingpipelinejava;

import com.github.cuixudong74.usagebillingpipelinejava.service.RecoveryService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RecoveryStartupRunner implements ApplicationRunner {
    private final RecoveryService recoveryService;

    public RecoveryStartupRunner(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        recoveryService.recover();
    }
}
