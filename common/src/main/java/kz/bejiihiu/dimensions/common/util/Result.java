package kz.bejiihiu.dimensions.common.util;

import java.util.Objects;
import java.util.function.Function;

/**
 * Minimal {@code Result<T, E>} for boundaries where an exception would be the wrong tool —
 * e.g. protocols and selection algorithms where failure is routine data, not a bug.
 *
 * <p>Usage notes (read before reaching for it):
 * <ul>
 *   <li>Inside a module, plain exceptions stay the default for programmer errors and
 *       violated invariants.</li>
 *   <li>At a boundary (wire decoding, external plugin callbacks) where the caller chooses
 *       what to do with a failure, this type beats {@code throw} — no stack unwinds into
 *       foreign frames, and exhaustiveness is checked by the compiler via pattern matching.</li>
 *   <li>This is deliberately not a monad power-tool (no zip/recover chains). map/flatMap/fold
 *       cover the uses we have; more would be noise.</li>
 * </ul>
 */
public sealed interface Result<T, E> {

    /** A successful outcome carrying {@code value}. */
    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A failed outcome carrying {@code error} — plain data, no stack trace. */
    record Err<T, E>(E error) implements Result<T, E> {
        public Err {
            Objects.requireNonNull(error, "error");
        }
    }

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok<?, ?>;
    }

    default boolean isErr() {
        return this instanceof Err<?, ?>;
    }

    /** Maps the value if present, keeps the error otherwise. */
    default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return switch (this) {
            case Ok<T, E> ok -> ok(mapper.apply(ok.value()));
            case Err<T, E> err -> err(err.error());
        };
    }

    /** Like {@link #map}, but the mapper itself returns a {@code Result}. */
    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return switch (this) {
            case Ok<T, E> ok -> {
                Result<U, E> inner = mapper.apply(ok.value());
                Objects.requireNonNull(inner, "mapper returned null");
                yield inner;
            }
            case Err<T, E> err -> err(err.error());
        };
    }

    /** Collapses both branches into a single result value. */
    default <R> R fold(Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
        Objects.requireNonNull(onOk, "onOk");
        Objects.requireNonNull(onErr, "onErr");
        return switch (this) {
            case Ok<T, E> ok -> onOk.apply(ok.value());
            case Err<T, E> err -> onErr.apply(err.error());
        };
    }

    /** The value, or {@code fallback} when this is an error. */
    default T orElse(T fallback) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> ignored -> fallback;
        };
    }

    /** The value, or throws the supplied exception mapped from the error. */
    default T orElseThrow(Function<? super E, ? extends RuntimeException> exceptionMapper) {
        Objects.requireNonNull(exceptionMapper, "exceptionMapper");
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> throw exceptionMapper.apply(err.error());
        };
    }
}
