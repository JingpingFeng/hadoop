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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.impl.OpenFileParameters;
import org.apache.hadoop.fs.s3a.S3AFileStatus;
import org.apache.hadoop.fs.s3a.S3AInputPolicy;
import org.apache.hadoop.fs.s3a.S3ALocatedFileStatus;
import org.apache.hadoop.fs.s3a.select.InternalSelectConstants;
import org.apache.hadoop.fs.s3a.select.SelectConstants;

import static org.apache.hadoop.fs.Options.OpenFileOptions.FS_OPTION_OPENFILE_BUFFER_SIZE;
import static org.apache.hadoop.fs.Options.OpenFileOptions.FS_OPTION_OPENFILE_FADVISE;
import static org.apache.hadoop.fs.Options.OpenFileOptions.FS_OPTION_OPENFILE_LENGTH;
import static org.apache.hadoop.fs.impl.AbstractFSBuilderImpl.rejectUnknownMandatoryKeys;
import static org.apache.hadoop.fs.s3a.Constants.INPUT_FADVISE;
import static org.apache.hadoop.fs.s3a.Constants.READAHEAD_RANGE;
import static org.apache.hadoop.thirdparty.com.google.common.base.Preconditions.checkArgument;

/**
 * Helper class for openFile() logic, especially processing file status
 * args and length/etag/versionID.
 * <p>
 *  This got complex enough it merited removal from S3AFileSystemy -which
 *  also permits unit testing.
 * </p>
 * <p>
 *   The default values are those from the FileSystem configuration.
 *   in openFile(), they can all be changed by specific options;
 *   in FileSystem.open(path, buffersize) only the buffer size is
 *   set.
 * </p>
 */
public class S3AOpenFileOperation extends AbstractStoreOperation {

  private static final Logger LOG =
      LoggerFactory.getLogger(S3AOpenFileOperation.class);

  /**  Default input policy. */
  private final S3AInputPolicy defaultInputPolicy;

  /**  Default change detection policy. */
  private final ChangeDetectionPolicy changePolicy;

  /** Default read ahead range. */
  private final long defaultReadAhead;

  /** Username. */
  private final String username;

  /** Default buffer size. */
  private final int defaultBufferSize;

  /**
   * Instantiate with the default options from the filesystem.
   * @param defaultInputPolicy input policy
   * @param changePolicy change detection policy
   * @param defaultReadAhead read ahead range
   * @param username username
   * @param defaultBufferSize buffer size
   */
  public S3AOpenFileOperation(
      final S3AInputPolicy defaultInputPolicy,
      final ChangeDetectionPolicy changePolicy,
      final long defaultReadAhead,
      final String username,
      final int defaultBufferSize) {
    super(null);
    this.defaultInputPolicy = defaultInputPolicy;
    this.changePolicy = changePolicy;
    this.defaultReadAhead = defaultReadAhead;
    this.username = username;
    this.defaultBufferSize = defaultBufferSize;
  }

  /**
   * The information on a file needed to open it.
   */
  public static final class OpenFileInformation {

    /** Is this SQL? */
    private final boolean isSql;

    /** File status; may be null. */
    private final S3AFileStatus status;

    /** SQL string if this is a SQL select file. */
    private final String sql;

    /** Active input policy. */
    private final S3AInputPolicy inputPolicy;

    /** Change detection policy. */
    private final ChangeDetectionPolicy changePolicy;

    /** read ahead range. */
    private final long readAheadRange;

    /** Buffer size. Currently ignored. */
    private final int bufferSize;

    /**
     * Constructor.
     */
    private OpenFileInformation(final boolean isSql,
        final S3AFileStatus status,
        final String sql,
        final S3AInputPolicy inputPolicy,
        final ChangeDetectionPolicy changePolicy,
        final long readAheadRange,
        final int bufferSize) {
      this.isSql = isSql;
      this.status = status;
      this.sql = sql;
      this.inputPolicy = inputPolicy;
      this.changePolicy = changePolicy;
      this.readAheadRange = readAheadRange;
      this.bufferSize = bufferSize;
    }

    public boolean isSql() {
      return isSql;
    }

    public S3AFileStatus getStatus() {
      return status;
    }

    public String getSql() {
      return sql;
    }

    public S3AInputPolicy getInputPolicy() {
      return inputPolicy;
    }

    public ChangeDetectionPolicy getChangePolicy() {
      return changePolicy;
    }

    public long getReadAheadRange() {
      return readAheadRange;
    }

    public int getBufferSize() {
      return bufferSize;
    }

    @Override
    public String toString() {
      return "OpenFileInformation{" +
          "isSql=" + isSql +
          ", status=" + status +
          ", sql='" + sql + '\'' +
          ", inputPolicy=" + inputPolicy +
          ", changePolicy=" + changePolicy +
          ", readAheadRange=" + readAheadRange +
          ", bufferSize=" + bufferSize +
          '}';
    }
  }

  /**
   * Prepare to open a file from the openFile parameters.
   * @param path path to the file
   * @param parameters open file parameters from the builder.
   * @param blockSize for fileStatus
   * @return open file options
   * @throws IOException failure to resolve the link.
   * @throws IllegalArgumentException unknown mandatory key
   */
  public OpenFileInformation prepareToOpenFile(
      final Path path,
      final OpenFileParameters parameters,
      final long blockSize) throws IOException {
    Configuration options = parameters.getOptions();
    Set<String> mandatoryKeys = parameters.getMandatoryKeys();
    String sql = options.get(SelectConstants.SELECT_SQL, null);
    boolean isSelect = sql != null;
    // choice of keys depends on open type
    if (isSelect) {
      // S3 Select call adds a large set of supported mandatory keys
      rejectUnknownMandatoryKeys(
          mandatoryKeys,
          InternalSelectConstants.SELECT_OPTIONS,
          "for " + path + " in S3 Select operation");
    } else {
      rejectUnknownMandatoryKeys(
          mandatoryKeys,
          InternalConstants.S3A_OPENFILE_KEYS,
          "for " + path + " in non-select file I/O");
    }
    FileStatus providedStatus = parameters.getStatus();
    S3AFileStatus fileStatus;
    if (providedStatus != null) {
      // there's a file status

      // make sure the file name matches -the rest of the path
      // MUST NOT be checked.
      Path providedStatusPath = providedStatus.getPath();
      checkArgument(path.getName().equals(providedStatusPath.getName()),
          "Filename mismatch between file being opened %s and"
              + " supplied filestatus %s",
          path, providedStatusPath);

      // make sure the status references a file
      if (providedStatus.isDirectory()) {
        throw new FileNotFoundException(
            "Supplied status references a directory " + providedStatus);
      }
      // build up the values
      long len = providedStatus.getLen();
      long modTime = providedStatus.getModificationTime();
      String versionId;
      String eTag;
      // can use this status to skip our own probes,
      LOG.debug("File was opened with a supplied FileStatus;"
              + " skipping getFileStatus call in open() operation: {}",
          providedStatus);
      if (providedStatus instanceof S3AFileStatus) {
        S3AFileStatus st = (S3AFileStatus) providedStatus;
        versionId = st.getVersionId();
        eTag = st.getETag();
      } else if (providedStatus instanceof S3ALocatedFileStatus) {
        //  S3ALocatedFileStatus instance may supply etag and version.
        S3ALocatedFileStatus st
            = (S3ALocatedFileStatus) providedStatus;
        versionId = st.getVersionId();
        eTag = st.getETag();
      } else {
        // it is another type.
        // build a status struct without etag or version.
        LOG.debug("Converting file status {}", providedStatus);
        versionId = null;
        eTag = null;
      }
      // Construct a new file status with the real path of the file.
      fileStatus = new S3AFileStatus(
          len,
          modTime,
          path,
          blockSize,
          username,
          eTag,
          versionId);
    } else if (options.get(FS_OPTION_OPENFILE_LENGTH) != null) {
      // build a minimal S3A FileStatus From the input length alone.
      // this is all we actually need.
      long length = options.getLong(FS_OPTION_OPENFILE_LENGTH, 0);
      LOG.debug("Fixing length of file to read {} as {}", path, length);
      fileStatus = new S3AFileStatus(
          length,
          0,
          path,
          blockSize,
          username,
          null,
          null);
    } else {
      // neither a file status nor a file length
      fileStatus = null;
    }

    // Build up the input policy.
    // seek policy from default, s3a opt or standard option
    // read from the FS standard option.
    Collection<String> policies =
        options.getStringCollection(FS_OPTION_OPENFILE_FADVISE);
    if (policies.isEmpty()) {
      // fall back to looking at the S3A-specific option.
      policies = options.getStringCollection(INPUT_FADVISE);
    }
    // get the first known policy
    S3AInputPolicy seekPolicy = S3AInputPolicy.getFirstSupportedPolicy(policies,
        defaultInputPolicy);
    // readahead range
    long readAhead = options.getLong(READAHEAD_RANGE, defaultReadAhead);
    // buffer size
    int bufferSize = options.getInt(FS_OPTION_OPENFILE_BUFFER_SIZE,
        defaultBufferSize);
    return new OpenFileInformation(isSelect, fileStatus, sql, seekPolicy,
        changePolicy, readAhead, bufferSize);
  }

  /**
   * Open a simple file.
   * @return the parameters needed to open a file through open(path, bufferSize).
   * @param bufferSize  buffer size
   */
  public OpenFileInformation openSimpleFile(final int bufferSize) {
    return new OpenFileInformation(false, null, null,
        defaultInputPolicy, changePolicy, defaultReadAhead, bufferSize);
  }

}
