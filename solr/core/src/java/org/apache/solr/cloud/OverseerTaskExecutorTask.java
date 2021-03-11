/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.cloud.overseer.ZkStateWriter;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.core.CoreContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.Future;

public class OverseerTaskExecutorTask {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final ZkController zkController;
  private final SolrCloudManager cloudManager;
  private final SolrZkClient zkClient;
  private final ZkNodeProps message;

  public OverseerTaskExecutorTask(CoreContainer cc,  ZkNodeProps message) {
    this.zkController = cc.getZkController();
    this.zkClient = zkController.getZkClient();
    this.cloudManager = zkController.getSolrCloudManager();
    this.message = message;
  }


  private Future processQueueItem(ZkNodeProps message) throws Exception {
    if (log.isDebugEnabled()) log.debug("Consume state update from queue {} {}", message);

    // assert clusterState != null;

    //  if (clusterState.getZNodeVersion() == 0 || clusterState.getZNodeVersion() > lastVersion) {

    final String operation = message.getStr(Overseer.QUEUE_OPERATION);
    if (operation == null) {
      log.error("Message missing " + Overseer.QUEUE_OPERATION + ":" + message);
      return null;
    }

    if (log.isDebugEnabled()) log.debug("Queue operation is {}", operation);

    if (log.isDebugEnabled()) log.debug("Process message {} {}", message, operation);

    if (log.isDebugEnabled()) log.debug("Enqueue message {}", operation);
    try {
      return zkController.getOverseer().getZkStateWriter().enqueueUpdate(null, message, true);
    } catch (NullPointerException e) {
      log.info("Overseer is stopped, won't process message " + zkController.getOverseer());
      return null;
    }

  }


  public Future run() {
    if (log.isDebugEnabled()) log.debug("OverseerTaskExecutorTask, going to process message {}", message);

    try {
      return processQueueItem(message);
    } catch (Exception e) {
      log.error("Failed to process message " + message, e);
    }
    return null;
  }

  public static class WriteTask implements Runnable {
    CoreContainer coreContainer;

    public WriteTask(CoreContainer coreContainer, ZkStateWriter zkStateWriter) {
      this.coreContainer = coreContainer;
    }

    @Override
    public void run() {
      try {
        coreContainer.getZkController().getOverseer().getZkStateWriter().writePendingUpdates();
      } catch (NullPointerException e) {
        if (log.isDebugEnabled()) log.debug("Won't write pending updates, zkStateWriter=null");
      } catch (Exception e) {
        log.error("Failed to process pending updates", e);
      }
    }
  }
}
