package kz.bejiihiu.dimensions.common.persistence;

import java.util.List;
import java.util.Objects;

/**
 * One versioned schema migration. {@code version} is a strictly increasing integer starting at 1;
 * {@code statements} are plain SQL executed in order inside one transaction (where the dialect
 * allows transactional DDL — H2/PostgreSQL do; MySQL implicitly commits, which is documented on
 * the runner).
 */
public record Migration(int version, String description, List<String> statements) {

    public Migration {
        if (version < 1) {
            throw new IllegalArgumentException("migration version must be >= 1, got " + version);
        }
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("migration description must not be blank");
        }
        Objects.requireNonNull(statements, "statements");
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("a migration needs at least one statement");
        }
        statements = List.copyOf(statements);
    }
}
