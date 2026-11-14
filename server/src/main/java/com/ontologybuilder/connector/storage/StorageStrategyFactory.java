package com.ontologybuilder.connector.storage;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Routes storage requests by StorageType; LOCAL is the default. */
@Component
public class StorageStrategyFactory {

    private final Map<StorageStrategy.StorageType, StorageStrategy> strategies;

    public StorageStrategyFactory(List<StorageStrategy> beans) {
        this.strategies = beans.stream()
                .collect(Collectors.toMap(StorageStrategy::type, Function.identity()));
    }

    public StorageStrategy getStrategy(StorageStrategy.StorageType type) {
        StorageStrategy s = strategies.get(type);
        if (s == null) throw new StorageException("no storage strategy for type " + type);
        return s;
    }

    public StorageStrategy getDefaultStrategy() {
        return getStrategy(StorageStrategy.StorageType.LOCAL);
    }
}
