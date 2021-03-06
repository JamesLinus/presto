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
package com.facebook.presto.hive;

import com.facebook.presto.hive.metastore.SemiTransactionalHiveMetastore;
import com.facebook.presto.hive.metastore.Table;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.predicate.ValueSet;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.airlift.slice.Slice;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.metastore.ProtectMode;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.hive.HiveBucketing.HiveBucket;
import static com.facebook.presto.hive.HiveBucketing.getHiveBucketHandle;
import static com.facebook.presto.hive.HiveBucketing.getHiveBucketNumbers;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_EXCEEDED_PARTITION_LIMIT;
import static com.facebook.presto.hive.HiveUtil.getPartitionKeyColumnHandles;
import static com.facebook.presto.hive.HiveUtil.parsePartitionValue;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.metastore.ProtectMode.getProtectModeFromString;

public class HivePartitionManager
{
    public static final String PRESTO_OFFLINE = "presto_offline";
    private static final String PARTITION_VALUE_WILDCARD = "";

    private final String connectorId;
    private final DateTimeZone timeZone;
    private final boolean assumeCanonicalPartitionKeys;
    private final int maxPartitions;
    private final int domainCompactionThreshold;
    private final TypeManager typeManager;

    @Inject
    public HivePartitionManager(
            HiveConnectorId connectorId,
            TypeManager typeManager,
            HiveClientConfig hiveClientConfig)
    {
        this(connectorId,
                typeManager,
                hiveClientConfig.getDateTimeZone(),
                hiveClientConfig.isAssumeCanonicalPartitionKeys(),
                hiveClientConfig.getMaxPartitionsPerScan(),
                hiveClientConfig.getDomainCompactionThreshold());
    }

    public HivePartitionManager(
            HiveConnectorId connectorId,
            TypeManager typeManager,
            DateTimeZone timeZone,
            boolean assumeCanonicalPartitionKeys,
            int maxPartitions,
            int domainCompactionThreshold)
    {
        this.connectorId = requireNonNull(connectorId, "connectorId is null").toString();
        this.timeZone = requireNonNull(timeZone, "timeZone is null");
        this.assumeCanonicalPartitionKeys = assumeCanonicalPartitionKeys;
        checkArgument(maxPartitions >= 1, "maxPartitions must be at least 1");
        this.maxPartitions = maxPartitions;
        checkArgument(domainCompactionThreshold >= 1, "domainCompactionThreshold must be at least 1");
        this.domainCompactionThreshold = domainCompactionThreshold;
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    public HivePartitionResult getPartitions(SemiTransactionalHiveMetastore metastore, ConnectorTableHandle tableHandle, Constraint<ColumnHandle> constraint)
    {
        HiveTableHandle hiveTableHandle = (HiveTableHandle) tableHandle;
        TupleDomain<ColumnHandle> effectivePredicate = constraint.getSummary();

        SchemaTableName tableName = hiveTableHandle.getSchemaTableName();
        Table table = getTable(metastore, tableName);
        Optional<HiveBucketHandle> hiveBucketHandle = getHiveBucketHandle(connectorId, table);

        List<HiveColumnHandle> partitionColumns = getPartitionKeyColumnHandles(connectorId, table);
        List<HiveBucket> buckets = getHiveBucketNumbers(table, effectivePredicate);
        TupleDomain<HiveColumnHandle> compactEffectivePredicate = toCompactTupleDomain(effectivePredicate, domainCompactionThreshold);

        if (effectivePredicate.isNone()) {
            return new HivePartitionResult(partitionColumns, ImmutableList.of(), TupleDomain.none(), TupleDomain.none(), hiveBucketHandle);
        }

        if (partitionColumns.isEmpty()) {
            return new HivePartitionResult(
                    partitionColumns,
                    ImmutableList.of(new HivePartition(tableName, compactEffectivePredicate, buckets)),
                    effectivePredicate,
                    TupleDomain.none(),
                    hiveBucketHandle);
        }

        List<Type> partitionTypes = partitionColumns.stream()
                .map(column -> typeManager.getType(column.getTypeSignature()))
                .collect(toList());

        List<String> partitionNames = getFilteredPartitionNames(metastore, tableName, partitionColumns, effectivePredicate);

        // do a final pass to filter based on fields that could not be used to filter the partitions
        int partitionCount = 0;
        ImmutableList.Builder<HivePartition> partitions = ImmutableList.builder();
        for (String partitionName : partitionNames) {
            Optional<Map<ColumnHandle, NullableValue>> values = parseValuesAndFilterPartition(partitionName, partitionColumns, partitionTypes, constraint);

            if (values.isPresent()) {
                if (partitionCount == maxPartitions) {
                    throw new PrestoException(HIVE_EXCEEDED_PARTITION_LIMIT, format(
                            "Query over table '%s' can potentially read more than %s partitions",
                            hiveTableHandle.getSchemaTableName(),
                            maxPartitions));
                }
                partitionCount++;
                partitions.add(new HivePartition(tableName, compactEffectivePredicate, partitionName, values.get(), buckets));
            }
        }

        // All partition key domains will be fully evaluated, so we don't need to include those
        TupleDomain<ColumnHandle> remainingTupleDomain = TupleDomain.withColumnDomains(Maps.filterKeys(effectivePredicate.getDomains().get(), not(Predicates.in(partitionColumns))));
        TupleDomain<ColumnHandle> enforcedTupleDomain = TupleDomain.withColumnDomains(Maps.filterKeys(effectivePredicate.getDomains().get(), Predicates.in(partitionColumns)));
        return new HivePartitionResult(partitionColumns, partitions.build(), remainingTupleDomain, enforcedTupleDomain, hiveBucketHandle);
    }

    private static TupleDomain<HiveColumnHandle> toCompactTupleDomain(TupleDomain<ColumnHandle> effectivePredicate, int threshold)
    {
        checkArgument(effectivePredicate.getDomains().isPresent());

        ImmutableMap.Builder<HiveColumnHandle, Domain> builder = ImmutableMap.builder();
        for (Map.Entry<ColumnHandle, Domain> entry : effectivePredicate.getDomains().get().entrySet()) {
            HiveColumnHandle hiveColumnHandle = (HiveColumnHandle) entry.getKey();

            ValueSet values = entry.getValue().getValues();
            ValueSet compactValueSet = values.getValuesProcessor().<Optional<ValueSet>>transform(
                    ranges -> ranges.getRangeCount() > threshold ? Optional.of(ValueSet.ofRanges(ranges.getSpan())) : Optional.empty(),
                    discreteValues -> discreteValues.getValues().size() > threshold ? Optional.of(ValueSet.all(values.getType())) : Optional.empty(),
                    allOrNone -> Optional.empty())
                    .orElse(values);
            builder.put(hiveColumnHandle, Domain.create(compactValueSet, entry.getValue().isNullAllowed()));
        }
        return TupleDomain.withColumnDomains(builder.build());
    }

    private Optional<Map<ColumnHandle, NullableValue>> parseValuesAndFilterPartition(String partitionName, List<HiveColumnHandle> partitionColumns, List<Type> partitionTypes, Constraint<ColumnHandle> constraint)
    {
        List<String> partitionValues = extractPartitionKeyValues(partitionName);

        Map<ColumnHandle, Domain> domains = constraint.getSummary().getDomains().get();
        ImmutableMap.Builder<ColumnHandle, NullableValue> builder = ImmutableMap.builder();
        for (int i = 0; i < partitionColumns.size(); i++) {
            HiveColumnHandle column = partitionColumns.get(i);
            NullableValue parsedValue = parsePartitionValue(partitionName, partitionValues.get(i), partitionTypes.get(i), timeZone);

            Domain allowedDomain = domains.get(column);
            if (allowedDomain != null && !allowedDomain.includesNullableValue(parsedValue.getValue())) {
                return Optional.empty();
            }
            builder.put(column, parsedValue);
        }
        Map<ColumnHandle, NullableValue> values = builder.build();

        if (!constraint.predicate().test(values)) {
            return Optional.empty();
        }

        return Optional.of(values);
    }

    private Table getTable(SemiTransactionalHiveMetastore metastore, SchemaTableName tableName)
    {
        Optional<Table> target = metastore.getTable(tableName.getSchemaName(), tableName.getTableName());
        if (!target.isPresent()) {
            throw new TableNotFoundException(tableName);
        }
        Table table = target.get();

        String protectMode = table.getParameters().get(ProtectMode.PARAMETER_NAME);
        if (protectMode != null && getProtectModeFromString(protectMode).offline) {
            throw new TableOfflineException(tableName, false, null);
        }

        String prestoOffline = table.getParameters().get(PRESTO_OFFLINE);
        if (!isNullOrEmpty(prestoOffline)) {
            throw new TableOfflineException(tableName, true, prestoOffline);
        }

        return table;
    }

    private List<String> getFilteredPartitionNames(SemiTransactionalHiveMetastore metastore, SchemaTableName tableName, List<HiveColumnHandle> partitionKeys, TupleDomain<ColumnHandle> effectivePredicate)
    {
        checkArgument(effectivePredicate.getDomains().isPresent());

        List<String> filter = new ArrayList<>();
        for (HiveColumnHandle partitionKey : partitionKeys) {
            Domain domain = effectivePredicate.getDomains().get().get(partitionKey);
            if (domain != null && domain.isNullableSingleValue()) {
                Object value = domain.getNullableSingleValue();
                if (value == null) {
                    filter.add(HivePartitionKey.HIVE_DEFAULT_DYNAMIC_PARTITION);
                }
                else if (value instanceof Slice) {
                    filter.add(((Slice) value).toStringUtf8());
                }
                else if ((value instanceof Boolean) || (value instanceof Double) || (value instanceof Long)) {
                    if (assumeCanonicalPartitionKeys) {
                        filter.add(value.toString());
                    }
                    else {
                        // Hive treats '0', 'false', and 'False' the same. However, the metastore differentiates between these.
                        filter.add(PARTITION_VALUE_WILDCARD);
                    }
                }
                else {
                    throw new PrestoException(NOT_SUPPORTED, "Only Boolean, Double and Long partition keys are supported");
                }
            }
            else {
                filter.add(PARTITION_VALUE_WILDCARD);
            }
        }

        // fetch the partition names
        return metastore.getPartitionNamesByParts(tableName.getSchemaName(), tableName.getTableName(), filter)
                .orElseThrow(() -> new TableNotFoundException(tableName));
    }

    public static List<String> extractPartitionKeyValues(String partitionName)
    {
        ImmutableList.Builder<String> values = ImmutableList.builder();

        boolean inKey = true;
        int valueStart = -1;
        for (int i = 0; i < partitionName.length(); i++) {
            char current = partitionName.charAt(i);
            if (inKey) {
                checkArgument(current != '/', "Invalid partition spec: %s", partitionName);
                if (current == '=') {
                    inKey = false;
                    valueStart = i + 1;
                }
            }
            else if (current == '/') {
                checkArgument(valueStart != -1, "Invalid partition spec: %s", partitionName);
                values.add(FileUtils.unescapePathName(partitionName.substring(valueStart, i)));
                inKey = true;
                valueStart = -1;
            }
        }
        checkArgument(!inKey, "Invalid partition spec: %s", partitionName);
        values.add(FileUtils.unescapePathName(partitionName.substring(valueStart, partitionName.length())));

        return values.build();
    }
}
