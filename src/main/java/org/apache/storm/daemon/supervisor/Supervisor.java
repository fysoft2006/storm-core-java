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
package org.apache.storm.daemon.supervisor;

import java.io.File;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.storm.ClojureClass;
import org.apache.storm.config.ConfigUtil;
import org.apache.storm.daemon.supervisor.event.EventManager;
import org.apache.storm.daemon.supervisor.event.EventManagerImp;
import org.apache.storm.daemon.supervisor.heartbeat.SupervisorHeartbeat;
import org.apache.storm.daemon.supervisor.sync.AddEventFn;
import org.apache.storm.daemon.supervisor.sync.SyncProcessesEvent;
import org.apache.storm.daemon.supervisor.sync.SynchronizeSupervisorEvent;
import org.apache.storm.daemon.worker.DefaultKillFn;
import org.apache.storm.http.HttpServer;
import org.apache.storm.util.CoreUtil;
import org.apache.storm.util.thread.AsyncLoopThread;
import org.apache.storm.util.thread.RunnableCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import backtype.storm.Config;
import backtype.storm.messaging.IContext;
import backtype.storm.scheduler.ISupervisor;
import backtype.storm.utils.Utils;

@ClojureClass(className = "backtype.storm.daemon.supervisor")
public class Supervisor {
  private static Logger LOG = LoggerFactory.getLogger(Supervisor.class);
  @SuppressWarnings("rawtypes")
  private static Map conf;
  private HttpServer httpServer;
  private SupervisorData supervisorData;
  private SupervisorManager supervisorManager = null;

  @SuppressWarnings({ "rawtypes" })
  @ClojureClass(className = "backtype.storm.daemon.supervisor#mk-supervisor")
  public SupervisorManager mkSupervisor(Map conf, IContext sharedContext,
      ISupervisor isupervisor) throws Exception {
    LOG.info("Starting Supervisor with conf " + conf);
    isupervisor.prepare(conf, ConfigUtil.supervisorIsupervisorDir(conf));
    FileUtils.cleanDirectory(new File(ConfigUtil.supervisorTmpDir(conf)));

    supervisorData = new SupervisorData(conf, sharedContext, isupervisor);

    EventManager eventManager = new EventManagerImp(false);
    EventManager processesEventManager = new EventManagerImp(false);
    SyncProcessesEvent syncProcesses = new SyncProcessesEvent(supervisorData);
    RunnableCallback synchronizeSupervisor =
        new SynchronizeSupervisorEvent(supervisorData, syncProcesses,
            processesEventManager, eventManager);

    SupervisorHeartbeat heartbeatFn =
        new SupervisorHeartbeat(conf, supervisorData, isupervisor);
    // should synchronize supervisor so it doesn't launch anything after being
    // down (optimization)
    AsyncLoopThread heartbeatThread =
        new AsyncLoopThread(heartbeatFn, true, new DefaultKillFn(),
            Thread.MAX_PRIORITY, true, "supervisor-heart-beat-thread");

    // This isn't strictly necessary, but it doesn't hurt and ensures that the
    // machine stays up to date even if callbacks don't all work exactly right
    RunnableCallback synchronizeSupervisorPusher =
        new AddEventFn(eventManager, synchronizeSupervisor,
            supervisorData.getActive(), 10);
    AsyncLoopThread synchronizeSupervisorPusherThread =
        new AsyncLoopThread(synchronizeSupervisorPusher, true,
            new DefaultKillFn(), Thread.MAX_PRIORITY, true,
            "synchronize-supervisor-pusher-thread");

    int syncFrequence =
        CoreUtil.parseInt(
            conf.get(Config.SUPERVISOR_MONITOR_FREQUENCY_SECS), 3);
    RunnableCallback syncProcessesPusher =
        new AddEventFn(processesEventManager, syncProcesses,
            supervisorData.getActive(), syncFrequence);
    AsyncLoopThread syncProcessesPusherThread =
        new AsyncLoopThread(syncProcessesPusher, true, new DefaultKillFn(),
            Thread.MAX_PRIORITY, true, "sync-processes-pusher-thread");

    AsyncLoopThread[] asyncLoopThreads =
        { heartbeatThread, synchronizeSupervisorPusherThread,
            syncProcessesPusherThread };
    LOG.info("Starting supervisor with id " + supervisorData.getSupervisorId()
        + " at host " + supervisorData.getMyHostname());
    return new SupervisorManager(supervisorData, eventManager,
        processesEventManager, httpServer, asyncLoopThreads);
  }

  @ClojureClass(className = "backtype.storm.daemon.supervisor#kill-supervisor")
  public void killSupervisor(SupervisorManager supervisor) {
    supervisor.shutdown();
  }

  @SuppressWarnings("unchecked")
  @ClojureClass(className = "backtype.storm.daemon.supervisor#launch")
  public void launch(ISupervisor supervisor) throws Exception {
    conf = Utils.readStormConfig();
    ConfigUtil.validateDistributedMode(conf);
    supervisorManager = mkSupervisor(conf, null, supervisor);

    addShutdownHookWithForceKillInOneSec();
  }

  /**
   * adds the user supplied function as a shutdown hook for cleanup. Also adds a
   * function that sleeps for a second and then sends kill -9 to process to
   * avoid any zombie process in case cleanup function hangs.
   */
  @ClojureClass(className = "backtype.storm.util#add-shutdown-hook-with-force-kill-in-1-sec")
  private void addShutdownHookWithForceKillInOneSec() {
    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        if (supervisorManager != null) {
          supervisorManager.shutdown();
        }
      }
    });

    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        try {
          CoreUtil.sleepSecs(1);
          Runtime.getRuntime().halt(20);
        } catch (InterruptedException e) {
          LOG.error(CoreUtil.stringifyError(e));
        }
      }
    });
  }

  @ClojureClass(className = "backtype.storm.daemon.supervisor#main")
  public static void main(String[] args) {
    Supervisor instance = new Supervisor();
    ISupervisor isupervisor = new StandaloneSupervisor();
    try {
      instance.launch(isupervisor);
    } catch (Exception e) {
      LOG.error("Failed to start supervisor\n", CoreUtil.stringifyError(e));
      e.printStackTrace();
      System.exit(1);
    }
  }
}
