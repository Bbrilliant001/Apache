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

package org.apache.gobblin.data.management.copy.hive;

import com.google.common.base.Optional;
import org.apache.gobblin.data.management.copy.CopyEntity;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.metrics.event.EventSubmitter;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.util.List;

public class UnpartitionedTableFileSetTest {

    @Test(expectedExceptions = { HiveTableLocationNotMatchException.class })
    public void testHiveTableLocationNotMatchException() throws Exception {
        Path testPath = new Path("/testPath/db/table");
        Path existingTablePath = new Path("/existing/testPath/db/table");
        Table table = new Table("testDb","table1");
        table.setDataLocation(testPath);
        Table existingTargetTable = new Table("testDb","table1");
        existingTargetTable.setDataLocation(existingTablePath);
        HiveDataset hiveDataset = Mockito.mock(HiveDataset.class);
        Mockito.when(hiveDataset.getTable()).thenReturn(table);
        HiveCopyEntityHelper helper = Mockito.mock(HiveCopyEntityHelper.class);
        Mockito.when(helper.getDataset()).thenReturn(hiveDataset);
        Mockito.when(helper.getExistingTargetTable()).thenReturn(Optional.of(existingTargetTable));
        Mockito.when(helper.getTargetTable()).thenReturn(table);
        Mockito.when(helper.getExistingEntityPolicy()).thenReturn(HiveCopyEntityHelper.ExistingEntityPolicy.ABORT);
        MetricContext metricContext = MetricContext.builder("testUnpartitionedTableFileSet").build();
        EventSubmitter eventSubmitter = new EventSubmitter.Builder(metricContext,"loc.nomatch.exp").build();
        Mockito.when(helper.getEventSubmitter()).thenReturn(eventSubmitter);
        UnpartitionedTableFileSet upts = new UnpartitionedTableFileSet("testLocationMatch",hiveDataset,helper);
        List<CopyEntity> copyEntities = (List<CopyEntity>)upts.generateCopyEntities();
    }
}
