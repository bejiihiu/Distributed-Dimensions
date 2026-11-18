package kz.bejiihiu.dimensions.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Credential redaction for URLs quoted in error messages. */
class SecretsRedactorTest {

    @Test
    void redactsJdbcUrlWithPassword() {
        assertEquals("cannot connect to jdbc:mysql://****@db.internal:3306/dd",
                SecretsRedactor.redact("cannot connect to jdbc:mysql://user:pw@db.internal:3306/dd"));
    }

    @Test
    void redactsMongoUriWithPassword() {
        assertEquals("mongodb://****@mongo.internal:27017/dd",
                SecretsRedactor.redact("mongodb://root:s3cret@mongo.internal:27017/dd"));
    }

    @Test
    void redactsUserWithoutPassword() {
        assertEquals("postgresql://****@db.internal/dd",
                SecretsRedactor.redact("postgresql://alice@db.internal/dd"));
    }

    @Test
    void leavesCredentialFreeMessagesAlone() {
        assertEquals("migration failed at step 3",
                SecretsRedactor.redact("migration failed at step 3"));
        assertEquals("jdbc:mysql://db.internal:3306/dd",
                SecretsRedactor.redact("jdbc:mysql://db.internal:3306/dd"));
    }

    @Test
    void redactsMultipleUrlsInOneMessage() {
        assertEquals("a://****@h1 b://****@h2",
                SecretsRedactor.redact("a://u:p@h1 b://u2@h2"));
    }

    @Test
    void nullMessageBecomesUnknownError() {
        assertEquals("<unknown error>", SecretsRedactor.redact(null));
    }
}
