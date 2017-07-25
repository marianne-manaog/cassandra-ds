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

package org.apache.cassandra.db;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Lists;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.filter.ClusteringIndexSliceFilter;
import org.apache.cassandra.db.filter.ColumnFilter;
import org.apache.cassandra.db.filter.DataLimits;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.monitoring.AbortedOperationException;
import org.apache.cassandra.db.monitoring.ApproximateTime;
import org.apache.cassandra.db.monitoring.Monitor;
import org.apache.cassandra.db.partitions.FilteredPartition;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.PartitionIterators;
import org.apache.cassandra.db.partitions.UnfilteredPartitionsSerializer;
import org.apache.cassandra.db.rows.FlowablePartition;
import org.apache.cassandra.db.rows.FlowablePartitions;
import org.apache.cassandra.db.rows.FlowableUnfilteredPartition;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.RowIterator;
import org.apache.cassandra.db.rows.SerializationHelper;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.flow.Flow;
import org.apache.cassandra.utils.versioning.Version;

import static org.junit.Assert.*;

public class ReadCommandTest
{
    private static final String KEYSPACE = "ReadCommandTest";
    private static final String CF1 = "Standard1";
    private static final String CF2 = "Standard2";
    private static final String CF3 = "Standard3";

    @BeforeClass
    public static void defineSchema() throws ConfigurationException
    {
        DatabaseDescriptor.daemonInitialization();

        TableMetadata.Builder metadata1 = SchemaLoader.standardCFMD(KEYSPACE, CF1);

        TableMetadata.Builder metadata2 =
            TableMetadata.builder(KEYSPACE, CF2)
                         .addPartitionKeyColumn("key", BytesType.instance)
                         .addClusteringColumn("col", AsciiType.instance)
                         .addRegularColumn("a", AsciiType.instance)
                         .addRegularColumn("b", AsciiType.instance);

        TableMetadata.Builder metadata3 =
            TableMetadata.builder(KEYSPACE, CF3)
                         .addPartitionKeyColumn("key", BytesType.instance)
                         .addClusteringColumn("col", AsciiType.instance)
                         .addRegularColumn("a", AsciiType.instance)
                         .addRegularColumn("b", AsciiType.instance)
                         .addRegularColumn("c", AsciiType.instance)
                         .addRegularColumn("d", AsciiType.instance)
                         .addRegularColumn("e", AsciiType.instance)
                         .addRegularColumn("f", AsciiType.instance);

        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE, KeyspaceParams.simple(1), metadata1, metadata2, metadata3);
    }

    @Test
    public void testPartitionRangeAbort() throws Exception
    {
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE).getColumnFamilyStore(CF1);

        new RowUpdateBuilder(cfs.metadata(), 0, ByteBufferUtil.bytes("key1"))
                .clustering("Column1")
                .add("val", ByteBufferUtil.bytes("abcd"))
                .build()
                .apply();

        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata(), 0, ByteBufferUtil.bytes("key2"))
                .clustering("Column1")
                .add("val", ByteBufferUtil.bytes("abcd"))
                .build()
                .apply();

        ReadCommand readCommand = Util.cmd(cfs).build();
        assertEquals(2, Util.getAll(readCommand).size());

        Monitor monitor = Monitor.createAndStart(readCommand, ApproximateTime.currentTimeMillis(), 0, false);

        try (PartitionIterator iterator = FlowablePartitions.toPartitionsFiltered(readCommand.executeInternal(monitor)))
        {
            PartitionIterators.consume(iterator);
            fail("The command should have been aborted");
        }
        catch (AbortedOperationException e)
        {
            // Expected
        }
    }

    @Test
    public void testSinglePartitionSliceAbort() throws Exception
    {
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE).getColumnFamilyStore(CF2);

        cfs.truncateBlocking();

        new RowUpdateBuilder(cfs.metadata(), 0, ByteBufferUtil.bytes("key"))
                .clustering("cc")
                .add("a", ByteBufferUtil.bytes("abcd"))
                .build()
                .apply();

        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata(), 0, ByteBufferUtil.bytes("key"))
                .clustering("dd")
                .add("a", ByteBufferUtil.bytes("abcd"))
                .build()
                .apply();

        ReadCommand readCommand = Util.cmd(cfs, Util.dk("key")).build();

        List<FilteredPartition> partitions = Util.getAll(readCommand);
        assertEquals(1, partitions.size());
        assertEquals(2, partitions.get(0).rowCount());

        Monitor monitor = Monitor.createAndStart(readCommand, ApproximateTime.currentTimeMillis(), 0, false);

        readCommand = readCommand.withUpdatedLimit(readCommand.limits());   // duplicate command as they are not reusable
        try (PartitionIterator iterator = FlowablePartitions.toPartitionsFiltered(readCommand.executeInternal(monitor)))
        {
            PartitionIterators.consume(iterator);
            fail("The command should have been aborted");
        }
        catch (AbortedOperationException e)
        {
            // Expected
        }
    }

    @Test
    public void testSinglePartitionNamesAbort() throws Exception
    {
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE).getColumnFamilyStore(CF2);

        cfs.truncateBlocking();

        new RowUpdateBuilder(cfs.metadata(), 0, ByteBufferUtil.bytes("key"))
                .clustering("cc")
                .add("a", ByteBufferUtil.bytes("abcd"))
                .build()
                .apply();

        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata(), 0, ByteBufferUtil.bytes("key"))
                .clustering("dd")
                .add("a", ByteBufferUtil.bytes("abcd"))
                .build()
                .apply();

        ReadCommand readCommand = Util.cmd(cfs, Util.dk("key")).includeRow("cc").includeRow("dd").build();

        List<FilteredPartition> partitions = Util.getAll(readCommand);
        assertEquals(1, partitions.size());
        assertEquals(2, partitions.get(0).rowCount());

        Monitor monitor = Monitor.createAndStart(readCommand, ApproximateTime.currentTimeMillis(), 0, false);

        readCommand = readCommand.withUpdatedLimit(readCommand.limits());   // duplicate command as they are not reusable
        try (PartitionIterator iterator = FlowablePartitions.toPartitionsFiltered(readCommand.executeInternal(monitor)))
        {
            PartitionIterators.consume(iterator);
            fail("The command should have been aborted");
        }
        catch (AbortedOperationException e)
        {
            // Expected
        }
    }

    @Test
    public void testSinglePartitionGroupMerge() throws Exception
    {
        ColumnFamilyStore cfs = Keyspace.open(KEYSPACE).getColumnFamilyStore(CF3);

        String[][][] groups = new String[][][]{
        new String[][]{
        new String[]{ "1", "key1", "aa", "a" }, // "1" indicates to create the data, "-1" to delete the row
        new String[]{ "1", "key2", "bb", "b" },
        new String[]{ "1", "key3", "cc", "c" }
        },
        new String[][]{
        new String[]{ "1", "key3", "dd", "d" },
        new String[]{ "1", "key2", "ee", "e" },
        new String[]{ "1", "key1", "ff", "f" }
        },
        new String[][]{
        new String[]{ "1", "key6", "aa", "a" },
        new String[]{ "1", "key5", "bb", "b" },
        new String[]{ "1", "key4", "cc", "c" }
        },
        new String[][]{
        new String[]{ "-1", "key6", "aa", "a" },
        new String[]{ "-1", "key2", "bb", "b" }
        }
        };

        // Given the data above, when the keys are sorted and the deletions removed, we should
        // get these clustering rows in this order
        List<String> expectedRows = Lists.newArrayList("col=aa", "col=ff", "col=ee", "col=cc", "col=dd", "col=cc", "col=bb");

        List<ByteBuffer> buffers = new ArrayList<>(groups.length);
        int nowInSeconds = FBUtilities.nowInSeconds();
        ColumnFilter columnFilter = ColumnFilter.allRegularColumnsBuilder(cfs.metadata()).build();
        RowFilter rowFilter = RowFilter.create();
        Slice slice = Slice.make(ClusteringBound.BOTTOM, ClusteringBound.TOP);
        ClusteringIndexSliceFilter sliceFilter = new ClusteringIndexSliceFilter(Slices.with(cfs.metadata().comparator, slice), false);
        EncodingVersion version = Version.last(EncodingVersion.class);

        for (String[][] group : groups)
        {
            cfs.truncateBlocking();

            List<SinglePartitionReadCommand> commands = new ArrayList<>(group.length);

            for (String[] data : group)
            {
                if (data[0].equals("1"))
                {
                    new RowUpdateBuilder(cfs.metadata(), 0, ByteBufferUtil.bytes(data[1]))
                    .clustering(data[2])
                    .add(data[3], ByteBufferUtil.bytes("blah"))
                    .build()
                    .apply();
                }
                else
                {
                    RowUpdateBuilder.deleteRow(cfs.metadata(), FBUtilities.timestampMicros(), ByteBufferUtil.bytes(data[1]), data[2]).apply();
                }
                commands.add(SinglePartitionReadCommand.create(cfs.metadata(), nowInSeconds, columnFilter, rowFilter, DataLimits.NONE, Util.dk(data[1]), sliceFilter));
            }

            cfs.forceBlockingFlush();

            ReadQuery query = new SinglePartitionReadCommand.Group(commands, DataLimits.NONE);

            Flow<FlowableUnfilteredPartition> partitions = FlowablePartitions.skipEmptyPartitions(query.executeLocally());
            UnfilteredPartitionsSerializer.Serializer serializer = UnfilteredPartitionsSerializer.serializerForIntraNode(version);
            buffers.addAll(serializer.serialize(partitions, columnFilter).toList().blockingSingle());
        }

        // deserialize, merge and check the results are all there
        List<Flow<FlowableUnfilteredPartition>> partitions = new ArrayList<>();

        UnfilteredPartitionsSerializer.Serializer serializer = UnfilteredPartitionsSerializer.serializerForIntraNode(version);
        for (ByteBuffer buffer : buffers)
        {
            partitions.add(serializer.deserialize(buffer,
                                                  cfs.metadata(),
                                                  columnFilter,
                                                  SerializationHelper.Flag.LOCAL));
        }

        Flow<FlowablePartition> merged = FlowablePartitions.mergeAndFilter(partitions,
                                                                           nowInSeconds,
                                                                           FlowablePartitions.MergeListener.NONE);


        int i = 0;
        int numPartitions = 0;

        try(PartitionIterator partitionIterator = FlowablePartitions.toPartitionsFiltered(merged))
        {
            while (partitionIterator.hasNext())
            {
                numPartitions++;
                try (RowIterator rowIterator = partitionIterator.next())
                {
                    while (rowIterator.hasNext())
                    {
                        i++;
                        Row row = rowIterator.next();
                        assertTrue(expectedRows.contains(row.clustering().toString(cfs.metadata())));
                        //System.out.print(row.toString(cfs.metadata, true));
                    }
                }
            }

            assertEquals(5, numPartitions);
            assertEquals(expectedRows.size(), i);
        }
    }
}
