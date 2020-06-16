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

package org.apache.hadoop.fs.s3a.impl.statistics;

import org.apache.hadoop.fs.statistics.IOStatisticsSource;
import org.apache.hadoop.fs.statistics.impl.CounterIOStatistics;

/**
 * Base class for implementing IOStatistics sources in the S3 module.
 * A lot of the methods are very terse, because S3AInstrumentation has
 * verbose methods of similar names; the short ones always
 * refer to the inner class and not any superclass method.
 */
public abstract class AbstractS3AStatisticsSource implements
    IOStatisticsSource {

  private CounterIOStatistics ioStatistics;

  protected AbstractS3AStatisticsSource() {
  }

  @Override
  public CounterIOStatistics getIOStatistics() {
    return ioStatistics;
  }

  /**
   * Setter.
   * this must be called in the subclass constructor with
   * whatever
   * @param statistics statistics to set
   */
  protected void setIOStatistics(final CounterIOStatistics statistics) {
    this.ioStatistics = statistics;
  }

  public CounterIOStatistics getCounterStats() {
    return ioStatistics;
  }

  public long incCounter(String name) {
    return incCounter(name, 1);
  }

  public long incCounter(String name, long value) {
    return ioStatistics.incrementCounter(name, value);
  }

  public Long lookupCounterValue(final String name) {
    return ioStatistics.counters().get(name);
  }

  public Long lookupGaugeValue(final String name) {
    return 0L;
  }

  public Long getGaugeVal(final String name) {
    return ioStatistics.gauges().get(name);
  }

  public long incGauge(String name, long v) {
    return ioStatistics.incrementGauge(name, v);
  }

  public long incGauge(String name) {
    return incGauge(name, 1);
  }

}
