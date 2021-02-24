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
package org.apache.cassandra.index.sai;

import com.google.common.base.Objects;

import org.apache.cassandra.index.sai.disk.io.IndexComponents;
import org.apache.cassandra.index.sai.disk.v1.PrimaryKeyMap;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.Throwables;
import org.apache.cassandra.utils.concurrent.Ref;
import org.apache.cassandra.utils.concurrent.RefCounted;
import org.apache.cassandra.utils.concurrent.SharedCloseableImpl;

/**
 * SSTableContext is created for individual sstable shared across indexes to track per-sstable index files.
 *
 * SSTableContext itself will be released when receiving sstable removed notification, but its shared copies in individual
 * SSTableIndex will be released when in-flight read requests complete.
 */
public class SSTableContext extends SharedCloseableImpl
{
    public final SSTableReader sstable;
    public final PrimaryKeyMap primaryKeyMap;

    private final IndexComponents groupComponents;

    private SSTableContext(SSTableReader sstable, PrimaryKeyMap primaryKeyMap, Cleanup cleanup, IndexComponents groupComponents)
    {
        super(cleanup);
        this.sstable = sstable;
        this.primaryKeyMap = primaryKeyMap;
        this.groupComponents = groupComponents;
    }

    private SSTableContext(SSTableContext copy)
    {
        super(copy);
        this.sstable = copy.sstable;
        this.primaryKeyMap = copy.primaryKeyMap;
        this.groupComponents = copy.groupComponents;
    }

    public static SSTableContext create(SSTableReader sstable)
    {
        IndexComponents groupComponents = IndexComponents.perSSTable(sstable);

        Ref<SSTableReader> sstableRef = null;
        PrimaryKeyMap primaryKeyMap;
        try
        {
            sstableRef = sstable.tryRef();

            if (sstableRef == null)
            {
                throw new IllegalStateException("Couldn't acquire reference to the sstable: " + sstable);
            }

            primaryKeyMap = new PrimaryKeyMap.DefaultPrimaryKeyMap(groupComponents, sstable.metadata().partitioner, sstable.metadata().comparator);

            Cleanup cleanup = new Cleanup(primaryKeyMap, sstableRef);

            return new SSTableContext(sstable, primaryKeyMap, cleanup, groupComponents);
        }
        catch (Throwable t)
        {
            if (sstableRef != null)
            {
                sstableRef.release();
            }

            //TODO Does this need to potentially close the PrimaryKeyMap?
            throw Throwables.unchecked(t);
        }
    }

    /**
     * @return number of open files per {@link SSTableContext} instance
     */
    public static int openFilesPerSSTable()
    {
        //TODO How many really?
        return 1;
    }

    @Override
    public SSTableContext sharedCopy()
    {
        return new SSTableContext(this);
    }

    private static class Cleanup implements RefCounted.Tidy
    {
        private final PrimaryKeyMap primaryKeyMap;
        private final Ref<SSTableReader> sstableRef;

        private Cleanup(PrimaryKeyMap primaryKeyMap, Ref<SSTableReader> sstableRef)
        {
            this.primaryKeyMap = primaryKeyMap;
            this.sstableRef = sstableRef;
        }

        @Override
        public void tidy()
        {
            Throwable t = sstableRef.ensureReleased(null);
            t = Throwables.close(t, primaryKeyMap);

            Throwables.maybeFail(t);
        }

        @Override
        public String name()
        {
            return null;
        }
    }

    /**
     * @return descriptor of attached sstable
     */
    public Descriptor descriptor()
    {
        return sstable.descriptor;
    }

    public SSTableReader sstable()
    {
        return sstable;
    }

    /**
     * @return disk usage of per-sstable index files
     */
    public long diskUsage()
    {
        return groupComponents.sizeOfPerSSTableComponents();
    }

    @Override
    public String toString()
    {
        return "SSTableContext{" +
               "sstable=" + sstable.descriptor +
               '}';
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SSTableContext that = (SSTableContext) o;
        return Objects.equal(sstable.descriptor, that.sstable.descriptor);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(sstable.descriptor.hashCode());
    }
}
