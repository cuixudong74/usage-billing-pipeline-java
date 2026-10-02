package com.github.cuixudong74.usagebillingpipelinejava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BillingStateStore {
    private final Path file;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    @Autowired
    public BillingStateStore(@Value("${billing.data-dir:./data/billing}") String dataDirectory) {
        this(Path.of(dataDirectory, "billing-state.json"), new ObjectMapper().findAndRegisterModules());
    }

public BillingStateStore(Path file, ObjectMapper objectMapper) {
        this.file = file;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory", e);
        }
    }

    public BillingState load() {
        if (!Files.exists(file)) {
            return BillingState.empty();
        }
        try {
            BillingState state = objectMapper.readValue(file.toFile(), new TypeReference<BillingState>() {
            });
            return state == null ? BillingState.empty() : state;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read billing state", e);
        }
    }

    public BillingState commit(UnaryOperator<BillingState> change) {
        synchronized (writeLock) {
            BillingState current = load();
            BillingState next = change.apply(current);
            Path temp;
            try {
                temp = Files.createTempFile(file.getParent(), "billing-state-", ".tmp");
                Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(next),
                        StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot atomically commit billing state", e);
            }
            return next;
        }
    }
}
