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
package org.apache.gobblin.service.modules.scheduler;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.io.Files;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.io.File;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.runtime.JobException;
import org.apache.gobblin.runtime.api.FlowSpec;
import org.apache.gobblin.runtime.api.Spec;
import org.apache.gobblin.runtime.api.SpecCatalogListener;
import org.apache.gobblin.runtime.app.ServiceBasedAppLauncher;
import org.apache.gobblin.runtime.spec_catalog.AddSpecResponse;
import org.apache.gobblin.runtime.spec_catalog.FlowCatalog;
import org.apache.gobblin.runtime.spec_catalog.TopologyCatalog;
import org.apache.gobblin.scheduler.SchedulerService;
import org.apache.gobblin.service.ServiceConfigKeys;
import org.apache.gobblin.service.modules.flow.MockedSpecCompiler;
import org.apache.gobblin.service.modules.flow.SpecCompiler;
import org.apache.gobblin.service.modules.orchestration.Orchestrator;
import org.apache.gobblin.runtime.spec_catalog.FlowCatalogTest;
import org.apache.gobblin.testing.AssertWithBackoff;
import org.apache.gobblin.util.ConfigUtils;

import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.mockito.stubbing.Answer;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.apache.gobblin.runtime.spec_catalog.FlowCatalog.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;


public class GobblinServiceJobSchedulerTest {
  private static final String TEST_GROUP_NAME = "testGroup";
  private static final String TEST_FLOW_NAME = "testFlow";
  private static final String TEST_SCHEDULE = "0 1/0 * ? * *";
  private static final String TEST_TEMPLATE_URI = "FS:///templates/test.template";

  /**
   * Test whenever JobScheduler is calling setActive, the FlowSpec is loading into scheduledFlowSpecs (eventually)
   */
  @Test
  public void testJobSchedulerInit() throws Exception {
    // Mock a FlowCatalog.
    File specDir = Files.createTempDir();

    Properties properties = new Properties();
    properties.setProperty(FLOWSPEC_STORE_DIR_KEY, specDir.getAbsolutePath());
    FlowCatalog flowCatalog = new FlowCatalog(ConfigUtils.propertiesToConfig(properties));
    SpecCatalogListener mockListener = Mockito.mock(SpecCatalogListener.class);
    when(mockListener.getName()).thenReturn(ServiceConfigKeys.GOBBLIN_SERVICE_JOB_SCHEDULER_LISTENER_CLASS);
    when(mockListener.onAddSpec(any())).thenReturn(new AddSpecResponse(""));
    flowCatalog.addListener(mockListener);
    ServiceBasedAppLauncher serviceLauncher = new ServiceBasedAppLauncher(properties, "GaaSJobSchedulerTest");

    serviceLauncher.addService(flowCatalog);
    serviceLauncher.start();

    FlowSpec flowSpec0 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec0"));
    FlowSpec flowSpec1 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec1"));

    flowCatalog.put(flowSpec0, true);
    flowCatalog.put(flowSpec1, true);

    Assert.assertEquals(flowCatalog.getSpecs().size(), 2);

    Orchestrator mockOrchestrator = Mockito.mock(Orchestrator.class);

    // Mock a GaaS scheduler.
    TestGobblinServiceJobScheduler scheduler = new TestGobblinServiceJobScheduler("testscheduler",
        ConfigFactory.empty(), Optional.of(flowCatalog), null, mockOrchestrator, null);

    SpecCompiler mockCompiler = Mockito.mock(SpecCompiler.class);
    Mockito.when(mockOrchestrator.getSpecCompiler()).thenReturn(mockCompiler);
    Mockito.doAnswer((Answer<Void>) a -> {
      scheduler.isCompilerHealthy = true;
      return null;
    }).when(mockCompiler).awaitHealthy();

    scheduler.setActive(true);

    AssertWithBackoff.create().timeoutMs(20000).maxSleepMs(2000).backoffFactor(2)
        .assertTrue(new Predicate<Void>() {
          @Override
          public boolean apply(Void input) {
            Map<String, Spec> scheduledFlowSpecs = scheduler.scheduledFlowSpecs;
            if (scheduledFlowSpecs != null && scheduledFlowSpecs.size() == 2) {
              return scheduler.scheduledFlowSpecs.containsKey("spec0") &&
                  scheduler.scheduledFlowSpecs.containsKey("spec1");
            } else {
              return false;
            }
          }
        }, "Waiting all flowSpecs to be scheduled");
  }

  @Test
  public void testDisableFlowRunImmediatelyOnStart()
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty(ConfigurationKeys.FLOW_RUN_IMMEDIATELY, "true");
    properties.setProperty(ConfigurationKeys.JOB_SCHEDULE_KEY, TEST_SCHEDULE);
    properties.setProperty(ConfigurationKeys.JOB_GROUP_KEY, TEST_GROUP_NAME);
    properties.setProperty(ConfigurationKeys.JOB_NAME_KEY, TEST_FLOW_NAME);
    Config config = ConfigFactory.parseProperties(properties);
    FlowSpec spec = FlowSpec.builder().withTemplate(new URI(TEST_TEMPLATE_URI)).withVersion("version")
        .withConfigAsProperties(properties).withConfig(config).build();
    FlowSpec modifiedSpec = (FlowSpec) GobblinServiceJobScheduler.disableFlowRunImmediatelyOnStart(spec);
    for (URI templateURI : modifiedSpec.getTemplateURIs().get()) {
      Assert.assertEquals(templateURI.toString(), TEST_TEMPLATE_URI);
    }
    Assert.assertEquals(modifiedSpec.getVersion(), "version");
    Config modifiedConfig = modifiedSpec.getConfig();
    Assert.assertFalse(modifiedConfig.getBoolean(ConfigurationKeys.FLOW_RUN_IMMEDIATELY));
    Assert.assertEquals(modifiedConfig.getString(ConfigurationKeys.JOB_GROUP_KEY), TEST_GROUP_NAME);
    Assert.assertEquals(modifiedConfig.getString(ConfigurationKeys.JOB_NAME_KEY), TEST_FLOW_NAME);
  }

  /**
   * Test that flowSpecs that throw compilation errors do not block the scheduling of other flowSpecs
   */
  @Test
  public void testJobSchedulerInitWithFailedSpec() throws Exception {
    // Mock a FlowCatalog.
    File specDir = Files.createTempDir();

    Properties properties = new Properties();
    properties.setProperty(FLOWSPEC_STORE_DIR_KEY, specDir.getAbsolutePath());
    FlowCatalog flowCatalog = new FlowCatalog(ConfigUtils.propertiesToConfig(properties));
    ServiceBasedAppLauncher serviceLauncher = new ServiceBasedAppLauncher(properties, "GaaSJobSchedulerTest");

    // Assume that the catalog can store corrupted flows
    SpecCatalogListener mockListener = Mockito.mock(SpecCatalogListener.class);
    when(mockListener.getName()).thenReturn(ServiceConfigKeys.GOBBLIN_SERVICE_JOB_SCHEDULER_LISTENER_CLASS);
    when(mockListener.onAddSpec(any())).thenReturn(new AddSpecResponse(""));
    flowCatalog.addListener(mockListener);

    serviceLauncher.addService(flowCatalog);
    serviceLauncher.start();

    FlowSpec flowSpec0 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec0"),
        MockedSpecCompiler.UNCOMPILABLE_FLOW);
    FlowSpec flowSpec1 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec1"));
    FlowSpec flowSpec2 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec2"));

    // Ensure that these flows are scheduled
    flowCatalog.put(flowSpec0, true);
    flowCatalog.put(flowSpec1, true);
    flowCatalog.put(flowSpec2, true);

    Assert.assertEquals(flowCatalog.getSpecs().size(), 3);

    Orchestrator mockOrchestrator = Mockito.mock(Orchestrator.class);

    // Mock a GaaS scheduler.
    TestGobblinServiceJobScheduler scheduler = new TestGobblinServiceJobScheduler("testscheduler",
        ConfigFactory.empty(), Optional.of(flowCatalog), null, mockOrchestrator, null);

    SpecCompiler mockCompiler = Mockito.mock(SpecCompiler.class);
    Mockito.when(mockOrchestrator.getSpecCompiler()).thenReturn(mockCompiler);
    Mockito.doAnswer((Answer<Void>) a -> {
      scheduler.isCompilerHealthy = true;
      return null;
    }).when(mockCompiler).awaitHealthy();

    scheduler.setActive(true);

    AssertWithBackoff.create().timeoutMs(20000).maxSleepMs(2000).backoffFactor(2)
        .assertTrue(new Predicate<Void>() {
          @Override
          public boolean apply(Void input) {
            Map<String, Spec> scheduledFlowSpecs = scheduler.scheduledFlowSpecs;
            if (scheduledFlowSpecs != null && scheduledFlowSpecs.size() == 2) {
              return scheduler.scheduledFlowSpecs.containsKey("spec1") &&
                  scheduler.scheduledFlowSpecs.containsKey("spec2");
            } else {
              return false;
            }
          }
        }, "Waiting all flowSpecs to be scheduled");
  }

  /**
   * Test that flowSpecs that throw compilation errors do not block the scheduling of other flowSpecs
   */
  @Test
  public void testJobSchedulerUnschedule() throws Exception {
    // Mock a FlowCatalog.
    File specDir = Files.createTempDir();

    Properties properties = new Properties();
    properties.setProperty(FLOWSPEC_STORE_DIR_KEY, specDir.getAbsolutePath());
    FlowCatalog flowCatalog = new FlowCatalog(ConfigUtils.propertiesToConfig(properties));
    ServiceBasedAppLauncher serviceLauncher = new ServiceBasedAppLauncher(properties, "GaaSJobSchedulerTest");

    // Assume that the catalog can store corrupted flows
    SpecCatalogListener mockListener = Mockito.mock(SpecCatalogListener.class);
    when(mockListener.getName()).thenReturn(ServiceConfigKeys.GOBBLIN_SERVICE_JOB_SCHEDULER_LISTENER_CLASS);
    when(mockListener.onAddSpec(any())).thenReturn(new AddSpecResponse(""));
    flowCatalog.addListener(mockListener);

    serviceLauncher.addService(flowCatalog);
    serviceLauncher.start();

    FlowSpec flowSpec0 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec0"));
    FlowSpec flowSpec1 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec1"));
    FlowSpec flowSpec2 = FlowCatalogTest.initFlowSpec(specDir.getAbsolutePath(), URI.create("spec2"));

    // Ensure that these flows are scheduled
    flowCatalog.put(flowSpec0, true);
    flowCatalog.put(flowSpec1, true);
    flowCatalog.put(flowSpec2, true);

    Assert.assertEquals(flowCatalog.getSpecs().size(), 3);

    Orchestrator mockOrchestrator = Mockito.mock(Orchestrator.class);
    SchedulerService schedulerService = new SchedulerService(new Properties());
    // Mock a GaaS scheduler.
    TestGobblinServiceJobScheduler scheduler = new TestGobblinServiceJobScheduler("testscheduler",
        ConfigFactory.empty(), Optional.of(flowCatalog), null, mockOrchestrator, schedulerService );

    schedulerService.startAsync().awaitRunning();
    scheduler.startUp();
    SpecCompiler mockCompiler = Mockito.mock(SpecCompiler.class);
    Mockito.when(mockOrchestrator.getSpecCompiler()).thenReturn(mockCompiler);
    Mockito.doAnswer((Answer<Void>) a -> {
      scheduler.isCompilerHealthy = true;
      return null;
    }).when(mockCompiler).awaitHealthy();

    scheduler.setActive(true);

    AssertWithBackoff.create().timeoutMs(20000).maxSleepMs(2000).backoffFactor(2)
        .assertTrue(new Predicate<Void>() {
          @Override
          public boolean apply(Void input) {
            Map<String, Spec> scheduledFlowSpecs = scheduler.scheduledFlowSpecs;
            if (scheduledFlowSpecs != null && scheduledFlowSpecs.size() == 3) {
              return scheduler.scheduledFlowSpecs.containsKey("spec0") &&
                  scheduler.scheduledFlowSpecs.containsKey("spec1") &&
                  scheduler.scheduledFlowSpecs.containsKey("spec2");
            } else {
              return false;
            }
          }
        }, "Waiting all flowSpecs to be scheduled");

    // set scheduler to be inactive and unschedule flows
    scheduler.setActive(false);
    Collection<Invocation> invocations = Mockito.mockingDetails(mockOrchestrator).getInvocations();

    for (Invocation invocation: invocations) {
      // ensure that orchestrator is not calling remove
      Assert.assertFalse(invocation.getMethod().getName().equals("remove"));
    }

    Assert.assertEquals(scheduler.scheduledFlowSpecs.size(), 0);
    Assert.assertEquals(schedulerService.getScheduler().getJobGroupNames().size(), 0);
  }

  class TestGobblinServiceJobScheduler extends GobblinServiceJobScheduler {
    public boolean isCompilerHealthy = false;
    private boolean hasScheduler = false;

    public TestGobblinServiceJobScheduler(String serviceName, Config config,
        Optional<FlowCatalog> flowCatalog, Optional<TopologyCatalog> topologyCatalog, Orchestrator orchestrator,
        SchedulerService schedulerService) throws Exception {
      super(serviceName, config, Optional.absent(), flowCatalog, topologyCatalog, orchestrator, schedulerService, Optional.absent());
      if (schedulerService != null) {
        hasScheduler = true;
      }
    }

    /**
     * Override super method to only add spec into in-memory containers but not scheduling anything to simplify testing.
     */
    @Override
    public AddSpecResponse onAddSpec(Spec addedSpec) {
      String flowName = (String) ((FlowSpec) addedSpec).getConfigAsProperties().get(ConfigurationKeys.FLOW_NAME_KEY);
      if (flowName.equals(MockedSpecCompiler.UNCOMPILABLE_FLOW)) {
        throw new RuntimeException("Could not compile flow");
      }
      super.scheduledFlowSpecs.put(addedSpec.getUri().toString(), addedSpec);
      if (hasScheduler) {
        try {
          scheduleJob(((FlowSpec) addedSpec).getConfigAsProperties(), null);
        } catch (JobException e) {
          throw new RuntimeException(e);
        }
      }
      // Check that compiler is healthy at time of scheduling flows
      Assert.assertTrue(isCompilerHealthy);
      return new AddSpecResponse(addedSpec.getDescription());
    }
  }
}