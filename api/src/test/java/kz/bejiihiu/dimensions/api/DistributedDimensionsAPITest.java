package kz.bejiihiu.dimensions.api;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DistributedDimensionsAPITest {

    private DistributedDimensionsAPI registered;

    @AfterEach
    void tearDown() {
        // every test restores the empty state so ordering between tests can't leak
        DistributedDimensionsAPI.unregister();
        registered = null;
    }

    private DistributedDimensionsAPI newImpl() {
        // minimal working implementations so the fixture is behavioural, not a null-stub
        TransferManager transfers = new TransferManager() {
            @Override
            public @NonNull TransferResult transfer(org.bukkit.entity.@NonNull Player player, @NonNull DimensionId target) {
                return TransferResult.failure(java.util.UUID.randomUUID(), TransferError.REJECTED, "test fixture");
            }

            @Override
            public @NonNull TransferResult transfer(org.bukkit.entity.@NonNull Entity entity, @NonNull DimensionId target) {
                return TransferResult.failure(java.util.UUID.randomUUID(), TransferError.REJECTED, "test fixture");
            }

            @Override
            public java.util.@NonNull Optional<OperationState> status(java.util.@NonNull UUID operationId) {
                return java.util.Optional.empty();
            }
        };
        DimensionRegistry dimensions = new DimensionRegistry() {
            @Override
            public java.util.@NonNull Optional<DimensionInfo> byServer(String server) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.@NonNull Optional<DimensionInfo> byDimension(DimensionId dimension) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.@NonNull List<DimensionInfo> all() {
                return java.util.List.of();
            }
        };
        PortalRegistry portals = entity -> false;
        return new DistributedDimensionsAPI() {
            @Override
            public @NonNull TransferManager transfers() {
                return transfers;
            }

            @Override
            public @NonNull DimensionRegistry dimensions() {
                return dimensions;
            }

            @Override
            public @NonNull PortalRegistry portals() {
                return portals;
            }
        };
    }

    @Test
    void getInstanceThrowsBeforeRegistration() {
        assertThrows(IllegalStateException.class, DistributedDimensionsAPI::getInstance);
    }

    @Test
    void registerThenGetInstanceReturnsIt() {
        registered = newImpl();
        DistributedDimensionsAPI.register(registered);
        assertSame(registered, DistributedDimensionsAPI.getInstance());
    }

    @Test
    void registeringTwiceThrows() {
        DistributedDimensionsAPI.register(newImpl());
        assertThrows(IllegalStateException.class, () -> DistributedDimensionsAPI.register(newImpl()));
    }

    @Test
    void registerRejectsNull() {
        assertThrows(NullPointerException.class, () -> DistributedDimensionsAPI.register(null));
    }

    @Test
    void unregisterMakesGetInstanceThrowAgain() {
        DistributedDimensionsAPI.register(newImpl());
        DistributedDimensionsAPI.unregister();
        assertThrows(IllegalStateException.class, DistributedDimensionsAPI::getInstance);
    }

    @Test
    void errorMessageHintsAtEnablingThePlugin() {
        var error = assertThrows(IllegalStateException.class, DistributedDimensionsAPI::getInstance);
        assertEquals("Distributed Dimensions is not available on this backend: "
                + "the plugin has not registered its api yet (is the plugin enabled?)", error.getMessage());
    }
}
