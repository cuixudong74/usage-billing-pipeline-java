package com.github.cuixudong74.usagebillingpipelinejava.authorization;

import java.util.Set;

public record Actor(String subject, Set<String> roles) {
}
