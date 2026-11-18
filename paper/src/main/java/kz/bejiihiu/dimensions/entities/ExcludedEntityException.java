package kz.bejiihiu.dimensions.entities;

/**
 * The assembly contains an entity that must never travel (wither, ender dragon, creaking).
 * The whole operation is refused — matching vanilla, where such an entity cannot pass the
 * portal no matter what it is riding.
 */
public final class ExcludedEntityException extends RuntimeException {

    public ExcludedEntityException(String message) {
        super(message);
    }
}
