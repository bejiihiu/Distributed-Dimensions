package kz.bejiihiu.dimensions.common.persistence;

import org.junit.jupiter.api.BeforeEach;

/**
 * The respawn-point contract against the in-memory store. A fresh container per test,
 * no cleanup needed.
 */
class InMemoryRespawnPointStoreTest extends RespawnPointStoreContractTest {

    private InMemoryRespawnPointStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRespawnPointStore();
    }

    @Override
    protected RespawnPointStore openStore() {
        if (store == null) {
            store = new InMemoryRespawnPointStore();
        }
        return store;
    }
}
