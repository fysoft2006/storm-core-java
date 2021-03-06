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
package org.apache.storm.daemon.nimbus.transitions;

import java.util.Map;

import org.apache.storm.ClojureClass;
import org.apache.storm.cluster.StormStatus;
import org.apache.storm.daemon.nimbus.NimbusData;
import org.apache.storm.daemon.nimbus.NimbusUtils;
import org.apache.storm.util.CoreUtil;
import org.apache.storm.util.thread.BaseCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.Config;

@ClojureClass(className = "backtype.storm.daemon.nimbus#kill-transition")
public class KillTransitionCallback extends BaseCallback {
  private static Logger LOG = LoggerFactory
      .getLogger(KillTransitionCallback.class);
  public static final int DEFAULT_DELAY_SECONDS = 5;
  private NimbusData nimbusData;
  private String stormId;

  public KillTransitionCallback(NimbusData nimbusData, String stormId) {
    this.nimbusData = nimbusData;
    this.stormId = stormId;
  }

  @SuppressWarnings("rawtypes")
  @Override
  public <T> Object execute(T... args) {
    Integer delaySecs = null;
    if (args == null || args.length == 0 || args[0] == null) {
      try {
        Map stormConf =
            NimbusUtils.readStormConf(nimbusData.getConf(), stormId);
        delaySecs =
            CoreUtil.parseInt(
                stormConf.get(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS), 30);
      } catch (Exception e) {
        LOG.info("Failed to get topology configuration " + stormId);
        delaySecs = KillTransitionCallback.DEFAULT_DELAY_SECONDS;
      }
    } else {
      delaySecs = Integer.valueOf(String.valueOf(args[0]));
    }

    if (delaySecs == null || delaySecs < 0) {
      delaySecs = KillTransitionCallback.DEFAULT_DELAY_SECONDS;
    }

    DelayEvent delayEvent =
        new DelayEvent(nimbusData, stormId, delaySecs, StatusType.remove);
    delayEvent.execute();

    return new StormStatus(delaySecs, StatusType.killed);
  }
}
