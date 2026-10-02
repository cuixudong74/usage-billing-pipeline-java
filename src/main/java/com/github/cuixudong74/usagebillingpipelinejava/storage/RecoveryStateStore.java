package com.github.cuixudong74.usagebillingpipelinejava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.cuixudong74.usagebillingpipelinejava.model.RecoveryState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RecoveryStateStore {
    private final Path file;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();

    @Autowired
    public RecoveryStateStore(@Value("${billing.data-dir:./data/billing}") String dataDirectory) {
        this(Path.of(dataDirectory, "recovery-state.json"), new ObjectMapper().findAndRegisterModules());
    }

public RecoveryStateStore(Path file, ObjectMapper objectMapper) {
        this.file = file;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory", e);
        }
    }

    public Map<String, RecoveryState> load() {
        if (!Files.exists(file)) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(file.toFile(), new TypeReference<Map<String, RecoveryState>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read recovery state", e);
        }
    }

    public RecoveryState put(RecoveryState state) {
        synchronized (lock) {
            Map<String, RecoveryState> values = load();
            values.put(key(state.accountId(), state.windowStart(), state.windowEnd()), state);
            try {
                Path temp = Files.createTempFile(file.getParent(), "recovery-", ".tmp");
                Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(values),
                        StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot write recovery state", e);
            }
            return state;
        }
    }

    public Map<String, RecoveryState> commit(UnaryOperator<Map<String, RecoveryState>> change) {
        synchronized (lock) {
            Map<String, RecoveryState> values = change.apply(load());
            try {
                Path temp = Files.createTempFile(file.getParent(), "recovery-", ".tmp");
                Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(values),
                        StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return values;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot commit recovery state", e);
            }
        }
    }

    public static String key(String accountId, java.time.Instant start, java.time.Instant end) {
        return accountId + "|" + start + "|" + end;
    }
}
