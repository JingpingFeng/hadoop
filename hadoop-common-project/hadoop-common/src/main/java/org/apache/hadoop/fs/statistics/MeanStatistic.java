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

import java.io.Serializable;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A mean statistic represented as the sum and the sample count;
 * the mean is calculated on demand.
 * <p></p>
 * It can be used to accrue values so as to dynamically update
 * the mean. If so, know that there is no synchronization
 * on the methods.
 * <p></p>
 * If a statistic has 0 samples then it is considered to be empty.
 * <p></p>
 * All 'empty' statistics are equivalent, independent of the sum value.
 * <p></p>
 * For non-empty statistics, sum and sample values must match
 * for equality.
 * <p></p>
 * It is serializable and annotated for correct serializations with jackson2.
 * <p></p>
 * Thread safety. The operations to add/copy sample data, are thread safe.
 * <ol>
 *   <li>{@link #add(MeanStatistic)}</li>
 *   <li>{@link #addSample(long)} </li>
 *   <li>{@link #clear()} </li>
 *   <li>{@link #setSamplesAndSum(long, long)}</li>
 *   <li>{@link #set(MeanStatistic)}</li>
 * </ol>
 * So is the {@link #mean()} method. This ensures that when
 * used to aggregated statistics, the aggregate value and sample
 * count are set and evaluated consistently.
 * No other operations are synchronized.
 */
public final class MeanStatistic implements Serializable, Cloneable {

  private static final long serialVersionUID = 567888327998615425L;

  /**
   * Number of samples used to calculate
   * the mean.
   */
  private long samples;

  /**
   * sum of the values.
   */
  private long sum;

  /**
   * Constructor, with some resilience against invalid sample counts.
   * If the sample count is 0 or less, the sum is set to 0 and
   * the sample count to 0.
   * @param samples sample count.
   * @param sum sum value
   */
  public MeanStatistic(final long samples, final long sum) {
    if (samples > 0) {
      this.sum = sum;
      this.samples = samples;
    }
  }

  /**
   * Create from another statistic.
   * @param that source
   */
  public MeanStatistic(MeanStatistic that) {
    this(that.samples, that.sum);
  }

  /**
   * Create an empty statistic.
   */
  public MeanStatistic() {
  }

  /**
   * Get the sum of samples.
   * @return the sum
   */
  public long getSum() {
    return sum;
  }

  /**
   * Get the sample count.
   * @return the sample count; 0 means empty
   */
  public long getSamples() {
    return samples;
  }

  /**
   * Is a statistic empty?
   * @return true if the sample count is 0
   */
  @JsonIgnore
  public boolean isEmpty() {
    return samples == 0;
  }


  public void clear() {
    setSamplesAndSum(0, 0);
  }

  /**
   * Set the sum and samples.
   * Synchronized.
   * @param sampleCount new sample count.
   * @param newSum new sum
   */
  public synchronized void setSamplesAndSum(long sampleCount,
      long newSum) {
    setSamples(sampleCount);
    sum = sampleCount;
  }

  /**
   * Set the statistic to the values of another.
   * Synchronized.
   * @param other the source.
   */
  public void set(final MeanStatistic other) {
    setSamplesAndSum(other.getSamples(), other.getSum());
  }

  /**
   * Set the sum.
   * @param sum new sum
   */
  public void setSum(final long sum) {
    this.sum = sum;
  }

  /**
   * Set the sample count.
   * <p></p>
   * If this is less than zero, it is set to zero.
   * <p></p>
   * This stops an ill-formed JSON entry from
   * breaking deserialization, or get an invalid sample count
   * into an entry.
   * @param samples sample count.
   */
  public void setSamples(final long samples) {
    if (samples < 0) {
      this.samples = 0;
    } else {
      this.samples = samples;
    }
  }

  /**
   * Get the arithmetic mean value.
   * <p></p>
   * Thread safe.
   * @return the mean
   */
  public synchronized double mean() {
    return samples > 0
        ? ((double) sum) / samples
        : 0.0d;
  }

  /**
   * Add another MeanStatistic.
   * @param other other value
   */
  public synchronized MeanStatistic add(final MeanStatistic other) {
    if (other.isEmpty()) {
      return this;
    }
    if (isEmpty()) {
      samples = other.samples;
      sum = other.sum;
      return this;
    }
    samples += other.samples;
    sum += other.sum;
    return this;
  }

  /**
   * Add a sample.
   * <p></p>
   * Thread safe.
   * @param value value to add to the sum
   */
  public synchronized void addSample(long value) {
    samples++;
    sum += value;
  }

  /**
   * The hash code is derived from the mean
   * and sample count: if either is changed
   * the statistic cannot be used as a key
   * for hash tables/maps.
   * @return a hash value
   */
  @Override
  public int hashCode() {
    return Objects.hash(sum, samples);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    MeanStatistic that = (MeanStatistic) o;
    if (isEmpty()) {
      // if we are empty, then so must the other.
      return that.isEmpty();
    }
    return sum == that.sum &&
        samples == that.samples;
  }

  @Override
  public MeanStatistic clone() {
    return copy();
  }

  /**
   * Create a copy of this instance.
   * @return copy.
   *
   */
  public MeanStatistic copy() {
    return new MeanStatistic(this);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(
        "MeanStatistic{");
    sb.append("sum=").append(sum);
    sb.append(", samples=").append(samples);
    sb.append(", mean=").append(mean());
    sb.append('}');
    return sb.toString();
  }

}
