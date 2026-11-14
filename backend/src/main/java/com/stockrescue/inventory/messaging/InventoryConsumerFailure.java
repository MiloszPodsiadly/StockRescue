package com.stockrescue.inventory.messaging;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.errors.RetriableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/** Safe classification for failures that must retain the current Kafka offset. */
public final class InventoryConsumerFailure extends RuntimeException {
    public enum Category { TRANSIENT_INFRA, PERMANENT_EVENT_DATA, NONRETRYABLE_APP_UNKNOWN }

    private final Category category;

    private InventoryConsumerFailure(Category category, Throwable cause) {
        super("Inventory projection failed: " + category.name(), cause);
        this.category = category;
    }

    public Category category() { return category; }

    public static InventoryConsumerFailure classify(Throwable failure) {
        if (failure instanceof InventoryConsumerFailure classified) return classified;
        Category category = categoryOf(failure);
        return new InventoryConsumerFailure(category, failure);
    }

    public static Category categoryOf(Throwable failure) {
        if (failure == null) return Category.NONRETRYABLE_APP_UNKNOWN;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof InventoryEventContractException contract && contract.isPermanentContractFailure()) {
                return Category.PERMANENT_EVENT_DATA;
            }
            if (current instanceof TransientDataAccessException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof CannotCreateTransactionException
                    || current instanceof TransactionTimedOutException
                    || current instanceof SQLTransientException
                    || current instanceof SQLRecoverableException
                    || current instanceof RetriableException
                    || current instanceof TimeoutException
                    || current instanceof SocketException
                    || current instanceof SocketTimeoutException
                    || current instanceof SQLException sql && transientSqlState(sql.getSQLState())) {
                return Category.TRANSIENT_INFRA;
            }
        }
        return Category.NONRETRYABLE_APP_UNKNOWN;
    }

    private static boolean transientSqlState(String state) {
        if (state == null || state.length() < 2) return false;
        String sqlClass = state.substring(0, 2);
        return sqlClass.equals("08") || sqlClass.equals("40") || sqlClass.equals("53") || sqlClass.equals("57");
    }
}
