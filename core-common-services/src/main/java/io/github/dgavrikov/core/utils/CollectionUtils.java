package io.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@UtilityClass
public class CollectionUtils {

    /**
     * Quickly and concurrently partitions an in-memory collection into fixed-size batches (sublists).
     * <p>
     * This method is optimized for CPU-bound operations and large data volumes resident in RAM.
     * It utilizes {@link List#subList}, ensuring zero redundant element copying (Zero-copy layout).
     *
     * @param <T>       the type of elements contained in the collection
     * @param source    the source collection (must not be null)
     * @param chunkSize the maximum size of each individual batch (must be strictly greater than 0)
     * @return a parallel {@link Stream} consisting of partitioned {@link List} sublists
     */
    public static <T> Stream<List<T>> parallelChunked(Collection<T> source, int chunkSize) {
        Objects.requireNonNull(source, "Source collection must not be null");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be greater than 0");
        }

        if (source.isEmpty()) {
            return Stream.empty();
        }

        List<T> list = (source instanceof List)
                ? (List<T>) source
                : new ArrayList<>(source);

        int size = list.size();
        int chunksCount = (size + chunkSize - 1) / chunkSize;

        return IntStream.range(0, chunksCount)
                .parallel()
                .mapToObj(i -> {
                    int start = i * chunkSize;
                    int end = Math.min(start + chunkSize, size);
                    return list.subList(start, end);
                });
    }

    /**
     * Lazily partitions the source stream into streaming batches (lists) of a fixed size.
     * <p>
     * <b>Database Processing Advantages:</b>
     * This method operates strictly using Lazy Evaluation. It does not pull the entire stream into memory at once,
     * but rather reads elements on the fly as it progresses through the stream. This allows you to safely process
     * millions of rows from a database (e.g., via Hibernate cursors or Spring Data {@code Stream}) with a minimal
     * and stable RAM footprint.
     * <p>
     * <b>Operational Specifications:</b>
     * <ul>
     *   <li><b>Resources:</b> The method preserves the resource-closing logic of the underlying stream (via {@code .onClose()}).
     *   Always use a {@code try-with-resources} block when invoking this method to ensure database connections and
     *   transactions are closed in a timely manner.</li>
     *   <li><b>Thread Safety:</b> If the provided stream is parallel ({@code isParallel()}), the method forces it into
     *   sequential mode ({@code sequential()}). Streaming batch operations fundamentally rely on strict preservation of
     *   element order ({@code ORDERED}).</li>
     *   <li><b>Mutability:</b> Each generated batch is returned as a mutable {@link ArrayList}.</li>
     * </ul>
     *
     * @param <T>       the type of elements in the source stream
     * @param stream    the source stream of elements (must not be {@code null})
     * @param chunkSize the maximum size of each individual batch (must be strictly greater than 0)
     * @return a new {@link Stream} that lazily generates {@link List} instances containing at most {@code chunkSize} elements
     * @throws NullPointerException     if the {@code stream} is {@code null}
     * @throws IllegalArgumentException if the {@code chunkSize} is less than or equal to 0
     *
     * @see java.util.Spliterator
     */
    public static <T> Stream<List<T>> chunked(Stream<T> stream, int chunkSize) {
        Objects.requireNonNull(stream, "Stream must not be null");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be greater than 0");
        }

        // If the stream is parallel, force it into sequential mode.
        // Lazy batching relies fundamentally on strict element ordering.
        Stream<T> sequentialStream = stream.isParallel() ? stream.sequential() : stream;

        // Retrieve the source spliterator (e.g., backed by database elements)
        Spliterator<T> sourceSpliterator = sequentialStream.spliterator();

        // Wrap it into our custom batch spliterator
        Spliterator<List<T>> batchSpliterator = new BatchSpliterator<>(sourceSpliterator, chunkSize);

        // Create a new stream, ensuring underlying resource lifecycle hooks are preserved (critical for DB connections)
        return StreamSupport.stream(batchSpliterator, false).onClose(stream::close);
    }

    private static class BatchSpliterator<T> implements Spliterator<List<T>> {
        private final Spliterator<T> source;
        private final int batchSize;

        public BatchSpliterator(Spliterator<T> source, int batchSize) {
            this.source = source;
            this.batchSize = batchSize;
        }

        @Override
        public boolean tryAdvance(Consumer<? super List<T>> action) {
            List<T> batch = new ArrayList<>(batchSize);

            // Populate the batch until batchSize is reached or the source stream is exhausted
            while (batch.size() < batchSize && source.tryAdvance(batch::add)) {
                // Elements are accumulated into the batch on the fly
            }

            // If any elements were collected, pass the batch down the stream pipeline
            if (!batch.isEmpty()) {
                action.accept(batch);
                return true; // Stream continues
            }

            return false; // Stream is exhausted
        }


        @Override
        public Spliterator<List<T>> trySplit() {
            // Return null, as lazy batch partitioning cannot be efficiently parallelized
            return null;
        }

        @Override
        public long estimateSize() {
            long sourceSize = source.estimateSize();
            if (sourceSize == Long.MAX_VALUE) {
                return Long.MAX_VALUE; // The stream size from the DB is typically unknown upfront
            }
            return (long) Math.ceil((double) sourceSize / batchSize);
        }

        @Override
        public int characteristics() {
            // Our stream preserves ordering (ORDERED) and cannot contain null batches (NONNULL)
            return source.characteristics() & Spliterator.ORDERED | Spliterator.NONNULL;
        }

    }
}
