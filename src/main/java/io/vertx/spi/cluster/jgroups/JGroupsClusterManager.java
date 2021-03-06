/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.spi.cluster.jgroups;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.Counter;
import io.vertx.core.shareddata.Lock;
import io.vertx.core.spi.cluster.AsyncMultiMap;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.core.spi.cluster.NodeListener;
import io.vertx.spi.cluster.jgroups.impl.CacheManager;
import io.vertx.spi.cluster.jgroups.impl.domain.ClusteredCounterImpl;
import io.vertx.spi.cluster.jgroups.impl.domain.ClusteredLockImpl;
import io.vertx.spi.cluster.jgroups.impl.listeners.TopologyListener;
import io.vertx.spi.cluster.jgroups.impl.support.LambdaLogger;
import org.jgroups.JChannel;
import org.jgroups.blocks.atomic.CounterService;
import org.jgroups.blocks.locking.LockService;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class JGroupsClusterManager implements ClusterManager, LambdaLogger {

  private static final Logger LOG = LoggerFactory.getLogger(JGroupsClusterManager.class);

  public static final String DEFAULT_CONFIG_FILE = "default-jgroups.xml";
  public static final String CONFIG_FILE = "jgroups.xml";

  public static final String CLUSTER_NAME = "JGROUPS_CLUSTER";

  private Vertx vertx;

  private CacheManager cacheManager;

  private JChannel channel;

  private CounterService counterService;
  private LockService lockService;

  private final String lock = "Lock";

  private boolean active = false;
  private String address;
  private TopologyListener topologyListener;

  private final boolean customChannel;


  public JGroupsClusterManager() {
    customChannel = false;
  }

  public JGroupsClusterManager(JChannel channel) {
    this.channel = channel;
    customChannel = true;
  }

  @Override
  public void setVertx(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public <K, V> void getAsyncMultiMap(String name, Handler<AsyncResult<AsyncMultiMap<K, V>>> handler) {
    logTrace(() -> String.format("Create new AsyncMultiMap [%s] on address [%s]", name, address));
    vertx.executeBlocking((future) -> {
      checkCluster();
      AsyncMultiMap<K, V> map = cacheManager.<K, V>createAsyncMultiMap(name);
      future.complete(map);
    }, handler);
  }

  @Override
  public <K, V> void getAsyncMap(String name, Handler<AsyncResult<AsyncMap<K, V>>> handler) {
    logTrace(() -> String.format("Create new AsyncMap [%s] on address [%s]", name, address));
    vertx.executeBlocking((future) -> {
      checkCluster();
      AsyncMap<K, V> map = cacheManager.<K, V>createAsyncMap(name);
      future.complete(map);
    }, handler);
  }

  @Override
  public <K, V> Map<K, V> getSyncMap(String name) {
    logTrace(() -> String.format("Create new SyncMap [%s] on address [%s]", name, address));
    checkCluster();
    return cacheManager.createSyncMap(name);
  }

  @Override
  public void getLockWithTimeout(String name, long timeout, Handler<AsyncResult<Lock>> handler) {
    logTrace(() -> String.format("Create new Lock [%s] on address [%s]", name, address));
    checkCluster();
    vertx.executeBlocking(
        future -> {
          ClusteredLockImpl lock = new ClusteredLockImpl(lockService, name);
          if (lock.acquire(timeout)) {
            logDebug(() -> String.format("Lock acquired on [%s]", name));
            future.complete(lock);
          } else {
            future.fail(String.format("Timed out waiting to get lock [%s]", name));
          }
        }, handler);
  }

  @Override
  public void getCounter(String name, Handler<AsyncResult<Counter>> handler) {
    logTrace(() -> String.format("Create new counter [%s] on address [%s]", name, address));
    checkCluster();
    vertx.executeBlocking(
        future -> future.complete(new ClusteredCounterImpl(vertx, counterService.getOrCreateCounter(name, 0L))),
        handler
    );
  }

  @Override
  public String getNodeID() {
    return address;
  }

  @Override
  public List<String> getNodes() {
    logTrace(() -> String.format("GetNodes on address [%s] with channel view [%s]", address, channel.getViewAsString()));
    return topologyListener.getNodes();
  }

  @Override
  public void nodeListener(NodeListener listener) {
    logTrace(() -> String.format("Set nodeListener [%s] on address [%s]", listener, address));
    topologyListener.setNodeListener(listener);
  }

  @Override
  public void join(Handler<AsyncResult<Void>> handler) {
    vertx.executeBlocking((future) -> {
      synchronized (lock) {
        if (!active) {
          try {

            if (! customChannel) {
              try (InputStream stream = getConfigStream()) {
                channel = new JChannel(stream);
              }
            }

            topologyListener = new TopologyListener(vertx);
            channel.setReceiver(topologyListener);
            channel.connect(CLUSTER_NAME);

            address = channel.getAddressAsString();

            logInfo(() -> String.format("Node id [%s] join the cluster", this.getNodeID()));

            counterService = new CounterService(channel);
            lockService = new LockService(channel);

            cacheManager = new CacheManager(vertx, channel);
            cacheManager.start();

            active = true;
          } catch (Exception e) {
            future.fail(e);
            return;
          }
        }
        future.complete();
      }
    }, handler);
  }

  @Override
  public void leave(Handler<AsyncResult<Void>> handler) {
    vertx.executeBlocking((future) -> {
      synchronized (lock) {
        if (active) {
          active = false;
          logInfo(() -> String.format("Node id [%s] leave the cluster", this.getNodeID()));

          cacheManager.stop();

          // If the channel was provided externally, it must be closed outside.
          if (! customChannel) {
            channel.close();
          }

          cacheManager = null;
          topologyListener = null;
          channel = null;
        }
        future.complete();
      }
    }, handler);
  }

  @Override
  public boolean isActive() {
    return active;
  }

  private void checkCluster() {
    if (!active) {
      throw new VertxException("Cluster is not active!");
    }
  }

  @Override
  public Logger log() {
    return LOG;
  }

  public static InputStream getConfigStream() {
    ClassLoader ctxClsLoader = Thread.currentThread().getContextClassLoader();
    InputStream is = null;
    if (ctxClsLoader != null) {
      is = ctxClsLoader.getResourceAsStream(CONFIG_FILE);
    }
    if (is == null) {
      is = JGroupsClusterManager.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
      if (is == null) {
        is = JGroupsClusterManager.class.getClassLoader().getResourceAsStream(DEFAULT_CONFIG_FILE);
      }
    }
    return is;
  }

  /**
   * Mostly useful for testing: closes the JChannel if it's still open, unless it was provided
   * to the constructor.
   */
  public void kill() {
    if (channel!=null && !customChannel && channel.isOpen()) {
      channel.close();
    }
  }
}
