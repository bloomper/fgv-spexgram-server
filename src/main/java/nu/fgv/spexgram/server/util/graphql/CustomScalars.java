/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexgram.server.util.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The {@code Instant} and {@code Void} scalars, as in Spexregister.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class CustomScalars {

    public static final GraphQLScalarType INSTANT = GraphQLScalarType.newScalar()
            .name("Instant")
            .description("An instant in UTC, ISO 8601")
            .coercing(new Coercing<java.time.Instant, String>() {
                @Override
                public String serialize(final Object dataFetcherResult, final GraphQLContext graphQLContext, final Locale locale) {
                    if (dataFetcherResult instanceof final java.time.Instant instant) {
                        return DateTimeFormatter.ISO_INSTANT.format(instant);
                    }
                    throw new CoercingSerializeException("Expected an Instant");
                }

                @Override
                public java.time.Instant parseValue(final Object input, final GraphQLContext graphQLContext, final Locale locale) {
                    try {
                        return java.time.Instant.parse((String) input);
                    } catch (final ClassCastException | DateTimeParseException e) {
                        throw new CoercingParseValueException("Invalid input", e);
                    }
                }

                @Override
                public java.time.Instant parseLiteral(final Value<?> input, final CoercedVariables variables, final GraphQLContext graphQLContext, final Locale locale) {
                    try {
                        return java.time.Instant.parse(((StringValue) input).getValue());
                    } catch (final ClassCastException | DateTimeParseException e) {
                        throw new CoercingParseLiteralException("Invalid input", e);
                    }
                }
            })
            .build();

    public static final GraphQLScalarType VOID = GraphQLScalarType.newScalar()
            .name("Void")
            .description("Represents the null value")
            .coercing(new Coercing<Void, Void>() {
                @Override
                public Void serialize(final Object dataFetcherResult, final GraphQLContext graphQLContext, final Locale locale) {
                    return null;
                }

                @Override
                public Void parseValue(final Object input, final GraphQLContext graphQLContext, final Locale locale) {
                    return null;
                }

                @Override
                public Void parseLiteral(final Value<?> input, final CoercedVariables variables, final GraphQLContext graphQLContext, final Locale locale) {
                    return null;
                }
            })
            .build();
}
