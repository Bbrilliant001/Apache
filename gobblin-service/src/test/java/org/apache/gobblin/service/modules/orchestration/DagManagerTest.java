/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gobblin.service.modules.orchestration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.FileUtils;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import org.apache.gobblin.config.ConfigBuilder;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.instrumented.Instrumented;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.runtime.api.JobSpec;
import org.apache.gobblin.runtime.api.SpecExecutor;
import org.apache.gobblin.runtime.spec_executorInstance.MockedSpecExecutor;
import org.apache.gobblin.service.ExecutionStatus;
import org.apache.gobblin.service.modules.flowgraph.Dag;
import org.apache.gobblin.service.modules.flowgraph.Dag.DagNode;
import org.apache.gobblin.service.modules.spec.JobExecutionPlan;
import org.apache.gobblin.service.modules.spec.JobExecutionPlanDagFactory;
import org.apache.gobblin.service.monitoring.JobStatus;
import org.apache.gobblin.service.monitoring.JobStatusRetriever;
import org.apache.gobblin.util.ConfigUtils;


public class DagManagerTest {
  private final String dagStateStoreDir = "/tmp/dagManagerTest/dagStateStore";
  private DagStateStore _dagStateStore;
  private JobStatusRetriever _jobStatusRetriever;
  private DagManager.DagManagerThread _dagManagerThread;
  private LinkedBlockingQueue<Dag<JobExecutionPlan>> queue;
  private LinkedBlockingQueue<String> cancelQueue;
  private LinkedBlockingQueue<String> resumeQueue;
  private Map<DagNode<JobExecutionPlan>, Dag<JobExecutionPlan>> jobToDag;
  private Map<String, LinkedList<DagNode<JobExecutionPlan>>> dagToJobs;
  private Map<String, Dag<JobExecutionPlan>> dags;
  private Set<String> failedDagIds;
  private static long START_SLA_DEFAULT = 15 * 60 * 1000;

  @BeforeClass
  public void setUp() throws Exception {
    FileUtils.deleteDirectory(new File(this.dagStateStoreDir));
    Config config = ConfigFactory.empty()
        .withValue(FSDagStateStore.DAG_STATESTORE_DIR, ConfigValueFactory.fromAnyRef(this.dagStateStoreDir));

    this._dagStateStore = new FSDagStateStore(config, new HashMap<>());
    DagStateStore failedDagStateStore = new InMemoryDagStateStore();
    this._jobStatusRetriever = Mockito.mock(JobStatusRetriever.class);
    this.queue = new LinkedBlockingQueue<>();
    this.cancelQueue = new LinkedBlockingQueue<>();
    this.resumeQueue = new LinkedBlockingQueue<>();
    MetricContext metricContext = Instrumented.getMetricContext(ConfigUtils.configToState(ConfigFactory.empty()), getClass());
    this._dagManagerThread = new DagManager.DagManagerThread(_jobStatusRetriever, _dagStateStore, failedDagStateStore, queue, cancelQueue,
        resumeQueue, true, 5, new HashMap<>(), new HashSet<>(), metricContext.contextAwareMeter("successMeter"),
        metricContext.contextAwareMeter("failedMeter"), START_SLA_DEFAULT);

    Field jobToDagField = DagManager.DagManagerThread.class.getDeclaredField("jobToDag");
    jobToDagField.setAccessible(true);
    this.jobToDag = (Map<DagNode<JobExecutionPlan>, Dag<JobExecutionPlan>>) jobToDagField.get(this._dagManagerThread);

    Field dagToJobsField = DagManager.DagManagerThread.class.getDeclaredField("dagToJobs");
    dagToJobsField.setAccessible(true);
    this.dagToJobs = (Map<String, LinkedList<DagNode<JobExecutionPlan>>>) dagToJobsField.get(this._dagManagerThread);

    Field dagsField = DagManager.DagManagerThread.class.getDeclaredField("dags");
    dagsField.setAccessible(true);
    this.dags = (Map<String, Dag<JobExecutionPlan>>) dagsField.get(this._dagManagerThread);

    Field failedDagIdsField = DagManager.DagManagerThread.class.getDeclaredField("failedDagIds");
    failedDagIdsField.setAccessible(true);
    this.failedDagIds = (Set<String>) failedDagIdsField.get(this._dagManagerThread);
  }

  /**
   * Create a {@link Dag <JobExecutionPlan>}.
   * @return a Dag.
   */
  static Dag<JobExecutionPlan> buildDag(String id, Long flowExecutionId, String flowFailureOption, boolean flag)
      throws URISyntaxException {
    int numNodes = (flag) ? 3 : 5;
    return buildDag(id, flowExecutionId, flowFailureOption, numNodes);
  }

  static Dag<JobExecutionPlan> buildDag(String id, Long flowExecutionId, String flowFailureOption, int numNodes)
      throws URISyntaxException {
    List<JobExecutionPlan> jobExecutionPlans = new ArrayList<>();

    for (int i = 0; i < numNodes; i++) {
      String suffix = Integer.toString(i);
      Config jobConfig = ConfigBuilder.create().
          addPrimitive(ConfigurationKeys.FLOW_GROUP_KEY, "group" + id).
          addPrimitive(ConfigurationKeys.FLOW_NAME_KEY, "flow" + id).
          addPrimitive(ConfigurationKeys.FLOW_EXECUTION_ID_KEY, flowExecutionId).
          addPrimitive(ConfigurationKeys.JOB_GROUP_KEY, "group" + id).
          addPrimitive(ConfigurationKeys.JOB_NAME_KEY, "job" + suffix).
          addPrimitive(ConfigurationKeys.FLOW_FAILURE_OPTION, flowFailureOption).build();
      if ((i == 1) || (i == 2)) {
        jobConfig = jobConfig.withValue(ConfigurationKeys.JOB_DEPENDENCIES, ConfigValueFactory.fromAnyRef("job0"));
      } else if (i == 3) {
        jobConfig = jobConfig.withValue(ConfigurationKeys.JOB_DEPENDENCIES, ConfigValueFactory.fromAnyRef("job1"));
      } else if (i == 4) {
        jobConfig = jobConfig.withValue(ConfigurationKeys.JOB_DEPENDENCIES, ConfigValueFactory.fromAnyRef("job2"));
      }
      JobSpec js = JobSpec.builder("test_job" + suffix).withVersion(suffix).withConfig(jobConfig).
          withTemplate(new URI("job" + suffix)).build();
      SpecExecutor specExecutor = MockedSpecExecutor.createDummySpecExecutor(new URI("job" + i));
      JobExecutionPlan jobExecutionPlan = new JobExecutionPlan(js, specExecutor);
      jobExecutionPlans.add(jobExecutionPlan);
    }
    return new JobExecutionPlanDagFactory().createDag(jobExecutionPlans);
  }

  static Iterator<JobStatus> getMockJobStatus(String flowName, String flowGroup, Long flowExecutionId, String jobGroup, String jobName, String eventName) {
    return getMockJobStatus(flowName, flowGroup, flowExecutionId, jobGroup, jobName, eventName, false, flowExecutionId + 10);
  }

  private static Iterator<JobStatus> getMockJobStatus(String flowName, String flowGroup, Long flowExecutionId, String jobGroup, String jobName, String eventName, boolean shouldRetry) {
    return getMockJobStatus(flowName, flowGroup, flowExecutionId, jobGroup, jobName, eventName, shouldRetry, flowExecutionId + 10);
  }
  private static Iterator<JobStatus> getMockJobStatus(String flowName, String flowGroup,  Long flowExecutionId, String jobGroup, String jobName, String eventName, boolean shouldRetry, Long orchestratedTime) {
    return Iterators.singletonIterator(JobStatus.builder().flowName(flowName).flowGroup(flowGroup).jobGroup(jobGroup).jobName(jobName).flowExecutionId(flowExecutionId).
        message("Test message").eventName(eventName).startTime(flowExecutionId + 10).shouldRetry(shouldRetry).orchestratedTime(orchestratedTime).build());
  }

  @Test
  public void testSuccessfulDag() throws URISyntaxException, IOException {
    long flowExecutionId = System.currentTimeMillis();
    String flowGroupId = "0";
    String flowGroup = "group" + flowGroupId;
    String flowName = "flow" + flowGroupId;
    String jobName0 = "job0";
    String jobName1 = "job1";
    String jobName2 = "job2";

    Dag<JobExecutionPlan> dag = buildDag(flowGroupId, flowExecutionId, "FINISH_RUNNING", true);
    String dagId = DagManagerUtils.generateDagId(dag);

    //Add a dag to the queue of dags
    this.queue.offer(dag);
    Iterator<JobStatus> jobStatusIterator1 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator2 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator3 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator4 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator5 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator6 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator7 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));

    Mockito.when(_jobStatusRetriever.getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(),
        Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
        thenReturn(jobStatusIterator1).
        thenReturn(jobStatusIterator2).
        thenReturn(jobStatusIterator3).
        thenReturn(jobStatusIterator4).
        thenReturn(jobStatusIterator5).
        thenReturn(jobStatusIterator6).
        thenReturn(jobStatusIterator7);

    //Run the thread once. Ensure the first job is running
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 1);
    Assert.assertTrue(this.jobToDag.containsKey(dag.getStartNodes().get(0)));
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 1);
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getStartNodes().get(0)));
    Assert.assertEquals(this.dags.get(dagId).getNodes().get(0).getValue().getCurrentAttempts(), 1);

    //Run the thread 2nd time. Ensure the job0 is complete and job1 and job2 are submitted.
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 2);
    Assert.assertTrue(this.jobToDag.containsKey(dag.getEndNodes().get(0)));
    Assert.assertTrue(this.jobToDag.containsKey(dag.getEndNodes().get(1)));
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 2);
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getEndNodes().get(0)));
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getEndNodes().get(1)));

    //Run the thread 3rd time. Ensure job1 and job2 are running.
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 2);
    Assert.assertTrue(this.jobToDag.containsKey(dag.getEndNodes().get(0)));
    Assert.assertTrue(this.jobToDag.containsKey(dag.getEndNodes().get(1)));
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 2);
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getEndNodes().get(0)));
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getEndNodes().get(1)));

    //Run the thread 4th time. One of the jobs is completed.
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 1);
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 1);

    //Run the thread again. Ensure all jobs completed and dag is cleaned up.
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 0);
    Assert.assertEquals(this.jobToDag.size(), 0);
    Assert.assertEquals(this.dagToJobs.size(), 0);
    Assert.assertEquals(this._dagStateStore.getDags().size(), 0);
  }

  @Test (dependsOnMethods = "testSuccessfulDag")
  public void testFailedDag() throws URISyntaxException, IOException {
    for (String failureOption: Lists.newArrayList("FINISH_RUNNING", "FINISH_ALL_POSSIBLE")) {
      long flowExecutionId = System.currentTimeMillis();
      String flowGroupId = "0";
      String flowGroup = "group" + flowGroupId;
      String flowName = "flow" + flowGroupId;
      String jobName0 = "job0";
      String jobName1 = "job1";
      String jobName2 = "job2";

      Dag<JobExecutionPlan> dag = buildDag(flowGroupId, flowExecutionId, failureOption, false);
      String dagId = DagManagerUtils.generateDagId(dag);

      //Add a dag to the queue of dags
      this.queue.offer(dag);
      Iterator<JobStatus> jobStatusIterator1 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
      Iterator<JobStatus> jobStatusIterator2 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
      Iterator<JobStatus> jobStatusIterator3 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
      Iterator<JobStatus> jobStatusIterator4 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
      Iterator<JobStatus> jobStatusIterator5 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
      Iterator<JobStatus> jobStatusIterator6 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.FAILED));
      Iterator<JobStatus> jobStatusIterator7 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
      Iterator<JobStatus> jobStatusIterator8 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
      Iterator<JobStatus> jobStatusIterator9 =
          getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));


      Mockito.when(_jobStatusRetriever
          .getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
          thenReturn(jobStatusIterator1).
          thenReturn(jobStatusIterator2).
          thenReturn(jobStatusIterator3).
          thenReturn(jobStatusIterator4).
          thenReturn(jobStatusIterator5).
          thenReturn(jobStatusIterator6).
          thenReturn(jobStatusIterator7).
          thenReturn(jobStatusIterator8).
          thenReturn(jobStatusIterator9);

      //Run the thread once. Ensure the first job is running
      this._dagManagerThread.run();
      Assert.assertEquals(this.dags.size(), 1);
      Assert.assertTrue(this.dags.containsKey(dagId));
      Assert.assertEquals(this.jobToDag.size(), 1);
      Assert.assertTrue(this.jobToDag.containsKey(dag.getStartNodes().get(0)));
      Assert.assertEquals(this.dagToJobs.get(dagId).size(), 1);
      Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getStartNodes().get(0)));

      //Run the thread 2nd time. Ensure the job0 is complete and job1 and job2 are submitted.
      this._dagManagerThread.run();
      Assert.assertEquals(this.dags.size(), 1);
      Assert.assertTrue(this.dags.containsKey(dagId));
      Assert.assertEquals(this.jobToDag.size(), 2);
      Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(1)));
      Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(2)));
      Assert.assertEquals(this.dagToJobs.get(dagId).size(), 2);
      Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(1)));
      Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(2)));

      //Run the thread 3rd time. Ensure the job0 is complete and job1 and job2 are running.
      this._dagManagerThread.run();
      Assert.assertEquals(this.dags.size(), 1);
      Assert.assertTrue(this.dags.containsKey(dagId));
      Assert.assertEquals(this.jobToDag.size(), 2);
      Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(1)));
      Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(2)));
      Assert.assertEquals(this.dagToJobs.get(dagId).size(), 2);
      Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(1)));
      Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(2)));

      //Run the thread 4th time.
      this._dagManagerThread.run();

      if ("FINISH_RUNNING".equals(failureOption)) {
        //One of the jobs is failed; so the dag is failed and all state is cleaned up.
        Assert.assertEquals(this.dags.size(), 0);
        Assert.assertEquals(this.jobToDag.size(), 0);
        Assert.assertEquals(this.dagToJobs.size(), 0);
        Assert.assertEquals(this._dagStateStore.getDags().size(), 0);
      } else {
        //One of the jobs is failed; but with finish_all_possible, some jobs can continue running.
        for (int i = 0; i < 3; i++) {
          Assert.assertEquals(this.dags.size(), 1);
          Assert.assertEquals(this.jobToDag.size(), 1);
          Assert.assertEquals(this.dagToJobs.get(dagId).size(), 1);
          this._dagManagerThread.run();
        }
        //Ensure the state is cleaned up.
        Assert.assertEquals(this.dags.size(), 0);
        Assert.assertEquals(this.jobToDag.size(), 0);
        Assert.assertEquals(this.dagToJobs.size(), 0);
        Assert.assertEquals(this._dagStateStore.getDags().size(), 0);
      }
    }
  }

  @Test (dependsOnMethods = "testFailedDag")
  public void testResumeDag() throws URISyntaxException {
    long flowExecutionId = System.currentTimeMillis();
    String flowGroupId = "0";
    String flowGroup = "group" + flowGroupId;
    String flowName = "flow" + flowGroupId;
    String jobName0 = "job0";
    String jobName1 = "job1";
    String jobName2 = "job2";

    Dag<JobExecutionPlan> dag = buildDag(flowGroupId, flowExecutionId, "FINISH_RUNNING", true);
    String dagId = DagManagerUtils.generateDagId(dag);

    //Add a dag to the queue of dags
    this.queue.offer(dag);
    Iterator<JobStatus> jobStatusIterator1 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator2 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator3 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator4 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator5 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator6 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.FAILED));
    Iterator<JobStatus> jobStatusIterator7 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, "NA_KEY", "NA_KEY", String.valueOf(ExecutionStatus.PENDING_RESUME));
    Iterator<JobStatus> jobStatusIterator8 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator9 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator10 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.PENDING_RESUME));
    Iterator<JobStatus> jobStatusIterator11 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator12 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));

    Mockito.when(_jobStatusRetriever
        .getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
        thenReturn(jobStatusIterator1).
        thenReturn(jobStatusIterator2).
        thenReturn(jobStatusIterator3).
        thenReturn(jobStatusIterator4).
        thenReturn(jobStatusIterator5).
        thenReturn(jobStatusIterator6).
        thenReturn(jobStatusIterator7).
        thenReturn(jobStatusIterator8).
        thenReturn(jobStatusIterator9).
        thenReturn(jobStatusIterator10).
        thenReturn(jobStatusIterator11).
        thenReturn(jobStatusIterator12);

    // Run thread until job2 fails
    for (int i = 0; i < 4; i++) {
      this._dagManagerThread.run();
    }

    Assert.assertTrue(this.failedDagIds.contains(dagId));

    // Resume dag
    this.resumeQueue.offer(dagId);

    // Job2 rerunning
    this._dagManagerThread.run();
    Assert.assertFalse(this.failedDagIds.contains(dagId));
    Assert.assertTrue(this.dags.containsKey(dagId));

    // Verify the current attempt number
    Assert.assertEquals(dag.getNodes().get(2).getValue().getCurrentAttempts(), 1);

    // Job2 complete
    this._dagManagerThread.run();
    Assert.assertFalse(this.failedDagIds.contains(dagId));
    Assert.assertFalse(this.dags.containsKey(dagId));
  }

  @Test (dependsOnMethods = "testResumeDag")
  public void testSucceedAfterRetry() throws Exception {
    long flowExecutionId = System.currentTimeMillis();
    String flowGroupId = "0";
    String flowGroup = "group" + flowGroupId;
    String flowName = "flow" + flowGroupId;
    String jobName0 = "job0";
    String jobName1 = "job1";
    String jobName2 = "job2";

    Dag<JobExecutionPlan> dag = buildDag(flowGroupId, flowExecutionId, "FINISH_RUNNING", true);
    String dagId = DagManagerUtils.generateDagId(dag);

    //Add a dag to the queue of dags
    this.queue.offer(dag);
    Iterator<JobStatus> jobStatusIterator1 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator2 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING), true);
    Iterator<JobStatus> jobStatusIterator3 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator4 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator5 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator6 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator7 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));

    Mockito.when(_jobStatusRetriever.getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(),
        Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
        thenReturn(jobStatusIterator1).
        thenReturn(jobStatusIterator2).
        thenReturn(jobStatusIterator3).
        thenReturn(jobStatusIterator4).
        thenReturn(jobStatusIterator5).
        thenReturn(jobStatusIterator6).
        thenReturn(jobStatusIterator7);

    //Run the thread once. Ensure the first job is running
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 1);
    Assert.assertTrue(this.jobToDag.containsKey(dag.getStartNodes().get(0)));
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 1);
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getStartNodes().get(0)));

    // Second run: check that first job failed and is running again after retry
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 1);
    Assert.assertTrue(this.jobToDag.containsKey(dag.getStartNodes().get(0)));
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 1);
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getStartNodes().get(0)));

    // Third run: check that first job completed successfully and now second and third job are submitted
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 2);
    Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(1)));
    Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(2)));
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 2);
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(1)));
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(2)));

    // Fourth run: second and third job are running
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId));
    Assert.assertEquals(this.jobToDag.size(), 2);
    Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(1)));
    Assert.assertTrue(this.jobToDag.containsKey(dag.getNodes().get(2)));
    Assert.assertEquals(this.dagToJobs.get(dagId).size(), 2);
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(1)));
    Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getNodes().get(2)));

    // Fifth run: second and third job complete and dag is cleaned up
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 0);
    Assert.assertEquals(this.jobToDag.size(), 0);
    Assert.assertEquals(this.dagToJobs.size(), 0);
    Assert.assertEquals(this._dagStateStore.getDags().size(), 0);
  }

  @Test (dependsOnMethods = "testSucceedAfterRetry")
  public void testFailAfterRetry() throws Exception {
    long flowExecutionId = System.currentTimeMillis();
    String flowGroupId = "0";
    String flowGroup = "group" + flowGroupId;
    String flowName = "flow" + flowGroupId;
    String jobName0 = "job0";

    Dag<JobExecutionPlan> dag = buildDag(flowGroupId, flowExecutionId, "FINISH_RUNNING", true);
    String dagId = DagManagerUtils.generateDagId(dag);

    //Add a dag to the queue of dags
    this.queue.offer(dag);
    Iterator<JobStatus> jobStatusIterator1 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator2 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING), true);
    Iterator<JobStatus> jobStatusIterator3 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING), true);
    Iterator<JobStatus> jobStatusIterator4 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING), true);
    Iterator<JobStatus> jobStatusIterator5 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.PENDING_RETRY), true);
    Iterator<JobStatus> jobStatusIterator6 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator7 = getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.FAILED));


    Mockito.when(_jobStatusRetriever.getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(),
        Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
        thenReturn(jobStatusIterator1).
        thenReturn(jobStatusIterator2).
        thenReturn(jobStatusIterator3).
        thenReturn(jobStatusIterator4).
        thenReturn(jobStatusIterator5).
        thenReturn(jobStatusIterator6).
        thenReturn(jobStatusIterator7);

    // Run 4 times, first job fails every time and is retried
    for (int i = 0; i < 4; i++) {
      this._dagManagerThread.run();
      Assert.assertEquals(this.dags.size(), 1);
      Assert.assertTrue(this.dags.containsKey(dagId));
      Assert.assertEquals(this.jobToDag.size(), 1);
      Assert.assertTrue(this.jobToDag.containsKey(dag.getStartNodes().get(0)));
      Assert.assertEquals(this.dagToJobs.get(dagId).size(), 1);
      Assert.assertTrue(this.dagToJobs.get(dagId).contains(dag.getStartNodes().get(0)));
      Assert.assertEquals(dag.getStartNodes().get(0).getValue().getCurrentAttempts(), i + 1);
    }

    // Got a PENDING_RETRY state
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertEquals(this.jobToDag.size(), 1);
    Assert.assertEquals(this.dagToJobs.size(), 1);

    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertEquals(this.jobToDag.size(), 1);
    Assert.assertEquals(this.dagToJobs.size(), 1);

    // Last run fails and dag is cleaned up
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 0);
    Assert.assertEquals(this.jobToDag.size(), 0);
    Assert.assertEquals(this.dagToJobs.size(), 0);
    Assert.assertEquals(this._dagStateStore.getDags().size(), 0);
  }

  @Test (dependsOnMethods = "testFailAfterRetry")
  public void testResumeCancelledDag() throws URISyntaxException, IOException {
    long flowExecutionId = System.currentTimeMillis();
    String flowGroupId = "0";
    String flowGroup = "group" + flowGroupId;
    String flowName = "flow" + flowGroupId;
    String jobName0 = "job0";
    String jobName1 = "job1";
    String jobName2 = "job2";

    Dag<JobExecutionPlan> dag = buildDag(flowGroupId, flowExecutionId, "FINISH_RUNNING", true);
    String dagId = DagManagerUtils.generateDagId(dag);

    //Add a dag to the queue of dags
    this.queue.offer(dag);
    Iterator<JobStatus> jobStatusIterator1 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator2 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator3 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator4 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator5 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator6 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.CANCELLED));
    Iterator<JobStatus> jobStatusIterator7 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, "NA_KEY", "NA_KEY", String.valueOf(ExecutionStatus.PENDING_RESUME));
        Iterator<JobStatus> jobStatusIterator8 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator9 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName1, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));
    Iterator<JobStatus> jobStatusIterator10 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.PENDING_RESUME));
    Iterator<JobStatus> jobStatusIterator11 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.RUNNING));
    Iterator<JobStatus> jobStatusIterator12 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName2, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));

    Mockito.when(_jobStatusRetriever
        .getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
        thenReturn(jobStatusIterator1).
        thenReturn(jobStatusIterator2).
        thenReturn(jobStatusIterator3).
        thenReturn(jobStatusIterator4).
        thenReturn(jobStatusIterator5).
        thenReturn(jobStatusIterator6).
        thenReturn(jobStatusIterator7).
        thenReturn(jobStatusIterator8).
        thenReturn(jobStatusIterator9).
        thenReturn(jobStatusIterator10).
        thenReturn(jobStatusIterator11).
        thenReturn(jobStatusIterator12);

    // Run until job2 cancelled
    for (int i = 0; i < 3; i++) {
      this._dagManagerThread.run();
    }

    // Cancel job2
    this.cancelQueue.offer(dagId);

    this._dagManagerThread.run();
    Assert.assertTrue(this.failedDagIds.contains(dagId));

    // Resume dag
    this.resumeQueue.offer(dagId);

    // Job2 rerunning
    this._dagManagerThread.run();
    Assert.assertFalse(this.failedDagIds.contains(dagId));
    Assert.assertTrue(this.dags.containsKey(dagId));

    // Job2 complete
    this._dagManagerThread.run();
    Assert.assertFalse(this.failedDagIds.contains(dagId));
    Assert.assertFalse(this.dags.containsKey(dagId));
  }

  @Test (dependsOnMethods = "testResumeCancelledDag")
  public void testJobStartSLAKilledDag() throws URISyntaxException, IOException {
    long flowExecutionId = System.currentTimeMillis();
    String flowGroupId = "0";
    String flowGroup = "group" + flowGroupId;
    String flowName = "flow" + flowGroupId;
    String jobName0 = "job0";
    String flowGroupId1 = "1";
    String flowGroup1 = "group" + flowGroupId1;
    String flowName1 = "flow" + flowGroupId1;

    Dag<JobExecutionPlan> dag = buildDag(flowGroupId, flowExecutionId, "FINISH_RUNNING", false);
    Dag<JobExecutionPlan> dag1 = buildDag(flowGroupId1, flowExecutionId+1, "FINISH_RUNNING", false);

    String dagId = DagManagerUtils.generateDagId(dag);
    String dagId1 = DagManagerUtils.generateDagId(dag1);


    //Add a dag to the queue of dags
    this.queue.offer(dag);
    // The start time should be 16 minutes ago, which is past the start SLA so the job should be cancelled
    Iterator<JobStatus> jobStatusIterator1 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.ORCHESTRATED),
            false, flowExecutionId - 16 * 60 * 1000);
    // This is for the second Dag that does not match the SLA so should schedule normally
    Iterator<JobStatus> jobStatusIterator2 =
        getMockJobStatus(flowName1, flowGroup1, flowExecutionId+1, jobName0, flowGroup1, String.valueOf(ExecutionStatus.ORCHESTRATED),
            false, flowExecutionId - 10 * 60 * 1000);
    // Let the first job get reported as cancel due to SLA kill on start and clean up
    Iterator<JobStatus> jobStatusIterator3 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId, jobName0, flowGroup, String.valueOf(ExecutionStatus.CANCELLED),
            false, flowExecutionId - 16 * 60 * 1000);
    // Cleanup the running job that is scheduled normally
    Iterator<JobStatus> jobStatusIterator4 =
        getMockJobStatus(flowName1, flowGroup1, flowExecutionId+1, jobName0, flowGroup1, String.valueOf(ExecutionStatus.COMPLETE));

    Mockito.when(_jobStatusRetriever
        .getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
        thenReturn(jobStatusIterator1).
        thenReturn(jobStatusIterator2).
        thenReturn(jobStatusIterator3).
        thenReturn(jobStatusIterator4);

    // Run the thread once. Ensure the first job is running
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 0);
    // Job should be marked as failed
    Assert.assertTrue(this.failedDagIds.contains(dagId));

    // Next job should succeed as it doesn't exceed SLA
    this.queue.offer(dag1);
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 1);
    Assert.assertEquals(this.jobToDag.size(), 1);
    Assert.assertEquals(this.dagToJobs.size(), 1);
    Assert.assertTrue(this.dags.containsKey(dagId1));

    // Cleanup
    this._dagManagerThread.run();
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 0);
    Assert.assertEquals(this.jobToDag.size(), 0);
    Assert.assertEquals(this.dagToJobs.size(), 0);
  }

  @Test (dependsOnMethods = "testJobStartSLAKilledDag")
  public void testDagManagerWithBadFlowSLAConfig() throws URISyntaxException, IOException {
    long flowExecutionId = System.currentTimeMillis();
    String flowGroup = "group0";
    String flowName = "flow0";
    String jobName = "job0";

    // Create a config with an improperly formatted Flow SLA time e.g. "1h"
    Config jobConfig = ConfigBuilder.create().
        addPrimitive(ConfigurationKeys.FLOW_GROUP_KEY, "group" + flowGroup).
        addPrimitive(ConfigurationKeys.FLOW_NAME_KEY, "flow" + flowName).
        addPrimitive(ConfigurationKeys.FLOW_EXECUTION_ID_KEY, flowExecutionId).
        addPrimitive(ConfigurationKeys.JOB_GROUP_KEY, flowGroup).
        addPrimitive(ConfigurationKeys.JOB_NAME_KEY, jobName).
        addPrimitive(ConfigurationKeys.FLOW_FAILURE_OPTION, "FINISH_RUNNING").
        addPrimitive(ConfigurationKeys.GOBBLIN_FLOW_SLA_TIME, "1h").build();

    List<JobExecutionPlan> jobExecutionPlans = new ArrayList<>();
    JobSpec js = JobSpec.builder("test_job" + jobName).withVersion("0").withConfig(jobConfig).
        withTemplate(new URI(jobName)).build();
    SpecExecutor specExecutor = MockedSpecExecutor.createDummySpecExecutor(new URI(jobName));
    JobExecutionPlan jobExecutionPlan = new JobExecutionPlan(js, specExecutor);
    jobExecutionPlans.add(jobExecutionPlan);
    Dag<JobExecutionPlan> dag = (new JobExecutionPlanDagFactory()).createDag(jobExecutionPlans);
    String dagId = DagManagerUtils.generateDagId(dag);

    //Add a dag to the queue of dags
    this.queue.offer(dag);
    // Job should have been run normally without breaking on SLA check, so we can just mark as completed for status
    Iterator<JobStatus> jobStatusIterator1 =
        getMockJobStatus(flowName, flowGroup, flowExecutionId+1, jobName, flowGroup, String.valueOf(ExecutionStatus.COMPLETE));

    Mockito.when(_jobStatusRetriever
        .getJobStatusesForFlowExecution(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(), Mockito.anyString())).
        thenReturn(jobStatusIterator1);

    // Run the thread once. Job should run without crashing thread on SLA check and cleanup
    this._dagManagerThread.run();
    Assert.assertEquals(this.dags.size(), 0);
    Assert.assertEquals(this.jobToDag.size(), 0);
    Assert.assertEquals(this.dagToJobs.size(), 0);
  }


  @AfterClass
  public void cleanUp() throws Exception {
    FileUtils.deleteDirectory(new File(this.dagStateStoreDir));
  }

  public static class InMemoryDagStateStore implements DagStateStore {
    private final Map<String, Dag<JobExecutionPlan>> dags = new ConcurrentHashMap<>();

    public void writeCheckpoint(Dag<JobExecutionPlan> dag) {
      dags.put(DagManagerUtils.generateDagId(dag), dag);
    }

    public void cleanUp(Dag<JobExecutionPlan> dag) {
      cleanUp(DagManagerUtils.generateDagId(dag));
    }

    public void cleanUp(String dagId) {
      dags.remove(dagId);
    }

    public List<Dag<JobExecutionPlan>> getDags() {
      return new ArrayList<>(dags.values());
    }

    public Dag<JobExecutionPlan> getDag(String dagId) {
      return dags.get(dagId);
    }

    public Set<String> getDagIds() {
      return dags.keySet();
    }
  }
}