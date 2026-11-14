package com.ontologybuilder.connector.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Local-disk storage under a configurable root (default ./data/storage). */
@Component
public class LocalStorageStrategy implements StorageStrategy {

    @Value("${ontology.storage.root:./data/storage}")
    private Path root;

    private Path resolve(String key) throws StorageException {
        if (key.contains("..")) throw new StorageException("illegal storage key: " + key);
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root.normalize())) throw new StorageException("illegal storage key: " + key);
        return p;
    }

    @Override
    public String store(String key, InputStream data, long size) throws StorageException {
        try {
            Path p = resolve(key);
            Files.createDirectories(p.getParent());
            Files.copy(data, p, StandardCopyOption.REPLACE_EXISTING);
            return key;
        } catch (IOException e) {
            throw new StorageException("store failed: " + key, e);
        }
    }

    @Override
    public InputStream retrieve(String key) throws StorageException {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new StorageException("retrieve failed: " + key, e);
        }
    }

    @Override
    public void delete(String key) throws StorageException {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("delete failed: " + key, e);
        }
    }

    @Override
    public StorageType type() { return StorageType.LOCAL; }
}
