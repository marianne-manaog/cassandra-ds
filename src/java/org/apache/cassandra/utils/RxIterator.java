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

package org.apache.cassandra.utils;

import java.io.Closeable;
import java.util.Iterator;

import com.google.common.collect.Iterables;

import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import org.apache.cassandra.db.AsObservable;

public interface RxIterator<T> extends Iterator<Single<T>>, AsObservable<T>, Closeable
{
    public default Flowable<T> asObservable()
    {
        return Single.merge(Iterables.transform(() -> this, s -> (SingleSource<T>) s))
                     .doOnTerminate(() -> close());
    }
}
