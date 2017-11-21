package org.apache.solr.cloud.autoscaling.sim;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.CollectionStatePredicate;
import org.apache.solr.common.cloud.CollectionStateWatcher;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.util.TimeOut;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.junit.AfterClass;
import org.junit.Before;

/**
 *
 */
public class SimSolrCloudTestCase extends SolrTestCaseJ4 {

  public static final int DEFAULT_TIMEOUT = 90;

  /** The cluster. */
  protected static SimCloudManager cluster;

  protected static SimCloudManager configureCluster(int nodeCount, TimeSource timeSource) throws Exception {
    return SimCloudManager.createCluster(nodeCount, timeSource);
  }

  @AfterClass
  public static void shutdownCluster() throws Exception {
    if (cluster != null) {
      cluster.close();
    }
    cluster = null;
  }

  @Before
  public void checkClusterConfiguration() {
    if (cluster == null)
      throw new RuntimeException("MiniSolrCloudCluster not configured - have you called configureCluster().configure()?");
  }

  /* Cluster helper methods ************************************/

  /**
   * Get the collection state for a particular collection
   */
  protected DocCollection getCollectionState(String collectionName) throws IOException {
    return cluster.getClusterStateProvider().getClusterState().getCollection(collectionName);
  }

  /**
   * Wait for a particular collection state to appear in the cluster client's state reader
   *
   * This is a convenience method using the {@link #DEFAULT_TIMEOUT}
   *
   * @param message     a message to report on failure
   * @param collection  the collection to watch
   * @param predicate   a predicate to match against the collection state
   */
  protected void waitForState(String message, String collection, CollectionStatePredicate predicate) {
    AtomicReference<DocCollection> state = new AtomicReference<>();
    AtomicReference<Set<String>> liveNodesLastSeen = new AtomicReference<>();
    try {
      waitForState(collection, DEFAULT_TIMEOUT, TimeUnit.SECONDS, (n, c) -> {
        state.set(c);
        liveNodesLastSeen.set(n);
        return predicate.matches(n, c);
      });
    } catch (Exception e) {
      fail(message + "\n" + e.getMessage() + "\nLive Nodes: " + liveNodesLastSeen.get() + "\nLast available state: " + state.get());
    }
  }

  /**
   * Block until a CollectionStatePredicate returns true, or the wait times out
   *
   * Note that the predicate may be called again even after it has returned true, so
   * implementors should avoid changing state within the predicate call itself.
   *
   * @param collection the collection to watch
   * @param wait       how long to wait
   * @param unit       the units of the wait parameter
   * @param predicate  the predicate to call on state changes
   * @throws InterruptedException on interrupt
   * @throws TimeoutException on timeout
   * @throws IOException on watcher register / unregister error
   */
  public void waitForState(final String collection, long wait, TimeUnit unit, CollectionStatePredicate predicate)
      throws InterruptedException, TimeoutException, IOException {
    TimeOut timeout = new TimeOut(wait, unit, cluster.getTimeSource());
    while (!timeout.hasTimedOut()) {
      ClusterState state = cluster.getClusterStateProvider().getClusterState();
      DocCollection coll = state.getCollectionOrNull(collection);
      if (coll == null) { // already removed or does not exist
        return;
      }
      if (predicate.matches(state.getLiveNodes(), coll)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new TimeoutException();
  }

  /**
   * Return a {@link CollectionStatePredicate} that returns true if a collection has the expected
   * number of shards and replicas
   */
  public static CollectionStatePredicate clusterShape(int expectedShards, int expectedReplicas) {
    return (liveNodes, collectionState) -> {
      if (collectionState == null)
        return false;
      if (collectionState.getSlices().size() != expectedShards)
        return false;
      for (Slice slice : collectionState) {
        int activeReplicas = 0;
        for (Replica replica : slice) {
          if (replica.isActive(liveNodes))
            activeReplicas++;
        }
        if (activeReplicas != expectedReplicas)
          return false;
      }
      return true;
    };
  }

  /**
   * Get a (reproducibly) random shard from a {@link DocCollection}
   */
  protected static Slice getRandomShard(DocCollection collection) {
    List<Slice> shards = new ArrayList<>(collection.getActiveSlices());
    if (shards.size() == 0)
      fail("Couldn't get random shard for collection as it has no shards!\n" + collection.toString());
    Collections.shuffle(shards, random());
    return shards.get(0);
  }

  /**
   * Get a (reproducibly) random replica from a {@link Slice}
   */
  protected static Replica getRandomReplica(Slice slice) {
    List<Replica> replicas = new ArrayList<>(slice.getReplicas());
    if (replicas.size() == 0)
      fail("Couldn't get random replica from shard as it has no replicas!\n" + slice.toString());
    Collections.shuffle(replicas, random());
    return replicas.get(0);
  }

  /**
   * Get a (reproducibly) random replica from a {@link Slice} matching a predicate
   */
  protected static Replica getRandomReplica(Slice slice, Predicate<Replica> matchPredicate) {
    List<Replica> replicas = new ArrayList<>(slice.getReplicas());
    if (replicas.size() == 0)
      fail("Couldn't get random replica from shard as it has no replicas!\n" + slice.toString());
    Collections.shuffle(replicas, random());
    for (Replica replica : replicas) {
      if (matchPredicate.test(replica))
        return replica;
    }
    fail("Couldn't get random replica that matched conditions\n" + slice.toString());
    return null;  // just to keep the compiler happy - fail will always throw an Exception
  }
}
