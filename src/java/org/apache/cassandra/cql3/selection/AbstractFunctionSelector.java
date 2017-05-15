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
package org.apache.cassandra.cql3.selection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.common.base.Objects;

import org.apache.commons.lang3.text.StrBuilder;

import org.apache.cassandra.cql3.ColumnSpecification;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.functions.*;
import org.apache.cassandra.cql3.statements.RequestValidations;
import org.apache.cassandra.db.ReadVerbs.ReadVersion;
import org.apache.cassandra.db.TypeSizes;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.schema.*;
import org.apache.cassandra.transport.ProtocolVersion;
import org.apache.cassandra.utils.ByteBufferUtil;

import static java.util.stream.Collectors.joining;

abstract class AbstractFunctionSelector<T extends Function> extends Selector
{
    protected static abstract class AbstractFunctionSelectorDeserializer extends SelectorDeserializer
    {
        protected Selector deserialize(DataInputPlus in, ReadVersion version, TableMetadata metadata) throws IOException
        {
            // The selector will only be deserialized on the replicas for GROUP BY queries. Due to that we
            // can safely use the current protocol version.
            final ProtocolVersion protocolVersion = ProtocolVersion.CURRENT;

            FunctionName name = new FunctionName(in.readUTF(), in.readUTF());

            KeyspaceMetadata keyspace = Schema.instance.getKeyspaceMetadata(metadata.keyspace);

            final int numberOfArguments = (int) in.readUnsignedVInt();
            List<AbstractType<?>> argTypes = new ArrayList<>(numberOfArguments);
            for (int i = 0; i < numberOfArguments; i++)
            {
                argTypes.add(readType(keyspace, in));
            }

            Optional<Function> optional = Schema.instance.findFunction(name, argTypes);

            if (!optional.isPresent())
                throw new IOException(String.format("Unknown serialized function %s(%s)",
                                                    name,
                                                    argTypes.stream()
                                                            .map(p -> p.asCQL3Type().toString())
                                                            .collect(joining(", "))));

            Function function = optional.get();

            boolean isPartial = in.readBoolean();
            // if the function is partial we need to retrieve the resolved parameters.
            // The resolved parameters are encoded as follow: [vint]([vint][bytes])*
            // The first vint contains the bitset used to determine if which parameters were resolved.
            // A bit equals to one meaning a resolved parameter.
            // The parameter values are encoded as [vint][bytes] where the vint contains the size in bytes of the
            // parameter value.
            if (isPartial)
            {
                // We use a bitset to track the position of the unresolved arguments
                int bitset = (int) in.readUnsignedVInt();
                List<ByteBuffer> partialParameters = new ArrayList<>(numberOfArguments);
                for (int i = 0; i < numberOfArguments; i++)
                {
                    boolean isParameterResolved = getRightMostBit(bitset) == 1;
                    ByteBuffer parameter = isParameterResolved ? ByteBufferUtil.readWithVIntLength(in)
                                                               : Function.UNRESOLVED;
                    partialParameters.add(parameter);
                    bitset >>= 1;
                }

                function = ((ScalarFunction) function).partialApplication(protocolVersion, partialParameters);
            }

            int numberOfRemainingArguments = (int) in.readUnsignedVInt();
            List<Selector> argSelectors = new ArrayList<>(numberOfRemainingArguments);
            for (int i = 0; i < numberOfRemainingArguments; i++)
            {
                argSelectors.add(Selector.serializers.get(version).deserialize(in, metadata));
            }

            return newFunctionSelector(protocolVersion, function, argSelectors);
        }

        /**
         * Returns the value of the right most bit.
         * @param bitset the bitset
         * @return the value of the right most bit
         */
        private int getRightMostBit(int bitset)
        {
            return bitset & 1;
        }

        protected abstract Selector newFunctionSelector(ProtocolVersion version,
                                                        Function function,
                                                        List<Selector> argSelectors);
    };

    protected final T fun;

    /**
     * The list used to pass the function arguments is recycled to avoid the cost of instantiating a new list
     * with each function call.
     */
    private final Arguments args;
    protected final List<Selector> argSelectors;

    public static Factory newFactory(final Function fun, final SelectorFactories factories) throws InvalidRequestException
    {
        if (fun.isAggregate())
        {
            if (factories.doesAggregation())
                throw new InvalidRequestException("aggregate functions cannot be used as arguments of aggregate functions");
        }

        return new Factory()
        {
            protected String getColumnName()
            {
                return fun.columnName(factories.getColumnNames());
            }

            protected AbstractType<?> getReturnType()
            {
                return fun.returnType();
            }

            protected void addColumnMapping(SelectionColumnMapping mapping, ColumnSpecification resultsColumn)
            {
                SelectionColumnMapping tmpMapping = SelectionColumnMapping.newMapping();
                for (Factory factory : factories)
                   factory.addColumnMapping(tmpMapping, resultsColumn);

                if (tmpMapping.getMappings().get(resultsColumn).isEmpty())
                    // add a null mapping for cases where there are no
                    // further selectors, such as no-arg functions and count
                    mapping.addMapping(resultsColumn, (ColumnMetadata)null);
                else
                    // collate the mapped columns from the child factories & add those
                    mapping.addMapping(resultsColumn, tmpMapping.getMappings().values());
            }

            public void addFunctionsTo(List<Function> functions)
            {
                fun.addFunctionsTo(functions);
                factories.addFunctionsTo(functions);
            }

            public Selector newInstance(QueryOptions options) throws InvalidRequestException
            {
                return fun.isAggregate() ? new AggregateFunctionSelector(options.getProtocolVersion(), fun, factories.newInstances(options))
                                         : createScalarSelector(options, (ScalarFunction) fun, factories.newInstances(options));
            }

            public boolean isWritetimeSelectorFactory()
            {
                return factories.containsWritetimeSelectorFactory();
            }

            public boolean isTTLSelectorFactory()
            {
                return factories.containsTTLSelectorFactory();
            }

            public boolean isAggregateSelectorFactory()
            {
                return fun.isAggregate() || factories.doesAggregation();
            }

            private Selector createScalarSelector(QueryOptions options, ScalarFunction function, List<Selector> argSelectors)
            {
                ProtocolVersion version = options.getProtocolVersion();
                int terminalCount = 0;
                List<ByteBuffer> terminalArgs = new ArrayList<>(argSelectors.size());
                for (Selector selector : argSelectors)
                {
                    if (selector.isTerminal())
                    {
                        ++terminalCount;
                        ByteBuffer output = selector.getOutput(version);
                        RequestValidations.checkBindValueSet(output, "Invalid unset value for argument in call to function %s", fun.name().name);
                        terminalArgs.add(output);
                    }
                    else
                    {
                        terminalArgs.add(Function.UNRESOLVED);
                    }
                }

                if (terminalCount == 0)
                    return new ScalarFunctionSelector(version, fun, argSelectors);

                // We have some terminal arguments, do a partial application
                ScalarFunction partialFunction = function.partialApplication(version, terminalArgs);

                // If all the arguments are terminal and the function is pure we can reduce to a simple value.
                if (terminalCount == argSelectors.size() && fun.isPure())
                {
                    Arguments arguments = partialFunction.newArguments(version);
                    return new TermSelector(partialFunction.execute(arguments), partialFunction.returnType());
                }

                List<Selector> remainingSelectors = new ArrayList<>(argSelectors.size() - terminalCount);
                for (Selector selector : argSelectors)
                {
                    if (!selector.isTerminal())
                        remainingSelectors.add(selector);
                }
                return new ScalarFunctionSelector(version, partialFunction, remainingSelectors);
            }
        };
    }

    protected AbstractFunctionSelector(Kind kind, ProtocolVersion version, T fun, List<Selector> argSelectors)
    {
        super(kind);
        this.fun = fun;
        this.argSelectors = argSelectors;
        this.args = fun.newArguments(version);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;

        if (!(o instanceof AbstractFunctionSelector))
            return false;

        AbstractFunctionSelector<?> s = (AbstractFunctionSelector<?>) o;

        return Objects.equal(fun.name(), s.fun.name())
            && Objects.equal(fun.argTypes(), s.fun.argTypes())
            && Objects.equal(argSelectors, s.argSelectors);
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(fun.name(), fun.argTypes(), argSelectors);
    }

    // Sets a given arg value. We should use that instead of directly setting the args list for the
    // sake of validation.
    protected void setArg(int i, ByteBuffer value) throws InvalidRequestException
    {
        RequestValidations.checkBindValueSet(value, "Invalid unset value for argument in call to function %s", fun.name().name);
        args.set(i, value);
    }

    protected Arguments args()
    {
        return args;
    }

    public AbstractType<?> getType()
    {
        return fun.returnType();
    }

    @Override
    public String toString()
    {
        return new StrBuilder().append(fun.name())
                               .append("(")
                               .appendWithSeparators(argSelectors, ", ")
                               .append(")")
                               .toString();
    }

    @Override
    protected int serializedSize(ReadVersion version)
    {
        boolean isPartial = fun instanceof PartialScalarFunction;
        Function function = isPartial ? ((PartialScalarFunction) fun).getFunction() : fun;

        FunctionName name = function.name();
        int size =  TypeSizes.sizeof(name.keyspace) + TypeSizes.sizeof(name.name);

        List<AbstractType<?>> argTypes = function.argTypes();
        size += TypeSizes.sizeofUnsignedVInt(argTypes.size());
        for (int i = 0, m = argTypes.size(); i < m; i++)
        {
            size += sizeOf(argTypes.get(i));
        }

        size += TypeSizes.sizeof(isPartial);

        if (isPartial)
        {
            List<ByteBuffer> partialParameters = ((PartialScalarFunction) fun).getPartialArguments();

            // We use a bitset to track the position of the unresolved arguments
            size += TypeSizes.sizeofUnsignedVInt(computeBitSet(partialParameters));

            for (int i = 0, m = partialParameters.size(); i < m; i++)
            {
                ByteBuffer buffer = partialParameters.get(i);
                if (buffer != Function.UNRESOLVED)
                    size += ByteBufferUtil.serializedSizeWithVIntLength(buffer);
            }
        }

        int numberOfRemainingArguments = argSelectors.size();
        size += TypeSizes.sizeofUnsignedVInt(numberOfRemainingArguments);
        for (int i = 0; i < numberOfRemainingArguments; i++)
            size += serializers.get(version).serializedSize(argSelectors.get(i));

        return size;
    }

    @Override
    protected void serialize(DataOutputPlus out, ReadVersion version) throws IOException
    {
        boolean isPartial = fun instanceof PartialScalarFunction;
        Function function = isPartial ? ((PartialScalarFunction) fun).getFunction() : fun;

        FunctionName name = function.name();
        out.writeUTF(name.keyspace);
        out.writeUTF(name.name);

        List<AbstractType<?>> argTypes = function.argTypes();
        int numberOfArguments = argTypes.size();
        out.writeUnsignedVInt(numberOfArguments);

        for (int i = 0; i < numberOfArguments; i++)
            writeType(out, argTypes.get(i));

        out.writeBoolean(isPartial);

        if (isPartial)
        {
            List<ByteBuffer> partialParameters = ((PartialScalarFunction) fun).getPartialArguments();

            // We use a bitset to track the position of the unresolved arguments
            out.writeUnsignedVInt(computeBitSet(partialParameters));

            for (int i = 0, m = partialParameters.size(); i < m; i++)
            {
                ByteBuffer buffer = partialParameters.get(i);
                if (buffer != Function.UNRESOLVED)
                    ByteBufferUtil.writeWithVIntLength(buffer, out);
            }
        }

        int numberOfRemainingArguments = argSelectors.size();
        out.writeUnsignedVInt(numberOfRemainingArguments);
        for (int i = 0; i < numberOfRemainingArguments; i++)
            serializers.get(version).serialize(argSelectors.get(i), out);
    }

    private int computeBitSet(List<ByteBuffer> partialParameters)
    {
        int bitset = 0;
        for (int i = 0, m = partialParameters.size(); i < m; i++)
        {
            if (partialParameters.get(i) != Function.UNRESOLVED)
                bitset |= 1 << i;
        }
        return bitset;
    }
}
