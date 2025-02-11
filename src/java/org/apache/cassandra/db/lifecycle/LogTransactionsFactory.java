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
package org.apache.cassandra.db.lifecycle;

import java.util.UUID;

import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.schema.TableMetadataRef;

final class LogTransactionsFactory implements ILogTransactionsFactory
{
    @Override
    public AbstractLogTransaction createLogTransaction(OperationType operationType, UUID uuid, TableMetadataRef metadata)
    {
        logger.debug("Creating a transaction for {} on {}", operationType, metadata);
        return new LogTransaction(operationType, uuid);
    }

    @Override
    public ILogAwareFileLister createLogAwareFileLister()
    {
        return new LogAwareFileLister();
    }

    @Override
    public ILogFileCleaner createLogFileCleaner()
    {
        return new LogFileCleaner();
    }

    @Override
    public FailedTransactionDeletionHandler createFailedTransactionDeletionHandler()
    {
        return LogTransaction::rescheduleFailedDeletions;
    }
}
