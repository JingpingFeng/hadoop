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

package org.apache.hadoop.fs.statistics.impl;

import org.apache.hadoop.fs.statistics.IOStatistics;

/**
 * Interface an IOStatistics source where all the counters
 * are implemented as a static set of counters.
 * <p></p>
 * Thread safe.
 */
public interface CounterIOStatistics extends IOStatistics {

  /**
   * Increment the counter.
   * No-op if the counter is unknown.
   * @param value incremental value.
   */
  void increment(String key, long value);

  /**
   * Set the counter.
   * No-op if the counter is unknown.
   * @param value new value.
   */
  void set(String key, long value);

  /**
   * Reset all counters.
   * Unsynchronized.
   */
  void resetCounters();
}
