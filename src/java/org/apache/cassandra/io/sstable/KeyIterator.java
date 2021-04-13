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
package org.apache.cassandra.io.sstable;

import java.io.IOException;

import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.io.sstable.format.PartitionIndexIterator;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.AbstractIterator;
import org.apache.cassandra.utils.CloseableIterator;

// TODO STAR-247: Implement a unit test
public class KeyIterator extends AbstractIterator<DecoratedKey> implements CloseableIterator<DecoratedKey>
{
    private final IPartitioner partitioner;

    private final PartitionIndexIterator it;

    private final long totalBytes;

    private boolean initialized = false;

    public KeyIterator(PartitionIndexIterator it, IPartitioner partitioner, long totalBytes)
    {
        this.it = it;
        this.partitioner = partitioner;
        this.totalBytes = totalBytes;
    }

    public static KeyIterator forSSTable(SSTableReader ssTableReader) throws IOException
    {
        return new KeyIterator(ssTableReader.allKeysIterator(), ssTableReader.getPartitioner(), ssTableReader.uncompressedLength());
    }

    protected DecoratedKey computeNext()
    {
        try
        {
            if (!initialized)
            {
                initialized = true;
                return it.isExhausted()
                       ? endOfData()
                       : partitioner.decorateKey(it.key());
            }
            else
            {
                return it.advance()
                       ? partitioner.decorateKey(it.key())
                       : endOfData();
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void close()
    {
        it.close();
    }

    public long getBytesRead()
    {
        return it.isExhausted() ? totalBytes : it.dataPosition();
    }

    public long getTotalBytes()
    {
        return totalBytes;
    }

}
