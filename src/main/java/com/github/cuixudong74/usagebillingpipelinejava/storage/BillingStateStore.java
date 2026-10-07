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

/**
 * Atomic, in-memory backed aggregate billing state.
 *
 * <p>The previous implementation re-read the whole JSON file on every commit and rewrote it,
 * which made concurrent commits from different accounts overwrite each other. State now lives in
 * memory (loaded once), reads are lock-free, and the monitor is held only for the short
 * "mutate + atomically persist" section. Expensive pricing/aggregation work happens outside the
 * lock (guarded by per-account locks in the services), so a slow account never blocks another
 * account's calculation; only the final disk flush is serialized across accounts.</p>
 */
@Component
public class BillingStateStore {
    private final Path file;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();
    private volatile BillingState state;

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
        this.state = loadFromDisk();
    }

    private BillingState loadFromDisk() {
        if (!Files.exists(file)) {
            return BillingState.empty();
        }
        try {
            BillingState loaded = objectMapper.readValue(file.toFile(), new TypeReference<BillingState>() {
            });
            return loaded == null ? BillingState.empty() : loaded;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read billing state", e);
        }
    }

    public BillingState load() {
        return state;
    }

    public BillingState commit(UnaryOperator<BillingState> change) {
        synchronized (writeLock) {
            BillingState next = change.apply(state);
            persist(next);
            state = next;
            return next;
        }
    }

    private void persist(BillingState next) {
        Path temp;
        try {
            temp = Files.createTempFile(file.getParent(), "billing-state-", ".tmp");
            Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(next),
                    StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot atomically commit billing state", e);
        }
    }
}
