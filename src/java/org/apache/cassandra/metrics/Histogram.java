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

package org.apache.cassandra.metrics;

import com.google.common.annotations.VisibleForTesting;

import com.codahale.metrics.*;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.EstimatedHistogram;

/**
 * A metric which calculates the distribution of a value.
 *
 * @see <a href="http://www.johndcook.com/standard_deviation.html">Accurately computing running
 *      variance</a>
 *
 * This class removes the {@link java.util.concurrent.atomic.LongAdder} of the {@link com.codahale.metrics.Histogram}
 * class and retrieves {@link this#getCount()} from {@link Reservoir}.
 *
 * This class needs to extend {@link com.codahale.metrics.Histogram} to allow this metric
 * to be retrieved by {@link MetricRegistry#getHistograms()} (used by {@link ScheduledReporter})
 * but we can't easily do that since we need access to the reservoir, which can be shared. We also
 * don't want to use a LongAdder by default. Was it not for this fact, it would have been an interface.
 */
public abstract class Histogram extends com.codahale.metrics.Histogram implements Composable<Histogram>
{
    /**
     * Whether zeros are considered by default.
     */
    public static boolean DEFAULT_ZERO_CONSIDERATION = false;

    /** The maximum trackable value, 18 TB. This comes from the legacy implementation based on
     * {@link EstimatedHistogram#newOffsets(int, boolean)} with size set to 164 and  considerZeros
     * set to true.*/
    public static long DEFAULT_MAX_TRACKABLE_VALUE = 18 * (1L << 43);

    protected Histogram()
    {
        super(null); // use a fake null reservoir for the base since our implementors use a different one and override all methods
    }

    /**
     * Adds a recorded value.
     *
     * @param value the length of the value
     */
    public abstract void update(final long value);

    /**
     * @return the number of values recorded
     */
    @Override
    public abstract long getCount();  //from Counting

    /**
     *
     * @return a snapshot of the histogram.
     */
    @Override
    public abstract Snapshot getSnapshot(); //from Sampling

    @VisibleForTesting
    public abstract void clear();

    @VisibleForTesting
    public abstract void aggregate();

    public abstract boolean considerZeroes();

    public abstract long maxTrackableValue();

    public abstract long[] getOffsets();

    public static Histogram make(boolean isComposite)
    {
        return make(DEFAULT_ZERO_CONSIDERATION, isComposite);
    }

    public static Histogram make(boolean considerZeroes, boolean isComposite)
    {
        return make(considerZeroes, DEFAULT_MAX_TRACKABLE_VALUE, isComposite);
    }

    public static Histogram make(boolean considerZeroes, long maxTrackableValue, boolean isComposite)
    {
        return make(considerZeroes, maxTrackableValue, DatabaseDescriptor.getMetricsHistogramUpdateTimeMillis(), isComposite);
    }

    public static Histogram make(boolean considerZeroes, long maxTrackableValue, int updateIntervalMillis, boolean isComposite)
    {
        return isComposite
               ? new CompositeHistogram(DecayingEstimatedHistogram.makeCompositeReservoir(considerZeroes, maxTrackableValue, updateIntervalMillis, ApproximateClock.defaultClock()))
               : new DecayingEstimatedHistogram(considerZeroes, maxTrackableValue, updateIntervalMillis, ApproximateClock.defaultClock());
    }

    interface Reservoir
    {
        boolean considerZeroes();

        long maxTrackableValue();

        long getCount();

        Snapshot getSnapshot();

        void add(Histogram histogram);

        long[] getOffsets();
    }
}
