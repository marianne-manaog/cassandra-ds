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

package org.apache.cassandra.db.compaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.agrona.collections.IntArrayList;
import org.apache.cassandra.db.DiskBoundaries;
import org.apache.cassandra.db.SortedLocalRanges;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Splitter;
import org.apache.cassandra.dht.Token;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class ShardManagerTest
{
    final IPartitioner partitioner = Murmur3Partitioner.instance;
    final Token minimumToken = partitioner.getMinimumToken();

    SortedLocalRanges localRanges;
    List<Splitter.WeightedRange> weightedRanges;

    static final double delta = 1e-15;

    @Before
    public void setUp()
    {
        weightedRanges = new ArrayList<>();
        localRanges = Mockito.mock(SortedLocalRanges.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        Mockito.when(localRanges.getRanges()).thenAnswer(invocation -> weightedRanges);
    }

    @Test
    public void testRangeSpannedFullOwnership()
    {
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(minimumToken, minimumToken)));
        ShardManager shardManager = new ShardManagerNoDisks(localRanges);

        // sanity check
        assertEquals(0.4, tokenAt(0.1).size(tokenAt(0.5)), delta);

        assertEquals(0.5, shardManager.rangeSpanned(range(0.2, 0.7)), delta);
        assertEquals(0.2, shardManager.rangeSpanned(range(0.3, 0.5)), delta);

        assertEquals(0.2, shardManager.rangeSpanned(mockedTable(0.5, 0.7, Double.NaN)), delta);
        // single-partition correction
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.3, 0.3, Double.NaN)), delta);

        // reported coverage
        assertEquals(0.1, shardManager.rangeSpanned(mockedTable(0.5, 0.7, 0.1)), delta);
        // bad coverage
        assertEquals(0.2, shardManager.rangeSpanned(mockedTable(0.5, 0.7, 0.0)), delta);
        assertEquals(0.2, shardManager.rangeSpanned(mockedTable(0.5, 0.7, -1)), delta);

        // correction over coverage
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.3, 0.5, 1e-50)), delta);
    }

    @Test
    public void testRangeSpannedPartialOwnership()
    {
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.05), tokenAt(0.15))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.3), tokenAt(0.4))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.45), tokenAt(0.5))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.7), tokenAt(0.75))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.75), tokenAt(0.85))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.90), tokenAt(0.91))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.92), tokenAt(0.94))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.98), tokenAt(1.0))));
        double total = weightedRanges.stream().mapToDouble(wr -> wr.range().left.size(wr.range().right)).sum();

        ShardManager shardManager = new ShardManagerNoDisks(localRanges);

        // sanity check
        assertEquals(0.4, tokenAt(0.1).size(tokenAt(0.5)), delta);

        assertEquals(0.15, shardManager.rangeSpanned(range(0.2, 0.7)), delta);
        assertEquals(0.15, shardManager.rangeSpanned(range(0.3, 0.5)), delta);
        assertEquals(0.0, shardManager.rangeSpanned(range(0.5, 0.7)), delta);
        assertEquals(total, shardManager.rangeSpanned(range(0.0, 1.0)), delta);


        assertEquals(0.1, shardManager.rangeSpanned(mockedTable(0.5, 0.8, Double.NaN)), delta);

        // single-partition correction
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.3, 0.3, Double.NaN)), delta);
        // out-of-local-range correction
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.6, 0.7, Double.NaN)), delta);
        assertEquals(0.001, shardManager.rangeSpanned(mockedTable(0.6, 0.701, Double.NaN)), delta);

        // reported coverage
        assertEquals(0.1, shardManager.rangeSpanned(mockedTable(0.5, 0.7, 0.1)), delta);
        // bad coverage
        assertEquals(0.1, shardManager.rangeSpanned(mockedTable(0.5, 0.8, 0.0)), delta);
        assertEquals(0.1, shardManager.rangeSpanned(mockedTable(0.5, 0.8, -1)), delta);

        // correction over coverage, no recalculation
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.5, 0.8, 1e-50)), delta);
    }

    @Test
    public void testRangeSpannedWeighted()
    {
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.05), tokenAt(0.15))));
        weightedRanges.add(new Splitter.WeightedRange(0.5, new Range<>(tokenAt(0.3), tokenAt(0.4))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.45), tokenAt(0.5))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.7), tokenAt(0.75))));
        weightedRanges.add(new Splitter.WeightedRange(0.2, new Range<>(tokenAt(0.75), tokenAt(0.85))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.90), tokenAt(0.91))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.92), tokenAt(0.94))));
        weightedRanges.add(new Splitter.WeightedRange(1.0, new Range<>(tokenAt(0.98), tokenAt(1.0))));
        double total = weightedRanges.stream().mapToDouble(wr -> wr.size()).sum();

        ShardManager shardManager = new ShardManagerNoDisks(localRanges);

        // sanity check
        assertEquals(0.4, tokenAt(0.1).size(tokenAt(0.5)), delta);

        assertEquals(0.10, shardManager.rangeSpanned(range(0.2, 0.7)), delta);
        assertEquals(0.10, shardManager.rangeSpanned(range(0.3, 0.5)), delta);
        assertEquals(0.0, shardManager.rangeSpanned(range(0.5, 0.7)), delta);
        assertEquals(total, shardManager.rangeSpanned(range(0.0, 1.0)), delta);


        assertEquals(0.06, shardManager.rangeSpanned(mockedTable(0.5, 0.8, Double.NaN)), delta);

        // single-partition correction
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.3, 0.3, Double.NaN)), delta);
        // out-of-local-range correction
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.6, 0.7, Double.NaN)), delta);
        assertEquals(0.001, shardManager.rangeSpanned(mockedTable(0.6, 0.701, Double.NaN)), delta);

        // reported coverage
        assertEquals(0.1, shardManager.rangeSpanned(mockedTable(0.5, 0.7, 0.1)), delta);
        // bad coverage
        assertEquals(0.06, shardManager.rangeSpanned(mockedTable(0.5, 0.8, 0.0)), delta);
        assertEquals(0.06, shardManager.rangeSpanned(mockedTable(0.5, 0.8, -1)), delta);

        // correction over coverage, no recalculation
        assertEquals(1.0, shardManager.rangeSpanned(mockedTable(0.5, 0.8, 1e-50)), delta);
    }

    Token tokenAt(double pos)
    {
        return partitioner.split(minimumToken, minimumToken, pos);
    }

    Range<Token> range(double start, double end)
    {
        return new Range<>(tokenAt(start), tokenAt(end));
    }

    CompactionSSTable mockedTable(double start, double end, double reportedCoverage)
    {
        CompactionSSTable mock = Mockito.mock(CompactionSSTable.class);
        Mockito.when(mock.getFirst()).thenReturn(tokenAt(start).minKeyBound());
        Mockito.when(mock.getLast()).thenReturn(tokenAt(end).minKeyBound());
        Mockito.when(mock.tokenSpaceCoverage()).thenReturn(reportedCoverage);
        return mock;
    }

    @Test
    public void testShardBoundaries()
    {
        // no shards
        testShardBoundaries(ints(), 1, 1, ints(10, 50));
        // split on disks at minimum
        testShardBoundaries(ints(30), 1, 2, ints(10, 50));
        testShardBoundaries(ints(20, 30, 40, 50), 1, 5, ints(10, 51, 61, 70));

        // no disks
        testShardBoundaries(ints(30), 2, 1, ints(10, 50));
        testShardBoundaries(ints(20, 30, 40, 50), 5, 1, ints(10, 51, 61, 70));

        // split
        testShardBoundaries(ints(10, 20, 30, 40, 50, 60, 70, 80), 3, 3, ints(0, 90));
        testShardBoundaries(ints(10, 20, 30, 40, 50, 70, 80, 90), 3, 3, ints(0, 51, 61, 100));
        testShardBoundaries(ints(10, 20, 30, 40, 60, 70, 80, 90), 3, 3, ints(0, 49, 59, 100));
        testShardBoundaries(ints(12, 23, 33, 45, 56, 70, 80, 90), 3, 3, ints(0, 9, 11, 20, 21, 39, 41, 50, 51, 60, 64, 68, 68, 100));

        // uneven
        testShardBoundaries(ints(8, 16, 24, 32, 42, 52, 62, 72, 79, 86, 93), 4, ints(32, 72, 100), ints(0, 100));
        testShardBoundaries(ints(1, 2, 3, 4, 6, 8, 10, 12, 34, 56, 78), 4, ints(4, 12, 100), ints(0, 100));
    }

    @Test
    public void testShardBoundariesWraparound()
    {
        // no shards
        testShardBoundaries(ints(), 1, 1, ints(50, 10));
        // split on disks at minimum
        testShardBoundaries(ints(70), 1, 2, ints(50, 10));
        testShardBoundaries(ints(10, 20, 30, 70), 1, 5, ints(91, 31, 61, 71));
        // no disks
        testShardBoundaries(ints(70), 2, 1, ints(50, 10));
        testShardBoundaries(ints(10, 20, 30, 70), 5, 1, ints(91, 31, 61, 71));
        // split
        testShardBoundaries(ints(10, 20, 30, 40, 50, 60, 70, 90), 3, 3, ints(81, 71));
        testShardBoundaries(ints(10, 20, 30, 40, 60, 70, 80, 90), 3, 3, ints(51, 41));
        testShardBoundaries(ints(10, 30, 40, 50, 60, 70, 80, 90), 3, 3, ints(21, 11));
        testShardBoundaries(ints(10, 20, 30, 40, 50, 60, 70, 90), 3, 3, ints(89, 79));
        testShardBoundaries(ints(10, 20, 30, 40, 60, 70, 80, 90), 3, 3, ints(59, 49));
        testShardBoundaries(ints(10, 30, 40, 50, 60, 70, 80, 90), 3, 3, ints(29, 19));

        testShardBoundaries(ints(10, 20, 30, 40, 50, 70, 80, 90), 3, 3, ints(91, 51, 61, 91));
        testShardBoundaries(ints(10, 20, 30, 40, 50, 70, 80, 90), 3, 3, ints(21, 51, 61, 21));
        testShardBoundaries(ints(10, 20, 30, 40, 50, 70, 80, 90), 3, 3, ints(71, 51, 61, 71));
    }

    @Test
    public void testShardBoundariesWeighted()
    {
        // no shards
        testShardBoundariesWeighted(ints(), 1, 1, ints(10, 50));
        // split on disks at minimum
        testShardBoundariesWeighted(ints(30), 1, 2, ints(10, 50));
        testShardBoundariesWeighted(ints(22, 34, 45, 64), 1, 5, ints(10, 51, 61, 70));

        // no disks
        testShardBoundariesWeighted(ints(30), 2, 1, ints(10, 50));
        testShardBoundariesWeighted(ints(22, 34, 45, 64), 5, 1, ints(10, 51, 61, 70));

        // split
        testShardBoundariesWeighted(ints(10, 20, 30, 40, 50, 60, 70, 80), 3, 3, ints(0, 90));
        testShardBoundariesWeighted(ints(14, 29, 43, 64, 71, 79, 86, 93), 3, 3, ints(0, 51, 61, 100));
        testShardBoundariesWeighted(ints(18, 36, 50, 63, 74, 83, 91, 96), 3, 3, ints(0, 40, 40, 70, 70, 90, 90, 100));
    }

    private int[] ints(int... values)
    {
        return values;
    }

    private void testShardBoundaries(int[] expected, int numShards, int numDisks, int[] rangeBounds)
    {
        CompactionRealm realm = Mockito.mock(CompactionRealm.class);
        when(realm.getPartitioner()).thenReturn(partitioner);

        List<Splitter.WeightedRange> ranges = new ArrayList<>();
        for (int i = 0; i < rangeBounds.length; i += 2)
            ranges.add(new Splitter.WeightedRange(1.0, new Range<>(getToken(rangeBounds[i + 0]), getToken(rangeBounds[i + 1]))));
        SortedLocalRanges sortedRanges = SortedLocalRanges.forTesting(realm, ranges);

        List<Token> diskBoundaries = sortedRanges.split(numDisks);
        int[] result = getShardBoundaries(numShards, diskBoundaries, sortedRanges);
        Assert.assertArrayEquals("Disks " + numDisks + " shards " + numShards + " expected " + Arrays.toString(expected) + " was " + Arrays.toString(result), expected, result);
    }

    private void testShardBoundariesWeighted(int[] expected, int numShards, int numDisks, int[] rangeBounds)
    {
        CompactionRealm realm = Mockito.mock(CompactionRealm.class);
        when(realm.getPartitioner()).thenReturn(partitioner);

        List<Splitter.WeightedRange> ranges = new ArrayList<>();
        for (int i = 0; i < rangeBounds.length; i += 2)
            ranges.add(new Splitter.WeightedRange(2.0 / (rangeBounds.length - i), new Range<>(getToken(rangeBounds[i + 0]), getToken(rangeBounds[i + 1]))));
        SortedLocalRanges sortedRanges = SortedLocalRanges.forTesting(realm, ranges);

        List<Token> diskBoundaries = sortedRanges.split(numDisks);
        int[] result = getShardBoundaries(numShards, diskBoundaries, sortedRanges);
        Assert.assertArrayEquals("Disks " + numDisks + " shards " + numShards + " expected " + Arrays.toString(expected) + " was " + Arrays.toString(result), expected, result);
    }

    private void testShardBoundaries(int[] expected, int numShards, int[] diskPositions, int[] rangeBounds)
    {
        CompactionRealm realm = Mockito.mock(CompactionRealm.class);
        when(realm.getPartitioner()).thenReturn(partitioner);

        List<Splitter.WeightedRange> ranges = new ArrayList<>();
        for (int i = 0; i < rangeBounds.length; i += 2)
            ranges.add(new Splitter.WeightedRange(1.0, new Range<>(getToken(rangeBounds[i + 0]), getToken(rangeBounds[i + 1]))));
        SortedLocalRanges sortedRanges = SortedLocalRanges.forTesting(realm, ranges);

        List<Token> diskBoundaries = Arrays.stream(diskPositions).mapToObj(this::getToken).collect(Collectors.toList());
        int[] result = getShardBoundaries(numShards, diskBoundaries, sortedRanges);
        Assert.assertArrayEquals("Disks " + Arrays.toString(diskPositions) + " shards " + numShards + " expected " + Arrays.toString(expected) + " was " + Arrays.toString(result), expected, result);
    }

    private int[] getShardBoundaries(int numShards, List<Token> diskBoundaries, SortedLocalRanges sortedRanges)
    {
        DiskBoundaries db = Mockito.mock(DiskBoundaries.class);
        when(db.getLocalRanges()).thenReturn(sortedRanges);
        when(db.getPositions()).thenReturn(diskBoundaries);

        final ShardTracker shardTracker = ShardManager.create(db)
                                                      .boundaries(numShards);
        IntArrayList list = new IntArrayList();
        for (int i = 0; i < 100; ++i)
        {
            if (shardTracker.advanceTo(getToken(i)))
                list.addInt(fromToken(shardTracker.shardStart()));
        }
        return list.toIntArray();
    }

    private Token getToken(int x)
    {
        return tokenAt(x / 100.0);
    }

    private int fromToken(Token t)
    {
        return (int) Math.round(partitioner.getMinimumToken().size(t) * 100.0);
    }

    @Test
    public void testRangeEnds()
    {
        CompactionRealm realm = Mockito.mock(CompactionRealm.class);
        when(realm.getPartitioner()).thenReturn(partitioner);
        SortedLocalRanges sortedRanges = SortedLocalRanges.forTestingFull(realm);

        for (int numDisks = 1; numDisks <= 3; ++numDisks)
        {
            List<Token> diskBoundaries = sortedRanges.split(numDisks);
            DiskBoundaries db = Mockito.mock(DiskBoundaries.class);
            when(db.getLocalRanges()).thenReturn(sortedRanges);
            when(db.getPositions()).thenReturn(diskBoundaries);

            ShardManager shardManager = ShardManager.create(db);
            for (int numShards = 1; numShards <= 3; ++numShards)
            {
                ShardTracker iterator = shardManager.boundaries(numShards);
                iterator.advanceTo(partitioner.getMinimumToken());

                int count = 1;
                for (Token end = iterator.shardEnd(); end != null; end = iterator.shardEnd())
                {
                    assertFalse(iterator.advanceTo(end));
                    assertTrue(iterator.advanceTo(end.nextValidToken()));
                    ++count;
                }
                assertEquals(numDisks * numShards, count);
            }
        }
    }
}
