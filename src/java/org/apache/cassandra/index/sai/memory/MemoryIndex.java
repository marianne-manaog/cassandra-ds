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

package org.apache.cassandra.index.sai.memory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.PartitionPosition;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.plan.Expression;
import org.apache.cassandra.index.sai.utils.RangeIterator;
import org.apache.cassandra.index.sai.utils.TypeUtil;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.bytecomparable.ByteComparable;

public abstract class MemoryIndex
{
    protected final IndexContext indexContext;
    private final AtomicReference<ByteBuffer> minTermRef = new AtomicReference<>();
    private final AtomicReference<ByteBuffer> maxTermRef = new AtomicReference<>();

    protected MemoryIndex(IndexContext indexContext)
    {
        this.indexContext = indexContext;
    }

    public abstract long add(DecoratedKey key, Clustering clustering, ByteBuffer value);

    public abstract RangeIterator search(Expression expression, AbstractBounds<PartitionPosition> keyRange);

    public void setMinMaxTerm(ByteBuffer term)
    {
        assert term != null;

        minTermRef.accumulateAndGet(term, (term1, term2) -> TypeUtil.min(term1, term2, indexContext.getValidator()));
        maxTermRef.accumulateAndGet(term, (term1, term2) -> TypeUtil.max(term1, term2, indexContext.getValidator()));
    }

    public ByteBuffer getMinTerm()
    {
        return minTermRef.get();
    }

    public ByteBuffer getMaxTerm()
    {
        return maxTermRef.get();
    }

    /**
     * @return Terms -> ByteComparable encoded primary keys
     */
    public abstract Iterator<Pair<ByteComparable, Iterable<ByteComparable>>> iterator();
}
