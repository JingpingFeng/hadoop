/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a;

import org.apache.hadoop.conf.Configuration;

/**
 * Testing S3A client-side encryption/decryption with SYMMETRIC AES
 * with the block output stream.
 */
public class ITestS3AEncryptionCSESymmetricBlockOutputStream
        extends ITestS3AEncryptionCSESymmetric {

  @Override
  protected Configuration createConfiguration() {
    Configuration conf = super.createConfiguration();
    S3ATestUtils.disableFilesystemCaching(conf);
    conf.set(Constants.CLIENT_SIDE_ENCRYPTION_METHOD,
            S3AClientEncryptionMethods.CUSTOM.getMethod());
    conf.setClass(Constants.CLIENT_SIDE_ENCRYPTION_MATERIALS_PROVIDER,
            SymmetricKeyConfig.class, S3ACSEMaterialProviderConfig.class);
    conf.setBoolean(Constants.FAST_UPLOAD, true);
    conf.set(Constants.FAST_UPLOAD_BUFFER,
            Constants.FAST_UPLOAD_BYTEBUFFER);
    return conf;
  }
}
