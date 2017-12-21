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
package org.apache.cassandra.db.marshal;

import java.nio.ByteBuffer;

import org.apache.commons.lang3.mutable.MutableInt;

import org.apache.cassandra.cql3.*;
import org.apache.cassandra.cql3.functions.ArgumentDeserializer;
import org.apache.cassandra.serializers.*;
import org.apache.cassandra.transport.ProtocolVersion;
import org.apache.cassandra.utils.*;

public class Int32Type extends NumberType<Integer>
{
    public static final Int32Type instance = new Int32Type();

    Int32Type()
    {
        super(ComparisonType.PRIMITIVE_COMPARE, 4, PrimitiveType.INT32);
    } // singleton

    public boolean isEmptyValueMeaningless()
    {
        return true;
    }

    public static int compareType(ByteBuffer o1, ByteBuffer o2)
    {
        return Integer.compare(UnsafeByteBufferAccess.getInt(o1), UnsafeByteBufferAccess.getInt(o2));
    }

    public ByteSource asByteComparableSource(ByteBuffer buf)
    {
        return ByteSource.optionalSignedFixedLengthNumber(buf);
    }

    public ByteBuffer fromString(String source) throws MarshalException
    {
        // Return an empty ByteBuffer for an empty string.
        if (source.isEmpty())
            return ByteBufferUtil.EMPTY_BYTE_BUFFER;

        int int32Type;

        try
        {
            int32Type = Integer.parseInt(source);
        }
        catch (Exception e)
        {
            throw new MarshalException(String.format("Unable to make int from '%s'", source), e);
        }

        return decompose(int32Type);
    }

    @Override
    public Term fromJSONObject(Object parsed) throws MarshalException
    {
        try
        {
            if (parsed instanceof String)
                return new Constants.Value(fromString((String) parsed));

            Number parsedNumber = (Number) parsed;
            if (!(parsedNumber instanceof Integer))
                throw new MarshalException(String.format("Expected an int value, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));

            return new Constants.Value(getSerializer().serialize(parsedNumber.intValue()));
        }
        catch (ClassCastException exc)
        {
            throw new MarshalException(String.format(
                    "Expected an int value, but got a %s: %s", parsed.getClass().getSimpleName(), parsed));
        }
    }

    @Override
    public String toJSONString(ByteBuffer buffer, ProtocolVersion protocolVersion)
    {
        return getSerializer().deserialize(buffer).toString();
    }

    public CQL3Type asCQL3Type()
    {
        return CQL3Type.Native.INT;
    }

    public TypeSerializer<Integer> getSerializer()
    {
        return Int32Serializer.instance;
    }

    @Override
    public ByteBuffer add(Number left, Number right)
    {
        return ByteBufferUtil.bytes(left.intValue() + right.intValue());
    }

    @Override
    public ByteBuffer substract(Number left, Number right)
    {
        return ByteBufferUtil.bytes(left.intValue() - right.intValue());
    }

    @Override
    public ByteBuffer multiply(Number left, Number right)
    {
        return ByteBufferUtil.bytes(left.intValue() * right.intValue());
    }

    @Override
    public ByteBuffer divide(Number left, Number right)
    {
        return ByteBufferUtil.bytes(left.intValue() / right.intValue());
    }

    @Override
    public ByteBuffer mod(Number left, Number right)
    {
        return ByteBufferUtil.bytes(left.intValue() % right.intValue());
    }

    @Override
    public ByteBuffer negate(Number input)
    {
        return ByteBufferUtil.bytes(-input.intValue());
    }

    @Override
    public ArgumentDeserializer getArgumentDeserializer()
    {
        return new NumberArgumentDeserializer<MutableInt>(new MutableInt())
        {
            @Override
            protected void setMutableValue(MutableInt mutable, ByteBuffer buffer)
            {
                mutable.setValue(ByteBufferUtil.toInt(buffer));
            }
        };
    }
}
