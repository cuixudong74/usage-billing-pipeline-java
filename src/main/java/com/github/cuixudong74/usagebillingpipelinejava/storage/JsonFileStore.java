package com.github.cuixudong74.usagebillingpipelinejava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JsonFileStore {
    private final Path directory;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public JsonFileStore(@Value("${billing.data-dir:./data/billing}") String dataDirectory) {
        this(Path.of(dataDirectory), new ObjectMapper().findAndRegisterModules());
    }

public JsonFileStore(Path directory, ObjectMapper objectMapper) {
        this.directory = directory;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory " + directory, e);
        }
    }

    public <T> Map<String, T> readAll(String type, TypeReference<Map<String, T>> typeReference) {
        Path file = file(type);
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(file.toFile(), typeReference);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }

    public <T> T put(String type, String id, T value) {
        return mutate(type, values -> values.put(id, value));
    }

    public <T> T putIfAbsent(String type, String id, T value) {
        return mutate(type, values -> values.putIfAbsent(id, value));
    }

    public <T> void replaceAll(String type, Map<String, T> values) {
        lock.lock();
        try {
            writeAll(type, values);
        } finally {
            lock.unlock();
        }
    }

    private <T> void writeAll(String type, Map<String, T> values) {
        Path file = file(type);
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, type + "-", ".tmp");
            Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(values),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot update " + type, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort temp cleanup
                }
            }
        }
    }

    private <T> T mutate(String type, java.util.function.Function<Map<String, T>, T> mutation) {
        lock.lock();
        try {
            Path file = file(type);
            TypeReference<Map<String, T>> ref = new TypeReference<>() {
            };
            Map<String, T> values = Files.exists(file)
                    ? objectMapper.readValue(file.toFile(), ref)
                    : new LinkedHashMap<>();
            T result = mutation.apply(values);
            Path temp = Files.createTempFile(directory, type + "-", ".tmp");
            try {
                Files.writeString(temp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(values),
                        StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot update " + type, e);
        } finally {
            lock.unlock();
        }
    }

    private Path file(String type) {
        return directory.resolve(type + ".json");
    }
}
