/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
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
package com.google.cloud.bigtable.hbase;

import static com.google.cloud.bigtable.hbase.adapters.Adapters.APPEND_ADAPTER;
import static com.google.cloud.bigtable.hbase.adapters.Adapters.BIGTABLE_RESULT_SCAN_ADAPTER;
import static com.google.cloud.bigtable.hbase.adapters.Adapters.BIGTABLE_WHILE_MATCH_RESULT_RESULT_SCAN_ADAPTER;
import static com.google.cloud.bigtable.hbase.adapters.Adapters.DELETE_ADAPTER;
import static com.google.cloud.bigtable.hbase.adapters.Adapters.GET_ADAPTER;
import static com.google.cloud.bigtable.hbase.adapters.Adapters.INCREMENT_ADAPTER;
import static com.google.cloud.bigtable.hbase.adapters.Adapters.ROW_ADAPTER;
import static com.google.cloud.bigtable.hbase.adapters.Adapters.SCAN_ADAPTER;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.AbstractBigtableConnection;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Operation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Row;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.ValueFilter;
import org.apache.hadoop.hbase.filter.WhileMatchFilter;
import org.apache.hadoop.hbase.ipc.CoprocessorRpcChannel;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.api.client.repackaged.com.google.common.annotations.VisibleForTesting;
import com.google.bigtable.v1.CheckAndMutateRowRequest;
import com.google.bigtable.v1.CheckAndMutateRowResponse;
import com.google.bigtable.v1.MutateRowRequest;
import com.google.bigtable.v1.Mutation;
import com.google.bigtable.v1.ReadModifyWriteRowRequest;
import com.google.bigtable.v1.ReadRowsRequest;
import com.google.cloud.bigtable.config.BigtableOptions;
import com.google.cloud.bigtable.config.Logger;
import com.google.cloud.bigtable.grpc.BigtableDataClient;
import com.google.cloud.bigtable.grpc.BigtableTableName;
import com.google.cloud.bigtable.hbase.adapters.Adapters;
import com.google.cloud.bigtable.hbase.adapters.DefaultReadHooks;
import com.google.cloud.bigtable.hbase.adapters.MutationAdapter;
import com.google.cloud.bigtable.hbase.adapters.PutAdapter;
import com.google.cloud.bigtable.hbase.adapters.ReadHooks;
import com.google.cloud.bigtable.hbase.adapters.ReadOperationAdapter;
import com.google.cloud.bigtable.hbase.adapters.RowMutationsAdapter;
import com.google.common.base.Function;
import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Service;
import com.google.protobuf.ServiceException;

public class BigtableTable implements Table {
  protected static final Logger LOG = new Logger(BigtableTable.class);


  // ReadHooks don't make sense from conditional mutations. If any filter attempts to make use of
  // them (which they shouldn't since we built the filter), throw an exception.
  private static final ReadHooks UNSUPPORTED_READ_HOOKS = new ReadHooks() {
    @Override
    public void composePreSendHook(Function<ReadRowsRequest, ReadRowsRequest> newHook) {
      throw new IllegalStateException(
          "We built a bad Filter for conditional mutation.");
    }

    @Override
    public ReadRowsRequest applyPreSendHook(ReadRowsRequest readRowsRequest) {
      throw new UnsupportedOperationException(
          "We built a bad Filter for conditional mutation.");
    }
  };

  protected final TableName tableName;
  protected final BigtableOptions options;
  protected final BigtableDataClient client;

  protected final PutAdapter putAdapter;
  protected final MutationAdapter mutationAdapter;
  protected final RowMutationsAdapter rowMutationsAdapter;
  protected final BatchExecutor batchExecutor;
  protected final BigtableTableName bigtableTableName;

  protected final AbstractBigtableConnection bigtableConnection;

  /**
   * Constructed by BigtableConnection
   */
  public BigtableTable(AbstractBigtableConnection bigtableConnection,
      TableName tableName,
      BigtableOptions options,
      BigtableDataClient client,
      ExecutorService executorService) {
    this.bigtableConnection = bigtableConnection;
    this.tableName = tableName;
    this.options = options;
    this.client = client;
    putAdapter = Adapters.createPutAdapter(getConfiguration());
    mutationAdapter = Adapters.createMutationsAdapter(putAdapter);
    rowMutationsAdapter = new RowMutationsAdapter(mutationAdapter);
    this.bigtableTableName = options.getClusterName().toTableName(tableName.getNameAsString());
    this.batchExecutor = new BatchExecutor(
        client,
        options,
        this.bigtableTableName,
        MoreExecutors.listeningDecorator(executorService),
        putAdapter,
        rowMutationsAdapter);
  }

  @Override
  public TableName getName() {
    return this.tableName;
  }

  @Override
  public final Configuration getConfiguration() {
    return this.bigtableConnection.getConfiguration();
  }

  @Override
  public HTableDescriptor getTableDescriptor() throws IOException {
    try (Admin admin = this.bigtableConnection.getAdmin()) {
      return admin.getTableDescriptor(tableName);
    }
  }

  @Override
  public boolean exists(Get get) throws IOException {
    LOG.trace("exists(Get)");
    // TODO: make use of strip_value() or count to hopefully return no extra data
    Result result = get(get);
    return !result.isEmpty();
  }

  @Override
  public boolean[] existsAll(List<Get> gets) throws IOException {
    LOG.trace("existsAll(Get)");
    Boolean[] existsObjects = batchExecutor.exists(gets);
    boolean[] exists = new boolean[existsObjects.length];
    for (int i = 0; i < existsObjects.length; i++) {
      exists[i] = existsObjects[i];
    }
    return exists;
  }

  @Override
  public void batch(List<? extends Row> actions, Object[] results)
      throws IOException, InterruptedException {
    LOG.trace("batch(List<>, Object[])");
    batchExecutor.batch(actions, results);
  }

  @Override
  public Object[] batch(List<? extends Row> actions) throws IOException, InterruptedException {
    LOG.trace("batch(List<>)");
    return batchExecutor.batch(actions);
  }

  @Override
  public <R> void batchCallback(List<? extends Row> actions, Object[] results,
      Batch.Callback<R> callback) throws IOException, InterruptedException {
    LOG.trace("batchCallback(List<>, Object[], Batch.Callback)");
    batchExecutor.batchCallback(actions, results, callback);
  }

  @Override
  public <R> Object[] batchCallback(List<? extends Row> actions, Batch.Callback<R> callback)
      throws IOException, InterruptedException {
    LOG.trace("batchCallback(List<>, Batch.Callback)");
    return batchExecutor.batchCallback(actions, callback);
  }

  @Override
  public Result[] get(List<Get> gets) throws IOException {
    LOG.trace("get(List<>)");
    return (Result[]) batchExecutor.batch(gets);
  }

  @Override
  public Result get(Get get) throws IOException {
    LOG.trace("get(Get)");
    try (com.google.cloud.bigtable.grpc.scanner.ResultScanner<com.google.bigtable.v1.Row> scanner =
        createBigtableScanner(GET_ADAPTER, get)) {
      return ROW_ADAPTER.adaptResponse(scanner.next());
    } catch (Throwable throwable) {
      LOG.error("Encountered exception when executing get.", throwable);
      throw new IOException(
          makeGenericExceptionMessage(
              "get",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              get.getRow()),
          throwable);
    }
  }

  @Override
  public ResultScanner getScanner(Scan scan) throws IOException {
    try {
      LOG.trace("getScanner(Scan)");
      com.google.cloud.bigtable.grpc.scanner.ResultScanner<com.google.bigtable.v1.Row> scanner =
          createBigtableScanner(SCAN_ADAPTER, scan);
      if (hasWhileMatchFilter(scan.getFilter())) {
        return BIGTABLE_WHILE_MATCH_RESULT_RESULT_SCAN_ADAPTER.adapt(scanner);
      }
      return BIGTABLE_RESULT_SCAN_ADAPTER.adapt(scanner);
    } catch (Throwable throwable) {
      LOG.error("Encountered exception when executing getScanner.", throwable);
      throw new IOException(
          makeGenericExceptionMessage(
              "getScanner",
              options.getProjectId(),
              tableName.getQualifierAsString()),
          throwable);
    }
  }

  @VisibleForTesting
  static boolean hasWhileMatchFilter(Filter filter) {
    if (filter instanceof WhileMatchFilter) {
      return true;
    }
 
    if (filter instanceof FilterList) {
      FilterList list = (FilterList) filter;
      for (Filter subFilter : list.getFilters()) {
        if (hasWhileMatchFilter(subFilter)) {
          return true;
        }
      }
    }

    return false;
  }

  private <T extends Operation>
      com.google.cloud.bigtable.grpc.scanner.ResultScanner<com.google.bigtable.v1.Row>
      createBigtableScanner(ReadOperationAdapter<T> adapter, T operation) {
    ReadHooks readHooks = new DefaultReadHooks();
    ReadRowsRequest.Builder request = adapter.adapt(operation, readHooks)
         .setTableName(bigtableTableName.toString());
    ReadRowsRequest finalRequest = readHooks.applyPreSendHook(request.build());
    return client.readRows(finalRequest);
  }

  @Override
  public ResultScanner getScanner(byte[] family) throws IOException {
    LOG.trace("getScanner(byte[])");
    return getScanner(new Scan().addFamily(family));
  }

  @Override
  public ResultScanner getScanner(byte[] family, byte[] qualifier) throws IOException {
    LOG.trace("getScanner(byte[], byte[])");
    return getScanner(new Scan().addColumn(family, qualifier));
  }

  @Override
  public void put(Put put) throws IOException {
    LOG.trace("put(Put)");
    MutateRowRequest.Builder rowMutationBuilder = putAdapter.adapt(put);
    rowMutationBuilder.setTableName(bigtableTableName.toString());

    try {
      client.mutateRow(rowMutationBuilder.build());
    } catch (Throwable throwable) {
      LOG.error("Encountered ServiceException when executing put.", throwable);
      throw new IOException(
          makeGenericExceptionMessage(
              "put",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              rowMutationBuilder.getRowKey().toByteArray()),
          throwable);
    }
  }

  @Override
  public void put(List<Put> puts) throws IOException {
    LOG.trace("put(List<Put>)");
    batchExecutor.batch(puts);
  }

  @Override
  public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier, byte[] value, Put put)
      throws IOException {
    return checkAndPut(row, family, qualifier, CompareFilter.CompareOp.EQUAL, value, put);
  }

  @Override
  public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier,
      CompareFilter.CompareOp compareOp, byte[] value, Put put) throws IOException {

    CheckAndMutateRowRequest.Builder requestBuilder =
        makeConditionalMutationRequestBuilder(
            row,
            family,
            qualifier,
            compareOp,
            value,
            put.getRow(),
            putAdapter.adapt(put).getMutationsList());

    try {
      CheckAndMutateRowResponse response =
          client.checkAndMutateRow(requestBuilder.build());
      return wasMutationApplied(requestBuilder, response);
    } catch (Throwable throwable) {
      throw new IOException(
          makeGenericExceptionMessage(
              "checkAndPut",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              row),
          throwable);
    }
  }

  @Override
  public void delete(Delete delete) throws IOException {
    LOG.trace("delete(Delete)");
    MutateRowRequest.Builder requestBuilder = DELETE_ADAPTER.adapt(delete);
    requestBuilder.setTableName(bigtableTableName.toString());

    try {
      client.mutateRow(requestBuilder.build());
    } catch (Throwable throwable) {
      LOG.error("Encountered ServiceException when executing delete.", throwable);
      throw new IOException(
          makeGenericExceptionMessage(
              "delete",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              requestBuilder.getRowKey().toByteArray()),
          throwable);
    }
  }

  @Override
  public void delete(List<Delete> deletes) throws IOException {
    LOG.trace("delete(List<Delete>)");
    batchExecutor.batch(deletes);
  }

  @Override
  public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier, byte[] value,
      Delete delete) throws IOException {
    return checkAndDelete(row, family, qualifier, CompareFilter.CompareOp.EQUAL, value, delete);
  }

  @Override
  public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier,
    CompareFilter.CompareOp compareOp, byte[] value, Delete delete) throws IOException {

    CheckAndMutateRowRequest.Builder requestBuilder =
        makeConditionalMutationRequestBuilder(
            row,
            family,
            qualifier,
            compareOp,
            value,
            delete.getRow(),
            DELETE_ADAPTER.adapt(delete).getMutationsList());

    try {
      CheckAndMutateRowResponse response =
          client.checkAndMutateRow(requestBuilder.build());
      return wasMutationApplied(requestBuilder, response);
    } catch (Throwable throwable) {
      throw new IOException(
          makeGenericExceptionMessage(
              "checkAndDelete",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              row),
          throwable);
    }
  }

  @Override
  public boolean checkAndMutate(
      final byte [] row, final byte [] family, final byte [] qualifier,
      final CompareFilter.CompareOp compareOp, final byte [] value, final RowMutations rm)
      throws IOException {
    List<Mutation> adaptedMutations = new ArrayList<>();
    for (org.apache.hadoop.hbase.client.Mutation mut : rm.getMutations()) {
      adaptedMutations.addAll(mutationAdapter.adapt(mut).getMutationsList());
    }

    CheckAndMutateRowRequest.Builder requestBuilder =
        makeConditionalMutationRequestBuilder(
            row,
            family,
            qualifier,
            compareOp,
            value,
            rm.getRow(),
            adaptedMutations);

    try {
      CheckAndMutateRowResponse response =
          client.checkAndMutateRow(requestBuilder.build());
      return wasMutationApplied(requestBuilder, response);
    } catch (Throwable throwable) {
      throw new IOException(
          makeGenericExceptionMessage(
              "checkAndMutate",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              row),
          throwable);
    }
  }

  @Override
  public void mutateRow(RowMutations rm) throws IOException {
    LOG.trace("mutateRow(RowMutation)");
    MutateRowRequest.Builder requestBuilder = rowMutationsAdapter.adapt(rm);
    requestBuilder.setTableName(bigtableTableName.toString());
    try {
      client.mutateRow(requestBuilder.build());
    } catch (Throwable throwable) {
      throw new IOException(
          makeGenericExceptionMessage(
              "mutateRow",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              rm.getRow()),
          throwable);
    }
  }

  @Override
  public Result append(Append append) throws IOException {
    LOG.trace("append(Append)");

    ReadModifyWriteRowRequest.Builder appendRowRequest = APPEND_ADAPTER.adapt(append);
    appendRowRequest.setTableName(bigtableTableName.toString());
    try {
      com.google.bigtable.v1.Row response =
          client.readModifyWriteRow(appendRowRequest.build());
      // The bigtable API will always return the mutated results. In order to maintain
      // compatibility, simply return null when results were not requested.
      if (append.isReturnResults()) {
        return ROW_ADAPTER.adaptResponse(response);
      } else {
        return null;
      }
    } catch (Throwable throwable) {
      LOG.error("Encountered Exception when executing append.", throwable);
      throw new IOException(
          makeGenericExceptionMessage(
              "append",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              append.getRow()),
          throwable);
    }
  }

  @Override
  public Result increment(Increment increment) throws IOException {
    LOG.trace("increment(Increment)");
    ReadModifyWriteRowRequest.Builder incrementRowRequest =
        INCREMENT_ADAPTER.adapt(increment);
    incrementRowRequest.setTableName(bigtableTableName.toString());

    try {
      com.google.bigtable.v1.Row response = client.readModifyWriteRow(incrementRowRequest.build());
      return ROW_ADAPTER.adaptResponse(response);
    } catch (Throwable e) {
      LOG.error("Encountered RuntimeException when executing increment.", e);
      throw new IOException(
          makeGenericExceptionMessage(
              "increment",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              increment.getRow()),
          e);
    }
  }

  @Override
  public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount)
      throws IOException {
    LOG.trace("incrementColumnValue(byte[], byte[], byte[], long)");
    Increment incr = new Increment(row);
    incr.addColumn(family, qualifier, amount);
    Result result = increment(incr);

    Cell cell = result.getColumnLatestCell(family, qualifier);
    if (cell == null) {
      LOG.error("Failed to find a incremented value in result of increment");
      throw new IOException(
          makeGenericExceptionMessage(
              "increment",
              options.getProjectId(),
              tableName.getQualifierAsString(),
              row));
    }
    return Bytes.toLong(CellUtil.cloneValue(cell));
  }

  @Override
  public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount,
      Durability durability) throws IOException {
    LOG.trace("incrementColumnValue(byte[], byte[], byte[], long, Durability)");
    return incrementColumnValue(row, family, qualifier, amount);
  }

  @Override
  public void close() throws IOException {
    // TODO: shutdown the executor.
  }

  @Override
  public CoprocessorRpcChannel coprocessorService(byte[] row) {
    LOG.error("Unsupported coprocessorService(byte[]) called.");
    throw new UnsupportedOperationException();  // TODO
  }

  @Override
  public <T extends Service, R> Map<byte[], R> coprocessorService(Class<T> service, byte[] startKey,
      byte[] endKey, Batch.Call<T, R> callable) throws ServiceException, Throwable {
    LOG.error("Unsupported coprocessorService(Class, byte[], byte[], Batch.Call) called.");
    throw new UnsupportedOperationException();  // TODO
  }

  @Override
  public <T extends Service, R> void coprocessorService(Class<T> service, byte[] startKey,
      byte[] endKey, Batch.Call<T, R> callable, Batch.Callback<R> callback)
      throws ServiceException, Throwable {
    LOG.error("Unsupported coprocessorService("
        + "Class, byte[], byte[], Batch.Call, Batch.Callback) called.");
    throw new UnsupportedOperationException();  // TODO
  }

  @Override
  public long getWriteBufferSize() {
    LOG.error("Unsupported getWriteBufferSize() called");
    throw new UnsupportedOperationException();  // TODO
  }

  @Override
  public void setWriteBufferSize(long writeBufferSize) throws IOException {
    LOG.error("Unsupported getWriteBufferSize() called");
    throw new UnsupportedOperationException();  // TODO
  }

  @Override
  public <R extends Message> Map<byte[], R> batchCoprocessorService(
      Descriptors.MethodDescriptor methodDescriptor, Message message, byte[] bytes, byte[] bytes2,
      R r) throws ServiceException, Throwable {
    LOG.error("Unsupported batchCoprocessorService("
        + "MethodDescriptor, Message, byte[], byte[], R) called.");
    throw new UnsupportedOperationException();  // TODO
  }

  @Override
  public <R extends Message> void batchCoprocessorService(
      Descriptors.MethodDescriptor methodDescriptor, Message message, byte[] bytes, byte[] bytes2,
      R r, Batch.Callback<R> rCallback) throws ServiceException, Throwable {
    LOG.error("Unsupported batchCoprocessorService("
        + "MethodDescriptor, Message, byte[], byte[], R, Batch.Callback<R>) called.");
    throw new UnsupportedOperationException();  // TODO
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(BigtableTable.class)
        .add("hashCode", "0x" + Integer.toHexString(hashCode()))
        .add("project", options.getProjectId())
        .add("zone", options.getZoneId())
        .add("cluster", options.getClusterId())
        .add("table", tableName.getNameAsString())
        .add("host", options.getDataHost())
        .toString();
  }

  protected boolean wasMutationApplied(
      CheckAndMutateRowRequest.Builder requestBuilder,
      CheckAndMutateRowResponse response) {

    // If we have true mods, we want the predicate to have matched.
    // If we have false mods, we did not want the predicate to have matched.
    return (requestBuilder.getTrueMutationsCount() > 0
        && response.getPredicateMatched())
        || (requestBuilder.getFalseMutationsCount() > 0
        && !response.getPredicateMatched());
  }

  protected CheckAndMutateRowRequest.Builder makeConditionalMutationRequestBuilder(
      byte[] row,
      byte[] family,
      byte[] qualifier,
      CompareFilter.CompareOp compareOp,
      byte[] value,
      byte[] actionRow,
      List<com.google.bigtable.v1.Mutation> mutations) throws IOException {

    if (!Arrays.equals(actionRow, row)) {
      // The following odd exception message is for compatibility with HBase.
      throw new DoNotRetryIOException("Action's getRow must match the passed row");
    }

    CheckAndMutateRowRequest.Builder requestBuilder =
        CheckAndMutateRowRequest.newBuilder();

    requestBuilder.setTableName(bigtableTableName.toString());

    requestBuilder.setRowKey(ByteString.copyFrom(row));
    Scan scan = new Scan().addColumn(family, qualifier);
    scan.setMaxVersions(1);
    if (value == null) {
      // If we don't have a value and we are doing CompareOp.EQUAL, we want to mutate if there
      // is no cell with the qualifier. If we are doing CompareOp.NOT_EQUAL, we want to mutate
      // if there is any cell. We don't actually want an extra filter for either of these cases,
      // but we do need to invert the compare op.
      if (CompareFilter.CompareOp.EQUAL.equals(compareOp)) {
        requestBuilder.addAllFalseMutations(mutations);
      } else if (CompareFilter.CompareOp.NOT_EQUAL.equals(compareOp)) {
        requestBuilder.addAllTrueMutations(mutations);
      }
    } else {
      ValueFilter valueFilter =
          new ValueFilter(compareOp, new BinaryComparator(value));
      scan.setFilter(valueFilter);
      requestBuilder.addAllTrueMutations(mutations);
    }
    requestBuilder.setPredicateFilter(SCAN_ADAPTER.buildFilter(scan, UNSUPPORTED_READ_HOOKS));
    return requestBuilder;
  }

  static String makeGenericExceptionMessage(String operation, String projectId, String tableName) {
    return String.format(
        "Failed to perform operation. Operation='%s', projectId='%s', tableName='%s'",
        operation,
        projectId,
        tableName);
  }

  static String makeGenericExceptionMessage(
      String operation,
      String projectId,
      String tableName,
      byte[] rowKey) {
    return String.format(
        "Failed to perform operation. Operation='%s', projectId='%s', tableName='%s', rowKey='%s'",
        operation,
        projectId,
        tableName,
        Bytes.toStringBinary(rowKey));
  }
}
