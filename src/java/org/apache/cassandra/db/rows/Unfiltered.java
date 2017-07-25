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
package org.apache.cassandra.db.rows;

import java.security.MessageDigest;

import org.apache.cassandra.db.DeletionPurger;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.db.Clusterable;

/**
 * Unfiltered is the common class for the main constituent of an unfiltered partition.
 * <p>
 * In practice, an Unfiltered is either a row or a range tombstone marker. Unfiltereds
 * are uniquely identified by their clustering information and can be sorted according
 * to those.
 */
public interface Unfiltered extends Clusterable
{
    public enum Kind { ROW, RANGE_TOMBSTONE_MARKER };
    // TODO: add sstable lower bound for clearer merging

    /**
     * The kind of the atom: either row or range tombstone marker. In flowables, the first Unfiltered is a header.
     */
    public Kind kind();

    /**
     * Digest the atom using the provided {@code MessageDigest}.
     *
     * @param digest the {@code MessageDigest} to use.
     */
    public void digest(MessageDigest digest);

    /**
     * Validate the data of this atom.
     *
     * @param metadata the metadata for the table this atom is part of.
     * @throws org.apache.cassandra.serializers.MarshalException if some of the data in this atom is
     * invalid (some value is invalid for its column type, or some field
     * is nonsensical).
     */
    public void validateData(TableMetadata metadata);

    public boolean isEmpty();

    public String toString(TableMetadata metadata);
    public String toString(TableMetadata metadata, boolean fullDetails);
    public String toString(TableMetadata metadata, boolean includeClusterKeys, boolean fullDetails);

    default boolean isRow()
    {
        return kind() == Kind.ROW;
    }

    default boolean isRangeTombstoneMarker()
    {
        return kind() == Kind.RANGE_TOMBSTONE_MARKER;
    }


    /**
     * Returns a copy of this row or marker without any deletion info that should be purged according to {@code purger}.
     *
     * @param purger the {@code DeletionPurger} to use to decide what can be purged.
     * @param nowInSec the current time to decide what is deleted and what isn't (in the case of expired cells).
     * @return this row but without any deletion info purged by {@code purger}. If the purged row is empty, returns
     * {@code null}.
     */
    public Unfiltered purge(DeletionPurger purger, int nowInSec);
}
