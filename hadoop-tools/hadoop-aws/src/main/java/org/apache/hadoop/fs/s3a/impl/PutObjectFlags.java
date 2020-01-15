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

package org.apache.hadoop.fs.s3a.impl;

import java.util.EnumSet;

/**
 * Flags to use in put object/finished write calls.
 */
public enum PutObjectFlags {

  /** Directory Marker. */
  DirectoryMarker,

  /** Delete Parent Markers. */
  DeleteParents,

  /** Fetch object metadata for S3Guard. */
  FetchMetadata;

  /**
   * Default settings.
   */
  public static final EnumSet<PutObjectFlags> DEFAULTS =
      EnumSet.of(DeleteParents);

  /**
   * No flags.
   */
  public static final EnumSet<PutObjectFlags> NONE =
      EnumSet.noneOf(PutObjectFlags.class);

}
