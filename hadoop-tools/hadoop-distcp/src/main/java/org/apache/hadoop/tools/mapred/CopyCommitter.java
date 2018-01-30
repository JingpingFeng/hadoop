/**
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

package org.apache.hadoop.tools.mapred;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.store.BulkIO;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.tools.CopyListing;
import org.apache.hadoop.tools.CopyListingFileStatus;
import org.apache.hadoop.tools.DistCpConstants;
import org.apache.hadoop.tools.DistCpOptionSwitch;
import org.apache.hadoop.tools.DistCpContext;
import org.apache.hadoop.tools.DistCpOptions;
import org.apache.hadoop.tools.DistCpOptions.FileAttribute;
import org.apache.hadoop.tools.GlobbedCopyListing;
import org.apache.hadoop.tools.util.DistCpUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;

/**
 * The CopyCommitter class is DistCp's OutputCommitter implementation. It is
 * responsible for handling the completion/cleanup of the DistCp run.
 * Specifically, it does the following:
 *  1. Cleanup of the meta-folder (where DistCp maintains its file-list, etc.)
 *  2. Preservation of user/group/replication-factor on any directories that
 *     have been copied. (Files are taken care of in their map-tasks.)
 *  3. Atomic-move of data from the temporary work-folder to the final path
 *     (if atomic-commit was opted for).
 *  4. Deletion of files from the target that are missing at source (if opted for).
 *  5. Cleanup of any partially copied files, from previous, failed attempts.
 */
public class CopyCommitter extends FileOutputCommitter {
  private static final Log LOG = LogFactory.getLog(CopyCommitter.class);

  private final TaskAttemptContext taskAttemptContext;
  private boolean syncFolder = false;
  private boolean overwrite = false;
  private boolean targetPathExists = true;
  private boolean ignoreFailures = false;

  /**
   * Create a output committer
   *
   * @param outputPath the job's output path
   * @param context    the task's context
   * @throws IOException - Exception if any
   */
  public CopyCommitter(Path outputPath, TaskAttemptContext context) throws IOException {
    super(outputPath, context);
    this.taskAttemptContext = context;
  }

  /** {@inheritDoc} */
  @Override
  public void commitJob(JobContext jobContext) throws IOException {
    Configuration conf = jobContext.getConfiguration();
    syncFolder = conf.getBoolean(DistCpConstants.CONF_LABEL_SYNC_FOLDERS, false);
    overwrite = conf.getBoolean(DistCpConstants.CONF_LABEL_OVERWRITE, false);
    targetPathExists = conf.getBoolean(
        DistCpConstants.CONF_LABEL_TARGET_PATH_EXISTS, true);
    ignoreFailures = conf.getBoolean(
        DistCpOptionSwitch.IGNORE_FAILURES.getConfigLabel(), false);

    concatFileChunks(conf);

    super.commitJob(jobContext);

    cleanupTempFiles(jobContext);

    String attributes = conf.get(DistCpConstants.CONF_LABEL_PRESERVE_STATUS);
    final boolean preserveRawXattrs =
        conf.getBoolean(DistCpConstants.CONF_LABEL_PRESERVE_RAWXATTRS, false);
    if ((attributes != null && !attributes.isEmpty()) || preserveRawXattrs) {
      preserveFileAttributesForDirectories(conf);
    }

    try {
      if (conf.getBoolean(DistCpConstants.CONF_LABEL_DELETE_MISSING, false)) {
        deleteMissing(conf);
      } else if (conf.getBoolean(DistCpConstants.CONF_LABEL_ATOMIC_COPY, false)) {
        commitData(conf);
      }
      taskAttemptContext.setStatus("Commit Successful");
    }
    finally {
      cleanup(conf);
    }
  }

  /** {@inheritDoc} */
  @Override
  public void abortJob(JobContext jobContext,
                       JobStatus.State state) throws IOException {
    try {
      super.abortJob(jobContext, state);
    } finally {
      cleanupTempFiles(jobContext);
      cleanup(jobContext.getConfiguration());
    }
  }

  private void cleanupTempFiles(JobContext context) {
    try {
      Configuration conf = context.getConfiguration();

      Path targetWorkPath = new Path(conf.get(DistCpConstants.CONF_LABEL_TARGET_WORK_PATH));
      FileSystem targetFS = targetWorkPath.getFileSystem(conf);

      String jobId = context.getJobID().toString();
      deleteAttemptTempFiles(targetWorkPath, targetFS, jobId);
      deleteAttemptTempFiles(targetWorkPath.getParent(), targetFS, jobId);
    } catch (Throwable t) {
      LOG.warn("Unable to cleanup temp files", t);
    }
  }

  private void deleteAttemptTempFiles(Path targetWorkPath,
                                      FileSystem targetFS,
                                      String jobId) throws IOException {
    if (targetWorkPath == null) {
      return;
    }

    FileStatus[] tempFiles = targetFS.globStatus(
        new Path(targetWorkPath, ".distcp.tmp." + jobId.replaceAll("job","attempt") + "*"));

    if (tempFiles != null && tempFiles.length > 0) {
      for (FileStatus file : tempFiles) {
        LOG.info("Cleaning up " + file.getPath());
        targetFS.delete(file.getPath(), false);
      }
    }
  }

  /**
   * Cleanup meta folder and other temporary files
   *
   * @param conf - Job Configuration
   */
  private void cleanup(Configuration conf) {
    Path metaFolder = new Path(conf.get(DistCpConstants.CONF_LABEL_META_FOLDER));
    try {
      FileSystem fs = metaFolder.getFileSystem(conf);
      LOG.info("Cleaning up temporary work folder: " + metaFolder);
      fs.delete(metaFolder, true);
    } catch (IOException ignore) {
      LOG.error("Exception encountered ", ignore);
    }
  }

  private boolean isFileNotFoundException(IOException e) {
    if (e instanceof FileNotFoundException) {
      return true;
    }

    if (e instanceof RemoteException) {
      return ((RemoteException)e).unwrapRemoteException()
          instanceof FileNotFoundException;
    }

    return false;
  }

  /**
   * Concat chunk files for the same file into one.
   * Iterate through copy listing, identify chunk files for the same file,
   * concat them into one.
   */
  private void concatFileChunks(Configuration conf) throws IOException {

    LOG.info("concat file chunks ...");

    String spath = conf.get(DistCpConstants.CONF_LABEL_LISTING_FILE_PATH);
    if (spath == null || spath.isEmpty()) {
      return;
    }
    Path sourceListing = new Path(spath);
    SequenceFile.Reader sourceReader = new SequenceFile.Reader(conf,
                                      SequenceFile.Reader.file(sourceListing));
    Path targetRoot =
        new Path(conf.get(DistCpConstants.CONF_LABEL_TARGET_WORK_PATH));

    try {
      CopyListingFileStatus srcFileStatus = new CopyListingFileStatus();
      Text srcRelPath = new Text();
      CopyListingFileStatus lastFileStatus = null;
      LinkedList<Path> allChunkPaths = new LinkedList<Path>();

      // Iterate over every source path that was copied.
      while (sourceReader.next(srcRelPath, srcFileStatus)) {
        if (srcFileStatus.isDirectory()) {
          continue;
        }
        Path targetFile = new Path(targetRoot.toString() + "/" + srcRelPath);
        Path targetFileChunkPath =
            DistCpUtils.getSplitChunkPath(targetFile, srcFileStatus);
        if (LOG.isDebugEnabled()) {
          LOG.debug("  add " + targetFileChunkPath + " to concat.");
        }
        allChunkPaths.add(targetFileChunkPath);
        if (srcFileStatus.getChunkOffset() + srcFileStatus.getChunkLength()
            == srcFileStatus.getLen()) {
          // This is the last chunk of the splits, consolidate allChunkPaths
          try {
            concatFileChunks(conf, targetFile, allChunkPaths);
          } catch (IOException e) {
            // If the concat failed because a chunk file doesn't exist,
            // then we assume that the CopyMapper has skipped copying this
            // file, and we ignore the exception here.
            // If a chunk file should have been created but it was not, then
            // the CopyMapper would have failed.
            if (!isFileNotFoundException(e)) {
              String emsg = "Failed to concat chunk files for " + targetFile;
              if (!ignoreFailures) {
                throw new IOException(emsg, e);
              } else {
                LOG.warn(emsg, e);
              }
            }
          }
          allChunkPaths.clear();
          lastFileStatus = null;
        } else {
          if (lastFileStatus == null) {
            lastFileStatus = new CopyListingFileStatus(srcFileStatus);
          } else {
            // Two neighboring chunks have to be consecutive ones for the same
            // file, for them to be merged
            if (!srcFileStatus.getPath().equals(lastFileStatus.getPath()) ||
                srcFileStatus.getChunkOffset() !=
                (lastFileStatus.getChunkOffset() +
                lastFileStatus.getChunkLength())) {
              String emsg = "Inconsistent sequence file: current " +
                  "chunk file " + srcFileStatus + " doesnt match prior " +
                  "entry " + lastFileStatus;
              if (!ignoreFailures) {
                throw new IOException(emsg);
              } else {
                LOG.warn(emsg + ", skipping concat this set.");
              }
            } else {
              lastFileStatus.setChunkOffset(srcFileStatus.getChunkOffset());
              lastFileStatus.setChunkLength(srcFileStatus.getChunkLength());
            }
          }
        }
      }
    } finally {
      IOUtils.closeStream(sourceReader);
    }
  }

  // This method changes the target-directories' file-attributes (owner,
  // user/group permissions, etc.) based on the corresponding source directories.
  private void preserveFileAttributesForDirectories(Configuration conf)
      throws IOException {
    String attrSymbols = conf.get(DistCpConstants.CONF_LABEL_PRESERVE_STATUS);
    final boolean syncOrOverwrite = syncFolder || overwrite;

    LOG.info("About to preserve attributes: " + attrSymbols);

    EnumSet<FileAttribute> attributes = DistCpUtils.unpackAttributes(attrSymbols);
    final boolean preserveRawXattrs =
        conf.getBoolean(DistCpConstants.CONF_LABEL_PRESERVE_RAWXATTRS, false);

    Path sourceListing = new Path(conf.get(DistCpConstants.CONF_LABEL_LISTING_FILE_PATH));
    FileSystem clusterFS = sourceListing.getFileSystem(conf);
    SequenceFile.Reader sourceReader = new SequenceFile.Reader(conf,
                                      SequenceFile.Reader.file(sourceListing));
    long totalLen = clusterFS.getFileStatus(sourceListing).getLen();

    Path targetRoot = new Path(conf.get(DistCpConstants.CONF_LABEL_TARGET_WORK_PATH));

    long preservedEntries = 0;
    try {
      CopyListingFileStatus srcFileStatus = new CopyListingFileStatus();
      Text srcRelPath = new Text();

      // Iterate over every source path that was copied.
      while (sourceReader.next(srcRelPath, srcFileStatus)) {
        // File-attributes for files are set at the time of copy,
        // in the map-task.
        if (! srcFileStatus.isDirectory()) continue;

        Path targetFile = new Path(targetRoot.toString() + "/" + srcRelPath);
        //
        // Skip the root folder when syncOrOverwrite is true.
        //
        if (targetRoot.equals(targetFile) && syncOrOverwrite) continue;

        FileSystem targetFS = targetFile.getFileSystem(conf);
        DistCpUtils.preserve(targetFS, targetFile, srcFileStatus, attributes,
            preserveRawXattrs);

        taskAttemptContext.progress();
        taskAttemptContext.setStatus("Preserving status on directory entries. [" +
            sourceReader.getPosition() * 100 / totalLen + "%]");
      }
    } finally {
      IOUtils.closeStream(sourceReader);
    }
    LOG.info("Preserved status on " + preservedEntries + " dir entries on target");
  }

  // This method deletes "extra" files from the target, if they're not
  // available at the source.
  private void deleteMissing(Configuration conf) throws IOException {
    LOG.info("-delete option is enabled. About to remove entries from " +
        "target that are missing in source");

    // Sort the source-file listing alphabetically.
    Path sourceListing = new Path(conf.get(DistCpConstants.CONF_LABEL_LISTING_FILE_PATH));
    FileSystem clusterFS = sourceListing.getFileSystem(conf);
    Path sortedSourceListing = DistCpUtils.sortListing(clusterFS, conf, sourceListing);

    // Similarly, create the listing of target-files. Sort alphabetically.
    Path targetListing = new Path(sourceListing.getParent(), "targetListing.seq");
    CopyListing target = new GlobbedCopyListing(new Configuration(conf), null);

    List<Path> targets = new ArrayList<Path>(1);
    Path targetFinalPath = new Path(conf.get(DistCpConstants.CONF_LABEL_TARGET_FINAL_PATH));
    targets.add(targetFinalPath);
    Path resultNonePath = Path.getPathWithoutSchemeAndAuthority(targetFinalPath)
        .toString().startsWith(DistCpConstants.HDFS_RESERVED_RAW_DIRECTORY_NAME)
        ? DistCpConstants.RAW_NONE_PATH : DistCpConstants.NONE_PATH;
    //
    // Set up options to be the same from the CopyListing.buildListing's perspective,
    // so to collect similar listings as when doing the copy
    //
    DistCpOptions options = new DistCpOptions.Builder(targets, resultNonePath)
        .withOverwrite(overwrite)
        .withSyncFolder(syncFolder)
        .build();
    DistCpContext distCpContext = new DistCpContext(options);
    distCpContext.setTargetPathExists(targetPathExists);

    target.buildListing(targetListing, distCpContext);
    Path sortedTargetListing = DistCpUtils.sortListing(clusterFS, conf, targetListing);
    long totalLen = clusterFS.getFileStatus(sortedTargetListing).getLen();

    SequenceFile.Reader sourceReader = new SequenceFile.Reader(conf,
                                 SequenceFile.Reader.file(sortedSourceListing));
    SequenceFile.Reader targetReader = new SequenceFile.Reader(conf,
                                 SequenceFile.Reader.file(sortedTargetListing));

    // Walk both source and target file listings.
    // Delete all from target that doesn't also exist on source.
    long deletedEntries = 0;
    try {
      CopyListingFileStatus srcFileStatus = new CopyListingFileStatus();
      Text srcRelPath = new Text();
      CopyListingFileStatus trgtFileStatus = new CopyListingFileStatus();
      Text trgtRelPath = new Text();

      FileSystem targetFS = targetFinalPath.getFileSystem(conf);
      int pageSize = 0;
      boolean showProgress = false;
      List<Path> deletePage = null;
      BulkIO bulkDelete = null;
      boolean useBulkDelete = false;
      if (targetFS instanceof BulkIO) {
        bulkDelete = (BulkIO) targetFS;
        pageSize = bulkDelete.getBulkDeleteFilesLimit();
        LOG.info("Destination filesystem supports bulk deletes, "
            + "maximum size " + pageSize);
        if (pageSize <= 0) {
          LOG.info("Bulk delete is disabled");
        } else {
          useBulkDelete = true;
          deletePage = new ArrayList<>();
        }
      }

      boolean srcAvailable = sourceReader.next(srcRelPath, srcFileStatus);
      while (targetReader.next(trgtRelPath, trgtFileStatus)) {
        // Skip sources that don't exist on target.
        while (srcAvailable && trgtRelPath.compareTo(srcRelPath) > 0) {
          srcAvailable = sourceReader.next(srcRelPath, srcFileStatus);
        }

        if (srcAvailable && trgtRelPath.equals(srcRelPath)) continue;

        // Target doesn't exist at source. Delete.
        if (!useBulkDelete) {
          boolean result = targetFS.delete(trgtFileStatus.getPath(), true)
              || !targetFS.exists(trgtFileStatus.getPath());
          if (result) {
            LOG.info("Deleted " + trgtFileStatus.getPath() + " - Missing at source");
            deletedEntries++;
            showProgress = true;
          } else {
            throw new IOException("Unable to delete " + trgtFileStatus.getPath());
          }
        } else {
          deletePage.add(trgtFileStatus.getPath());
          if (deletePage.size() == pageSize) {
            showProgress = true;
            LOG.info("Initiating bulk delete of size " + deletePage.size());
            deletedEntries += bulkDelete.bulkDeleteFiles(deletePage);
            deletePage.clear();
          } else {
            // no delete
            showProgress = false;
          }
        }
        if (showProgress) {
          // update progress if there's been any FS IO/files deleted.
          taskAttemptContext.progress();
          taskAttemptContext.setStatus("Deleting missing files from target. [" +
              targetReader.getPosition() * 100 / totalLen + "%]");
        }
      }
      // end of the loop: there may still be some bulk delete files to write
      if (useBulkDelete) {
        LOG.info("Initiating final bulk delete of size " + deletePage.size());
        deletedEntries += bulkDelete.bulkDeleteFiles(deletePage);
      }
    } finally {
      IOUtils.closeStream(sourceReader);
      IOUtils.closeStream(targetReader);
    }
    LOG.info("Deleted " + deletedEntries + " from target: " + targets.get(0));
  }

  private void commitData(Configuration conf) throws IOException {

    Path workDir = new Path(conf.get(DistCpConstants.CONF_LABEL_TARGET_WORK_PATH));
    Path finalDir = new Path(conf.get(DistCpConstants.CONF_LABEL_TARGET_FINAL_PATH));
    FileSystem targetFS = workDir.getFileSystem(conf);

    LOG.info("Atomic commit enabled. Moving " + workDir + " to " + finalDir);
    if (targetFS.exists(finalDir) && targetFS.exists(workDir)) {
      LOG.error("Pre-existing final-path found at: " + finalDir);
      throw new IOException("Target-path can't be committed to because it " +
          "exists at " + finalDir + ". Copied data is in temp-dir: " + workDir + ". ");
    }

    boolean result = targetFS.rename(workDir, finalDir);
    if (!result) {
      LOG.warn("Rename failed. Perhaps data already moved. Verifying...");
      result = targetFS.exists(finalDir) && !targetFS.exists(workDir);
    }
    if (result) {
      LOG.info("Data committed successfully to " + finalDir);
      taskAttemptContext.setStatus("Data committed successfully to " + finalDir);
    } else {
      LOG.error("Unable to commit data to " + finalDir);
      throw new IOException("Atomic commit failed. Temporary data in " + workDir +
        ", Unable to move to " + finalDir);
    }
  }

  /**
   * Concat the passed chunk files into one and rename it the targetFile.
   */
  private void concatFileChunks(Configuration conf, Path targetFile,
      LinkedList<Path> allChunkPaths) throws IOException {
    if (allChunkPaths.size() == 1) {
      return;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("concat " + targetFile + " allChunkSize+ "
          + allChunkPaths.size());
    }
    FileSystem dstfs = targetFile.getFileSystem(conf);

    Path firstChunkFile = allChunkPaths.removeFirst();
    Path[] restChunkFiles = new Path[allChunkPaths.size()];
    allChunkPaths.toArray(restChunkFiles);
    if (LOG.isDebugEnabled()) {
      LOG.debug("concat: firstchunk: " + dstfs.getFileStatus(firstChunkFile));
      int i = 0;
      for (Path f : restChunkFiles) {
        LOG.debug("concat: other chunk: " + i + ": " + dstfs.getFileStatus(f));
        ++i;
      }
    }
    dstfs.concat(firstChunkFile, restChunkFiles);
    if (LOG.isDebugEnabled()) {
      LOG.debug("concat: result: " + dstfs.getFileStatus(firstChunkFile));
    }
    rename(dstfs, firstChunkFile, targetFile);
  }

  /**
   * Rename tmp to dst on destFileSys.
   * @param destFileSys the file ssystem
   * @param tmp the source path
   * @param dst the destination path
   * @throws IOException if renaming failed
   */
  private static void rename(FileSystem destFileSys, Path tmp, Path dst)
      throws IOException {
    try {
      if (destFileSys.exists(dst)) {
        destFileSys.delete(dst, true);
      }
      destFileSys.rename(tmp, dst);
    } catch (IOException ioe) {
      throw new IOException("Fail to rename tmp file (=" + tmp
          + ") to destination file (=" + dst + ")", ioe);
    }
  }

}
