package io.atomix.cluster.messaging;

/**
 * Stream handler function.
 */
@FunctionalInterface
public interface TriConsumer<T, U, V> {

  void accept(T arg1, U arg2, V arg3);

}
