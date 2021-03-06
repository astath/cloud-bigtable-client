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

import com.google.cloud.dataflow.sdk.coders.AtomicCoder;
import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.coders.CoderException;
import com.google.cloud.dataflow.sdk.io.Source;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * A {@link Coder} that serializes {@link Result} objects used by the Cloud Bigtable {@link Source}.
 */
public class HBaseResultCoder extends AtomicCoder<Result> implements Serializable{

  private static final long serialVersionUID = -4975428837770254686L;

  @Override
  public Result decode(InputStream inputStream, Coder.Context context)
      throws CoderException, IOException {
    return ProtobufUtil.toResult(ClientProtos.Result.parseDelimitedFrom(inputStream));
  }

  @Override
  public void encode(Result value, OutputStream outputStream,
      Coder.Context context) throws CoderException, IOException {
    ProtobufUtil.toResult(value).writeDelimitedTo(outputStream);
  }
}
