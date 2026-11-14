package com.ontologybuilder.connector.storage;

import java.io.InputStream;

/** Pluggable file storage. Local-disk implementation is provided by default. */
public interface StorageStrategy {

    enum StorageType { LOCAL, S3, OSS }

    /** Store bytes; returns the storage key/path. */
    String store(String key, InputStream data, long size) throws StorageException;

    /** Read a stored file back. */
    InputStream retrieve(String key) throws StorageException;

    /** Delete a stored file. */
    void delete(String key) throws StorageException;

    StorageType type();
}
