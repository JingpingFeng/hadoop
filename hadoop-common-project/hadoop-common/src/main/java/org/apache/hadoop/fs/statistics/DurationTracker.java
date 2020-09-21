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

package org.apache.hadoop.fs.statistics;

/**
 * Interface to be implemented by objects which can track duration.
 * It extends AutoCloseable to fit into a try-with-resources statement,
 * but then strips out the {@code throws Exception} aspect of the signature
 * so it doesn't force code to add extra handling for any failures.
 */
public interface DurationTracker extends AutoCloseable {

  /**
   * Finish tracking: update the statistics with the timings.
   */
  void close();
}
