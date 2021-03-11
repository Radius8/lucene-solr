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

package org.apache.solr.handler.admin;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.solr.SolrTestUtil;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.params.CollectionAdminParams;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.TimeOut;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.core.SolrInfoBean;
import org.apache.solr.metrics.SolrMetricManager;
import org.apache.solr.metrics.SolrMetricsContext;
import org.apache.solr.util.LogLevel;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rrd4j.core.RrdDb;

/**
 *
 */
@LogLevel("org.apache.solr.cloud=DEBUG")
public class MetricsHistoryHandlerTest extends SolrCloudTestCase {

  private volatile static SolrCloudManager cloudManager;
  private volatile static SolrMetricManager metricManager;
  private volatile static TimeSource timeSource;
  private volatile static SolrClient solrClient;
  private volatile static boolean simulated;
  private volatile static int SPEED;

  private volatile static MetricsHistoryHandler handler;
  private volatile static MetricsHandler metricsHandler;

  private static MBeanServer TEST_MBEAN_SERVER;

  @BeforeClass
  public static void beforeClass() throws Exception {
    System.setProperty("solr.disableDefaultJmxReporter", "false");
    System.setProperty("solr.enableMetrics", "true");

    TEST_MBEAN_SERVER = MBeanServerFactory.createMBeanServer();
    simulated = TEST_NIGHTLY ? random().nextBoolean() : true;
    Map<String, Object> args = new HashMap<>();
    args.put(MetricsHistoryHandler.SYNC_PERIOD_PROP, 1);
    args.put(MetricsHistoryHandler.COLLECT_PERIOD_PROP, 1);
    args.put(MetricsHistoryHandler.ENABLE_NODES_PROP, "true");
    configureCluster(1)
        .addConfig("conf", SolrTestUtil.configset("cloud-minimal"))
        .configure();

    cloudManager = cluster.getJettySolrRunner(0).getCoreContainer().getZkController().getSolrCloudManager();
    metricManager = cluster.getJettySolrRunner(0).getCoreContainer().getMetricManager();
    solrClient = cluster.getSolrClient();
    metricsHandler = new MetricsHandler(metricManager);
    handler = new MetricsHistoryHandler(cluster.getJettySolrRunner(0).getNodeName(), metricsHandler, solrClient, cloudManager, args,
        null);
    SolrMetricsContext solrMetricsContext = new SolrMetricsContext(metricManager, SolrInfoBean.Group.node.toString(), "");
    handler.initializeMetrics(solrMetricsContext, CommonParams.METRICS_HISTORY_PATH);
    solrMetricsContext.getMetricsSnapshot();
    SPEED = 1;
    timeSource = cloudManager.getTimeSource();

    // create .system collection
    CollectionAdminRequest.Create create = CollectionAdminRequest.createCollection(CollectionAdminParams.SYSTEM_COLL,
        "conf", 1, 1).waitForFinalState(true);
    create.process(solrClient);
  }

  @AfterClass
  public static void teardown() throws Exception {
    if (handler != null) {
      handler.close();
    }
    if (null != TEST_MBEAN_SERVER) {
      MBeanServerFactory.releaseMBeanServer(TEST_MBEAN_SERVER);
      TEST_MBEAN_SERVER = null;
    }
    handler = null;
    metricsHandler = null;
    cloudManager = null;
    metricManager = null;
    solrClient = null;
  }

  @Test
  //Commented 14-Oct-2018 @BadApple(bugUrl="https://issues.apache.org/jira/browse/SOLR-12028") // added 15-Sep-2018
  public void testBasic() throws Exception {
    List<Pair<String, Long>> list = handler.getFactory().list(100);

    if (list.size() < 2) {
      TimeOut timeout = new TimeOut(5000, TimeUnit.MILLISECONDS, TimeSource.NANO_TIME);
      while (!timeout.hasTimedOut() && list.size() < 2) {
        Thread.sleep(10);
        list = handler.getFactory().list(100);
      }
    }
    // solr.jvm, solr.node, solr.collection..system

    assertEquals(list.toString(), 2, list.size());
    for (Pair<String, Long> p : list) {
      RrdDb db = RrdDb.getBuilder().setPath(MetricsHistoryHandler.URI_PREFIX + p.first()).setReadOnly(true).setBackendFactory( handler.getFactory()).setUsePool(true).build();
      int dsCount = db.getDsCount();
      int arcCount = db.getArcCount();
      assertTrue("dsCount should be > 0, was " + dsCount, dsCount > 0);
      assertEquals("arcCount", 5, arcCount);
      db.close();
    }
  }
}
