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
package com.google.cloud.bigtable.dataflow;

import com.google.protobuf.InvalidProtocolBufferException;

import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.util.Base64;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * This class defines information that a Cloud Bigtable client needs to connect to a user's Cloud
 * Bigtable cluster; a table to connect to in the cluster; and a filter on the table in the form of
 * a {@link Scan}.
 */
public class CloudBigtableScanConfiguration extends CloudBigtableTableConfiguration {

  private static final long serialVersionUID = 2435897354284600685L;

  /**
   * Converts a {@link CloudBigtableOptions} object to a {@link CloudBigtableScanConfiguration}.
   * @param options The {@link CloudBigtableOptions} object.
   * @return The new {@link CloudBigtableScanConfiguration}.
   */
  public static CloudBigtableScanConfiguration fromCBTOptions(CloudBigtableOptions options) {
    return fromCBTOptions(options, new Scan());
  }

  /**
   * Converts a {@link CloudBigtableOptions} object to a {@link CloudBigtableScanConfiguration},
   * with the addition of a {@link Scan} that filters the table.
   * @param options The {@link CloudBigtableOptions} object.
   * @param scan The {@link Scan} to add to the configuration.
   * @return The new {@link CloudBigtableScanConfiguration}.
   */
  public static CloudBigtableScanConfiguration fromCBTOptions(CloudBigtableOptions options,
      Scan scan) {
    return new CloudBigtableScanConfiguration(
        options.getBigtableProjectId(),
        options.getBigtableZoneId(),
        options.getBigtableClusterId(),
        options.getBigtableTableId(),
        scan);
  }

  /**
   * Builds a {@link CloudBigtableScanConfiguration}.
   */
  public static class Builder extends CloudBigtableTableConfiguration.Builder<Builder> {
    protected Scan scan = new Scan();

    /**
     * Specifies the {@link Scan} that will be used to filter the table.
     * @param scan The {@link Scan} to add to the configuration.
     * @return The original {@link CloudBigtableScanConfiguration.Builder} for chaining convenience.
     */
    public Builder withScan(Scan scan) {
      this.scan = scan;
      return this;
    }

    /**
     * Builds the {@link CloudBigtableScanConfiguration}.
     * @return The new {@link CloudBigtableScanConfiguration}.
     */
    public CloudBigtableScanConfiguration build() {
      return new CloudBigtableScanConfiguration(projectId, zoneId, clusterId, tableId, scan);
    }
  }

  /**
   * HBase's {@link Scan} does not implement {@link Serializable}.  This class provides a wrapper
   * around {@link Scan} to properly serialize it.
   */
  private static class SerializableScan implements Serializable {
    private static final long serialVersionUID = 1998373680347356757L;
    private transient Scan scan;

    public SerializableScan(Scan scan) {
      this.scan = scan;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
      out.writeObject(Base64.encodeBytes(ProtobufUtil.toScan(scan).toByteArray()));
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      this.scan = toScan((String) in.readObject());
    }

    private static Scan toScan(String scanStr) throws IOException {
      try {
        return ProtobufUtil.toScan(ClientProtos.Scan.parseFrom(Base64.decode(scanStr)));
      } catch (InvalidProtocolBufferException ipbe) {
        throw new IOException("Could not deserialize the Scan.", ipbe);
      }
    }
  }

  private SerializableScan serializableScan;

  /**
   * Creates a {@link CloudBigtableScanConfiguration} using the specified information.
   * @param projectId The project ID for the cluster.
   * @param zone The zone where the cluster is located.
   * @param cluster The cluster ID for the cluster.
   * @param table The table to connect to in the cluster.
   * @param scan The {@link Scan} that will be used to filter the table.
   */
  public CloudBigtableScanConfiguration(String projectId, String zone, String cluster,
      String table, Scan scan) {
    super(projectId, zone, cluster, table);
    this.serializableScan = new SerializableScan(scan);
  }

  /**
   * Gets the {@link Scan} used to filter the table.
   * @return The {@link Scan}.
   */
  public Scan getScan() {
    return serializableScan.scan;
  }
}
