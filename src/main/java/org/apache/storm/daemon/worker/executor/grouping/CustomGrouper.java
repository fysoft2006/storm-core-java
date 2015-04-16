/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.daemon.worker.executor.grouping;

import java.util.List;

import org.apache.storm.ClojureClass;

import backtype.storm.generated.GlobalStreamId;
import backtype.storm.grouping.CustomStreamGrouping;
import backtype.storm.task.WorkerTopologyContext;

@ClojureClass(className = "backtype.storm.daemon.executor#mk-custom-grouper")
public class CustomGrouper implements IGrouper {
  private CustomStreamGrouping grouping;

  public CustomGrouper(CustomStreamGrouping grouping,
      WorkerTopologyContext context, String componentId, String streamId,
      List<Integer> targetTasks) {
    this.grouping = grouping;
    this.grouping.prepare(context, new GlobalStreamId(componentId, streamId),
        targetTasks);
  }

  @Override
  @ClojureClass(className = "backtype.storm.daemon.executor#mk-custom-grouper#fn")
  public List<Integer> fn(Integer taskId, List<Object> values) {

    return grouping.chooseTasks(taskId, values);
  }
}
