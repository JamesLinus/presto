/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator;

import com.facebook.presto.Session;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.sql.gen.JoinFilterFunctionCompiler.JoinFilterFunctionFactory;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.facebook.presto.SystemSessionProperties.isFastInequalityJoin;
import static com.facebook.presto.operator.SyntheticAddress.decodePosition;
import static com.facebook.presto.operator.SyntheticAddress.decodeSliceIndex;
import static java.util.Objects.requireNonNull;

public class JoinHashSupplier
        implements LookupSourceSupplier
{
    private final Session session;
    private final PagesHash pagesHash;
    private final LongArrayList addresses;
    private final List<List<Block>> channels;
    private final Function<Optional<JoinFilterFunction>, PositionLinks> positionLinks;
    private final Optional<JoinFilterFunctionFactory> filterFunctionFactory;

    public JoinHashSupplier(
            Session session,
            PagesHashStrategy pagesHashStrategy,
            LongArrayList addresses,
            List<List<Block>> channels,
            Optional<JoinFilterFunctionFactory> filterFunctionFactory)
    {
        this.session = requireNonNull(session, "session is null");
        this.addresses = requireNonNull(addresses, "addresses is null");
        this.channels = requireNonNull(channels, "channels is null");
        this.filterFunctionFactory = requireNonNull(filterFunctionFactory, "filterFunctionFactory is null");
        requireNonNull(pagesHashStrategy, "pagesHashStrategy is null");

        PositionLinks.Builder positionLinksBuilder;
        if (filterFunctionFactory.isPresent() &&
                filterFunctionFactory.get().getSortChannel().isPresent() &&
                isFastInequalityJoin(session)) {
            positionLinksBuilder = SortedPositionLinks.builder(
                    addresses.size(),
                    new PositionComparator(pagesHashStrategy, addresses));
        }
        else {
            positionLinksBuilder = ArrayPositionLinks.builder(addresses.size());
        }

        this.pagesHash = new PagesHash(addresses, pagesHashStrategy, positionLinksBuilder);
        this.positionLinks = positionLinksBuilder.build();
    }

    @Override
    public long getHashCollisions()
    {
        return pagesHash.getHashCollisions();
    }

    @Override
    public double getExpectedHashCollisions()
    {
        return pagesHash.getExpectedHashCollisions();
    }

    @Override
    public JoinHash get()
    {
        // We need to create new JoinFilterFunction per each thread using it, since those functions
        // are not thread safe...
        Optional<JoinFilterFunction> filterFunction =
                filterFunctionFactory.map(factory -> factory.create(session.toConnectorSession(), addresses, channels));
        return new JoinHash(
                pagesHash,
                filterFunction,
                positionLinks.apply(filterFunction));
    }

    public static class PositionComparator
            implements IntComparator
    {
        private final PagesHashStrategy pagesHashStrategy;
        private final LongArrayList addresses;

        public PositionComparator(PagesHashStrategy pagesHashStrategy, LongArrayList addresses)
        {
            this.pagesHashStrategy = pagesHashStrategy;
            this.addresses = addresses;
        }

        @Override
        public int compare(int leftPosition, int rightPosition)
        {
            long leftPageAddress = addresses.getLong(leftPosition);
            int leftBlockIndex = decodeSliceIndex(leftPageAddress);
            int leftBlockPosition = decodePosition(leftPageAddress);

            long rightPageAddress = addresses.getLong(rightPosition);
            int rightBlockIndex = decodeSliceIndex(rightPageAddress);
            int rightBlockPosition = decodePosition(rightPageAddress);

            return pagesHashStrategy.compare(leftBlockIndex, leftBlockPosition, rightBlockIndex, rightBlockPosition);
        }

        @Override
        public int compare(Integer leftPosition, Integer rightPosition)
        {
            return compare(leftPosition.intValue(), rightPosition.intValue());
        }
    }
}
