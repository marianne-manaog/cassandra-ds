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
package org.apache.cassandra.service;

import java.io.IOException;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.index.SecondaryIndexManager;
import org.apache.cassandra.index.StubIndex;

public class JoinTokenRingTest
{
    @BeforeClass
    public static void setup() throws ConfigurationException
    {
        DatabaseDescriptor.daemonInitialization();
        SchemaLoader.startGossiper();
        // use this hook to make the schema changes before joinRing() happens
        CQLTester.preJoinHook = () -> SchemaLoader.schemaDefinition("JoinTokenRingTest");
        SchemaLoader.prepareServer();
    }

    @Test
    public void testIndexPreJoinInvocation() throws IOException
    {
        StorageService ss = StorageService.instance;
        ss.joinRing();

        SecondaryIndexManager indexManager = ColumnFamilyStore.getIfExists("JoinTokenRingTestKeyspace7", "Indexed1").indexManager;
        StubIndex stub = (StubIndex) indexManager.getIndexByName("Indexed1_value_index");
        Assert.assertTrue(stub.preJoinInvocation);
    }
}
