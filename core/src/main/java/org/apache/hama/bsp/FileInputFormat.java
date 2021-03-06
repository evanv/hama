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
package org.apache.hama.bsp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.mapred.InvalidInputException;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

public abstract class FileInputFormat<K, V> implements InputFormat<K, V> {
  public static final Log LOG = LogFactory.getLog(FileInputFormat.class);

  private static final double SPLIT_SLOP = 1.1; // 10% slop

  private static final PathFilter hiddenFileFilter = new PathFilter() {
    @Override
    public boolean accept(Path p) {
      String name = p.getName();
      return !name.startsWith("_") && !name.startsWith(".");
    }
  };

  /**
   * Proxy PathFilter that accepts a path only if all filters given in the
   * constructor do. Used by the listPaths() to apply the built-in
   * hiddenFileFilter together with a user provided one (if any).
   */
  private static class MultiPathFilter implements PathFilter {
    private List<PathFilter> filters;

    public MultiPathFilter(List<PathFilter> filters) {
      this.filters = filters;
    }

    @Override
    public boolean accept(Path path) {
      for (PathFilter filter : filters) {
        if (!filter.accept(path)) {
          return false;
        }
      }
      return true;
    }
  }

  @Override
  public abstract RecordReader<K, V> getRecordReader(InputSplit split,
      BSPJob job) throws IOException;

  /**
   * Set a PathFilter to be applied to the input paths for the BSP job.
   * 
   * @param filter the PathFilter class use for filtering the input paths.
   */
  public static void setInputPathFilter(BSPJob conf,
      Class<? extends PathFilter> filter) {
    conf.getConfiguration().setClass("bsp.input.pathFilter.class", filter,
        PathFilter.class);
  }

  /**
   * Get a PathFilter instance of the filter set for the input paths.
   * 
   * @return the PathFilter instance set for the job, NULL if none has been set.
   */
  public static PathFilter getInputPathFilter(BSPJob conf) {
    Class<? extends PathFilter> filterClass = conf.getConfiguration().getClass(
        "bsp.input.pathFilter.class", null, PathFilter.class);
    return (filterClass != null) ? ReflectionUtils.newInstance(filterClass,
        conf.getConfiguration()) : null;
  }

  /**
   * List input directories. Subclasses may override to, e.g., select only files
   * matching a regular expression.
   * 
   * @param job the job to list input paths for
   * @return array of FileStatus objects
   * @throws IOException if zero items.
   */
  protected FileStatus[] listStatus(BSPJob job) throws IOException {
    Path[] dirs = getInputPaths(job);
    if (dirs.length == 0) {
      throw new IOException("No input paths specified in job");
    }

    List<FileStatus> result = new ArrayList<FileStatus>();
    List<IOException> errors = new ArrayList<IOException>();

    // creates a MultiPathFilter with the hiddenFileFilter and the
    // user provided one (if any).
    List<PathFilter> filters = new ArrayList<PathFilter>();
    filters.add(hiddenFileFilter);
    PathFilter jobFilter = getInputPathFilter(job);
    if (jobFilter != null) {
      filters.add(jobFilter);
    }
    PathFilter inputFilter = new MultiPathFilter(filters);

    for (Path p : dirs) {
      FileSystem fs = p.getFileSystem(job.getConfiguration());

      FileStatus[] matches = null;
      try {
        matches = fs.globStatus(p, inputFilter);
      } catch (Exception e) {
        LOG.info(p + "\n" + e.toString());
      }

      if (matches == null) {
        errors.add(new IOException("Input path does not exist: " + p));
      } else if (matches.length == 0) {
        errors.add(new IOException("Input Pattern " + p + " matches 0 files"));
      } else {
        for (FileStatus globStat : matches) {
          if (globStat.isDir()) {
            Collections.addAll(result,
                fs.listStatus(globStat.getPath(), inputFilter));
          } else {
            result.add(globStat);
          }
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new InvalidInputException(errors);
    }
    LOG.info("Total input paths to process : " + result.size());
    return result.toArray(new FileStatus[result.size()]);
  }

  /**
   * Splits files returned by {@link #listStatus(BSPJob)} when they're too big. <br/>
   * numSplits will be ignored by the framework.
   */
  @Override
  public InputSplit[] getSplits(BSPJob job, int numSplits) throws IOException {
    long minSize = Math.max(getFormatMinSplitSize(), getMinSplitSize(job));
    long maxSize = getMaxSplitSize(job);
    
    // generate splits
    List<InputSplit> splits = new ArrayList<InputSplit>();
    FileStatus[] files = listStatus(job);
    for (FileStatus file : files) {
      Path path = file.getPath();
      FileSystem fs = path.getFileSystem(job.getConfiguration());
      long length = file.getLen();
      BlockLocation[] blkLocations = fs.getFileBlockLocations(file, 0, length);
      if ((length != 0) && isSplitable(job, path)) {
        long blockSize = file.getBlockSize();
        long splitSize = computeSplitSize(blockSize, minSize, maxSize);

        long bytesRemaining = length;
        while (((double) bytesRemaining) / splitSize > SPLIT_SLOP) {
          int blkIndex = getBlockIndex(blkLocations, length - bytesRemaining);
          splits.add(new FileSplit(path, length - bytesRemaining, splitSize,
              blkLocations[blkIndex].getHosts()));
          bytesRemaining -= splitSize;
        }

        if (bytesRemaining != 0) {
          splits
              .add(new FileSplit(path, length - bytesRemaining, bytesRemaining,
                  blkLocations[blkLocations.length - 1].getHosts()));
        }
      } else if (length != 0) {
        splits.add(new FileSplit(path, 0, length, blkLocations[0].getHosts()));
      } else {
        // Create empty hosts array for zero length files
        splits.add(new FileSplit(path, 0, length, new String[0]));
      }
    }

    // Save the number of input files in the job-conf
    job.getConfiguration().setLong("bsp.input.files", files.length);

    LOG.debug("Total # of splits: " + splits.size());
    return splits.toArray(new InputSplit[splits.size()]);
  }

  /**
   * @return true if the file is splittable (default), false if not.
   */
  protected boolean isSplitable(BSPJob job, Path path) {
    return true;
  }

  /**
   * Get the lower bound on split size imposed by the format.
   * 
   * @return the number of bytes of the minimal split for this format
   */
  protected long getFormatMinSplitSize() {
    return 1;
  }

  /**
   * Set the minimum input split size
   * 
   * @param job the job to modify
   * @param size the minimum size
   */
  public static void setMinInputSplitSize(Job job, long size) {
    job.getConfiguration().setLong("bsp.min.split.size", size);
  }

  /**
   * Get the minimum split size
   * 
   * @param job the job
   * @return the minimum number of bytes that can be in a split
   */
  public static long getMinSplitSize(BSPJob job) {
    return job.getConfiguration().getLong("bsp.min.split.size", 1L);
  }

  /**
   * Set the maximum split size
   * 
   * @param job the job to modify
   * @param size the maximum split size
   */
  public static void setMaxInputSplitSize(Job job, long size) {
    job.getConfiguration().setLong("bsp.max.split.size", size);
  }

  /**
   * Get the maximum split size.
   * 
   * @param context the job to look at.
   * @return the maximum number of bytes a split can include
   */
  public static long getMaxSplitSize(BSPJob context) {
    return context.getConfiguration().getLong("bsp.max.split.size",
        Long.MAX_VALUE);
  }

  protected long computeTotalSize(BSPJob job, FileStatus[] files)
      throws IOException {
    long totalSize = 0L;
    for (int i = 0; i < files.length; i++) { // check we have valid files
      FileStatus file = files[i];
      if (file.isDir()) {
        final Path path = file.getPath();
        if (path.getName().equals("hama-partitions")
            || (job.get("bsp.partitioning.dir") != null && path.getName()
                .equals(job.get("bsp.partitioning.dir")))) {
          // if we find the partitioning dir, just remove it.
          LOG.warn("Removing already existing partitioning directory " + path);
          FileSystem fileSystem = path.getFileSystem(job.getConfiguration());
          if (!fileSystem.delete(path, true)) {
            LOG.error("Remove failed.");
          }
          // remove this file from our initial list
          files[i] = null;
        } else {
          throw new IOException("Not a file (dir): " + path);
        }
      }
      totalSize += file.getLen();
    }
    return totalSize;
  }

  protected long computeGoalSize(int numSplits, long totalSize) {
    // The minus 1 is for the remainder.
    return totalSize / (numSplits <= 1 ? 1 : numSplits - 1);
  }

  protected long computeSplitSize(long goalSize, long minSize, long blockSize) {
    if (goalSize > blockSize) {
      return Math.max(minSize, Math.max(goalSize, blockSize));
    } else {
      return Math.max(minSize, Math.min(goalSize, blockSize));
    }
  }

  protected int getBlockIndex(BlockLocation[] blkLocations, long offset) {
    for (int i = 0; i < blkLocations.length; i++) {
      // is the offset inside this block?
      if ((blkLocations[i].getOffset() <= offset)
          && (offset < blkLocations[i].getOffset()
              + blkLocations[i].getLength())) {
        return i;
      }
    }
    BlockLocation last = blkLocations[blkLocations.length - 1];
    long fileLength = last.getOffset() + last.getLength() - 1;
    throw new IllegalArgumentException("Offset " + offset
        + " is outside of file (0.." + fileLength + ")");
  }

  /**
   * Sets the given comma separated paths as the list of inputs for the BSP job.
   * 
   * @param conf Configuration of the job
   * @param commaSeparatedPaths Comma separated paths to be set as the list of
   *          inputs for the BSP job.
   */
  public static void setInputPaths(BSPJob conf, String commaSeparatedPaths) {
    setInputPaths(conf,
        StringUtils.stringToPath(getPathStrings(commaSeparatedPaths)));
  }

  /**
   * Add the given comma separated paths to the list of inputs for the BSP job.
   * 
   * @param conf The configuration of the job
   * @param commaSeparatedPaths Comma separated paths to be added to the list of
   *          inputs for the BSP job.
   */
  public static void addInputPaths(BSPJob conf, String commaSeparatedPaths) {
    for (String str : getPathStrings(commaSeparatedPaths)) {
      addInputPath(conf, new Path(str));
    }
  }

  /**
   * Set the array of {@link Path}s as the list of inputs for the BSP job.
   * 
   * @param conf Configuration of the job.
   * @param inputPaths the {@link Path}s of the input directories/files for the
   *          BSP job.
   */
  public static void setInputPaths(BSPJob conf, Path... inputPaths) {
    Path path = new Path(conf.getWorkingDirectory(), inputPaths[0]);
    StringBuffer str = new StringBuffer(StringUtils.escapeString(path
        .toString()));
    for (int i = 1; i < inputPaths.length; i++) {
      str.append(StringUtils.COMMA_STR);
      path = new Path(conf.getWorkingDirectory(), inputPaths[i]);
      str.append(StringUtils.escapeString(path.toString()));
    }
    conf.set("bsp.input.dir", str.toString());
  }

  /**
   * Add a {@link Path} to the list of inputs for the BSP job.
   * 
   * @param conf The configuration of the job
   * @param path {@link Path} to be added to the list of inputs for the BSP job.
   */
  public static void addInputPath(BSPJob conf, Path p) {
    Path path = new Path(conf.getWorkingDirectory(), p);
    String dirStr = StringUtils.escapeString(path.toString());
    String dirs = conf.get("bsp.input.dir");
    conf.set("bsp.input.dir", dirs == null ? dirStr : dirs
        + StringUtils.COMMA_STR + dirStr);
  }

  // This method escapes commas in the glob pattern of the given paths.
  private static String[] getPathStrings(String commaSeparatedPaths) {
    int length = commaSeparatedPaths.length();
    int curlyOpen = 0;
    int pathStart = 0;
    boolean globPattern = false;
    List<String> pathStrings = new ArrayList<String>();

    for (int i = 0; i < length; i++) {
      char ch = commaSeparatedPaths.charAt(i);
      switch (ch) {
        case '{': {
          curlyOpen++;
          if (!globPattern) {
            globPattern = true;
          }
          break;
        }
        case '}': {
          curlyOpen--;
          if (curlyOpen == 0 && globPattern) {
            globPattern = false;
          }
          break;
        }
        case ',': {
          if (!globPattern) {
            pathStrings.add(commaSeparatedPaths.substring(pathStart, i));
            pathStart = i + 1;
          }
          break;
        }
      }
    }
    pathStrings.add(commaSeparatedPaths.substring(pathStart, length));

    return pathStrings.toArray(new String[pathStrings.size()]);
  }

  /**
   * Get the list of input {@link Path}s for the BSP job.
   * 
   * @param conf The configuration of the job
   * @return the list of input {@link Path}s for the BSP job.
   */
  public static Path[] getInputPaths(BSPJob conf) {
    String dirs = conf.getConfiguration().get("bsp.input.dir", "");
    String[] list = StringUtils.split(dirs);
    Path[] result = new Path[list.length];
    for (int i = 0; i < list.length; i++) {
      result[i] = new Path(StringUtils.unEscapeString(list[i]));
    }
    return result;
  }

  private static void sortInDescendingOrder(List<NodeInfo> mylist) {
    Collections.sort(mylist, new Comparator<NodeInfo>() {
      @Override
      public int compare(NodeInfo obj1, NodeInfo obj2) {

        if (obj1 == null || obj2 == null)
          return -1;

        if (obj1.getValue() == obj2.getValue()) {
          return 0;
        } else {
          return ((obj1.getValue() < obj2.getValue()) ? 1 : -1);
        }
      }
    });
  }

  /**
   * This function identifies and returns the hosts that contribute most for a
   * given split. For calculating the contribution, rack locality is treated on
   * par with host locality, so hosts from racks that contribute the most are
   * preferred over hosts on racks that contribute less
   * 
   * @param blkLocations The list of block locations
   * @param offset
   * @param splitSize
   * @return array of hosts that contribute most to this split
   * @throws IOException
   */
  protected String[] getSplitHosts(BlockLocation[] blkLocations, long offset,
      long pSplitSize, NetworkTopology clusterMap) throws IOException {
    long splitSize = pSplitSize;
    int startIndex = getBlockIndex(blkLocations, offset);

    long bytesInThisBlock = blkLocations[startIndex].getOffset()
        + blkLocations[startIndex].getLength() - offset;

    // If this is the only block, just return
    if (bytesInThisBlock >= splitSize) {
      return blkLocations[startIndex].getHosts();
    }

    long bytesInFirstBlock = bytesInThisBlock;
    int index = startIndex + 1;
    splitSize -= bytesInThisBlock;

    while (splitSize > 0) {
      bytesInThisBlock = Math.min(splitSize, blkLocations[index++].getLength());
      splitSize -= bytesInThisBlock;
    }

    long bytesInLastBlock = bytesInThisBlock;
    int endIndex = index - 1;

    Map<Node, NodeInfo> hostsMap = new IdentityHashMap<Node, NodeInfo>();
    Map<Node, NodeInfo> racksMap = new IdentityHashMap<Node, NodeInfo>();
    String[] allTopos = new String[0];

    // Build the hierarchy and aggregate the contribution of
    // bytes at each level. See TestGetSplitHosts.java

    for (index = startIndex; index <= endIndex; index++) {

      // Establish the bytes in this block
      if (index == startIndex) {
        bytesInThisBlock = bytesInFirstBlock;
      } else if (index == endIndex) {
        bytesInThisBlock = bytesInLastBlock;
      } else {
        bytesInThisBlock = blkLocations[index].getLength();
      }

      allTopos = blkLocations[index].getTopologyPaths();

      // If no topology information is available, just
      // prefix a fakeRack
      if (allTopos.length == 0) {
        allTopos = fakeRacks(blkLocations, index);
      }

      // NOTE: This code currently works only for one level of
      // hierarchy (rack/host). However, it is relatively easy
      // to extend this to support aggregation at different
      // levels

      for (String topo : allTopos) {

        Node node, parentNode;
        NodeInfo nodeInfo, parentNodeInfo;

        node = clusterMap.getNode(topo);

        if (node == null) {
          node = new NodeBase(topo);
          clusterMap.add(node);
        }

        nodeInfo = hostsMap.get(node);

        if (nodeInfo == null) {
          nodeInfo = new NodeInfo(node);
          hostsMap.put(node, nodeInfo);
          parentNode = node.getParent();
          parentNodeInfo = racksMap.get(parentNode);
          if (parentNodeInfo == null) {
            parentNodeInfo = new NodeInfo(parentNode);
            racksMap.put(parentNode, parentNodeInfo);
          }
          parentNodeInfo.addLeaf(nodeInfo);
        } else {
          nodeInfo = hostsMap.get(node);
          parentNode = node.getParent();
          parentNodeInfo = racksMap.get(parentNode);
        }

        nodeInfo.addValue(index, bytesInThisBlock);
        parentNodeInfo.addValue(index, bytesInThisBlock);

      } // for all topos

    } // for all indices

    return identifyHosts(allTopos.length, racksMap);
  }

  private static String[] identifyHosts(int replicationFactor,
      Map<Node, NodeInfo> racksMap) {

    String[] retVal = new String[replicationFactor];

    List<NodeInfo> rackList = new LinkedList<NodeInfo>();

    rackList.addAll(racksMap.values());

    // Sort the racks based on their contribution to this split
    sortInDescendingOrder(rackList);

    boolean done = false;
    int index = 0;

    // Get the host list for all our aggregated items, sort
    // them and return the top entries
    for (NodeInfo ni : rackList) {

      Set<NodeInfo> hostSet = ni.getLeaves();

      List<NodeInfo> hostList = new LinkedList<NodeInfo>();
      hostList.addAll(hostSet);

      // Sort the hosts in this rack based on their contribution
      sortInDescendingOrder(hostList);

      for (NodeInfo host : hostList) {
        // Strip out the port number from the host name
        retVal[index++] = host.node.getName().split(":")[0];
        if (index == replicationFactor) {
          done = true;
          break;
        }
      }

      if (done == true) {
        break;
      }
    }
    return retVal;
  }

  private static String[] fakeRacks(BlockLocation[] blkLocations, int index)
      throws IOException {
    String[] allHosts = blkLocations[index].getHosts();
    String[] allTopos = new String[allHosts.length];
    for (int i = 0; i < allHosts.length; i++) {
      allTopos[i] = NetworkTopology.DEFAULT_RACK + "/" + allHosts[i];
    }
    return allTopos;
  }

  private static class NodeInfo {
    final Node node;
    final Set<Integer> blockIds;
    final Set<NodeInfo> leaves;

    private long value;

    NodeInfo(Node node) {
      this.node = node;
      blockIds = new HashSet<Integer>();
      leaves = new HashSet<NodeInfo>();
    }

    long getValue() {
      return value;
    }

    void addValue(int blockIndex, long value) {
      if (blockIds.add(blockIndex) == true) {
        this.value += value;
      }
    }

    Set<NodeInfo> getLeaves() {
      return leaves;
    }

    void addLeaf(NodeInfo nodeInfo) {
      leaves.add(nodeInfo);
    }
  }
}
