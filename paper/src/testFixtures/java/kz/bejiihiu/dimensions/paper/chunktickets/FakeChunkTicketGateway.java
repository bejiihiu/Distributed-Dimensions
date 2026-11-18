package kz.bejiihiu.dimensions.paper.chunktickets;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording {@link ChunkTicketGateway} for tests: every add/remove call is appended
 * to the returned lists as a {@link TicketKey}. {@code addResult}/{@code removeResult}
 * control the returned booleans to simulate platform failures.
 */
public final class FakeChunkTicketGateway implements ChunkTicketGateway {

    private final List<TicketKey> adds = new ArrayList<>();
    private final List<TicketKey> removes = new ArrayList<>();
    private boolean addResult = true;
    private boolean removeResult = true;

    public void setAddResult(boolean addResult) {
        this.addResult = addResult;
    }

    public void setRemoveResult(boolean removeResult) {
        this.removeResult = removeResult;
    }

    /** All add calls in order. */
    public List<TicketKey> adds() {
        return List.copyOf(adds);
    }

    /** All remove calls in order. */
    public List<TicketKey> removes() {
        return List.copyOf(removes);
    }

    @Override
    public boolean add(TicketKey key) {
        adds.add(key);
        return addResult;
    }

    @Override
    public boolean remove(TicketKey key) {
        removes.add(key);
        return removeResult;
    }
}
