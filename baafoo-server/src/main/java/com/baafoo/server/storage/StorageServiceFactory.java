package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;

/**
 * Factory for creating StorageService instances based on configuration.
 *
 * <p>Currently only JdbcStorageService is production-ready.
 * FileStorage exists but does not implement the StorageService interface
 * yet; it will be supported in a future release.</p>
 */
public class StorageServiceFactory {

    public static StorageService create(ServerConfig config) {
        // FileStorage does not yet implement StorageService; always use JDBC for now
        return new JdbcStorageService(config);
    }
}
