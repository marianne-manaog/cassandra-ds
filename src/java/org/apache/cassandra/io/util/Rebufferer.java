/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.io.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.apache.cassandra.cache.ChunkCache;
import org.apache.cassandra.concurrent.ExecutorLocals;

/**
 * Rebufferer for reading data by a RandomAccessReader.
 */
public interface Rebufferer extends ReaderFileProxy
{
    /**
     * Rebuffer (move on or seek to) a given position, and return a buffer that can be used there.
     * The only guarantee about the size of the returned data is that unless rebuffering at the end of the file,
     * the buffer will not be empty and will contain the requested position, i.e.
     * {@code offset <= position < offset + bh.buffer().limit()}, but the buffer will not be positioned there.
     */
    BufferHolder rebuffer(long position);

    /**
     * Called when a reader is closed. Should clean up reader-specific data.
     */
    void closeReader();

    // Extensions for TPC/optimistic read below.

    default BufferHolder rebuffer(long position, ReaderConstraint constraint)
    {
        if (constraint == ReaderConstraint.IN_CACHE_ONLY)
            throw new IllegalStateException("In cache only constraint requires the cache rebufferer");

        return rebuffer(position);
    }

    public enum ReaderConstraint
    {
        NONE,
        IN_CACHE_ONLY
    }

    public static class NotInCacheException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final CompletableFuture<ChunkCache.Buffer> asyncBuffer;

        public NotInCacheException(CompletableFuture<ChunkCache.Buffer> asyncBuffer)
        {
            super("Requested data is not in cache. Retry with ReaderConstraint.NONE.");
            this.asyncBuffer = asyncBuffer;
        }

        @Override
        public synchronized Throwable fillInStackTrace()
        {
            //Avoids generating a stack trace for every instance
            return this;
        }

        public void accept(Runnable onReady, Runnable onSchedule, Consumer<Throwable> onError, Executor executor)
        {
            //Registers a callback to be issued when the async buffer is ready
            assert asyncBuffer != null;

            if (asyncBuffer.isDone() && !asyncBuffer.isCompletedExceptionally())
            {
                onReady.run();
            }
            else
            {
                onSchedule.run();

                //Track the ThreadLocals
                Runnable wrappedOnReady = new ExecutorLocals.WrappedRunnable(onReady);

                asyncBuffer.thenRunAsync(() -> wrappedOnReady.run(), executor)
                            .exceptionally(t ->
                                           {
                                               onError.accept(t);
                                               return null;
                                           });
            }
        }

        public String toString()
        {
            return "NotInCache " + asyncBuffer;
        }
    }

    interface BufferHolder
    {
        /**
         * Returns a useable buffer (i.e. one whose position and limit can be freely modified). Its limit will be set
         * to the size of the available data in the buffer.
         * The buffer must be treated as read-only.
         */
        ByteBuffer buffer();

        /**
         * Position in the file of the start of the buffer.
         */
        long offset();

        /**
         * To be called when this buffer is no longer in use. Must be called for all BufferHolders, or ChunkCache
         * will not be able to free blocks.
         */
        void release();
    }

    BufferHolder EMPTY = new BufferHolder()
    {
        final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

        @Override
        public ByteBuffer buffer()
        {
            return EMPTY_BUFFER;
        }

        @Override
        public long offset()
        {
            return 0;
        }

        @Override
        public void release()
        {
            // nothing to do
        }
    };
}