/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.grpc.server;

import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcUtil;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.impl.FollowerInfo;
import org.apache.ratis.server.impl.LeaderState;
import org.apache.ratis.server.impl.LogAppender;
import org.apache.ratis.server.impl.RaftServerImpl;
import org.apache.ratis.server.impl.ServerProtoUtils;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.thirdparty.io.grpc.stub.StreamObserver;
import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto;
import org.apache.ratis.proto.RaftProtos.AppendEntriesRequestProto;
import org.apache.ratis.proto.RaftProtos.InstallSnapshotReplyProto;
import org.apache.ratis.proto.RaftProtos.InstallSnapshotRequestProto;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A new log appender implementation using grpc bi-directional stream API.
 */
public class GrpcLogAppender extends LogAppender {
  public static final Logger LOG = LoggerFactory.getLogger(GrpcLogAppender.class);

  private final GrpcService rpcService;
  private final Map<Long, AppendEntriesRequestProto> pendingRequests;
  private final int maxPendingRequestsNum;
  private long callId = 0;
  private volatile boolean firstResponseReceived = false;
  private final boolean installSnapshotEnabled;

  private final TimeDuration requestTimeoutDuration;
  private final TimeoutScheduler scheduler = TimeoutScheduler.newInstance(1);

  private volatile StreamObserver<AppendEntriesRequestProto> appendLogRequestObserver;

  public GrpcLogAppender(RaftServerImpl server, LeaderState leaderState,
                         FollowerInfo f) {
    super(server, leaderState, f);

    this.rpcService = (GrpcService) server.getServerRpc();

    maxPendingRequestsNum = GrpcConfigKeys.Server.leaderOutstandingAppendsMax(
        server.getProxy().getProperties());
    requestTimeoutDuration = RaftServerConfigKeys.Rpc.requestTimeout(server.getProxy().getProperties());
    pendingRequests = new ConcurrentHashMap<>();
    installSnapshotEnabled = RaftServerConfigKeys.Log.Appender.installSnapshotEnabled(
        server.getProxy().getProperties());
  }

  private GrpcServerProtocolClient getClient() throws IOException {
    return rpcService.getProxies().getProxy(getFollowerId());
  }

  private synchronized void resetClient(AppendEntriesRequestProto request) {
    rpcService.getProxies().resetProxy(getFollowerId());
    appendLogRequestObserver = null;
    firstResponseReceived = false;

    // clear the pending requests queue and reset the next index of follower
    final long nextIndex = request != null && request.hasPreviousLog()?
        request.getPreviousLog().getIndex() + 1: follower.getMatchIndex() + 1;
    pendingRequests.clear();
    follower.decreaseNextIndex(nextIndex);
  }

  @Override
  protected void runAppenderImpl() throws IOException {
    boolean shouldAppendLog;
    for(; isAppenderRunning(); mayWait()) {
      shouldAppendLog = true;
      if (shouldSendRequest()) {
        if (installSnapshotEnabled) {
          SnapshotInfo snapshot = shouldInstallSnapshot();
          if (snapshot != null) {
            installSnapshot(snapshot);
            shouldAppendLog = false;
          }
        } else {
          TermIndex installSnapshotNotificationTermIndex = shouldNotifyToInstallSnapshot();
          if (installSnapshotNotificationTermIndex != null) {
            installSnapshot(installSnapshotNotificationTermIndex);
            shouldAppendLog = false;
          }
        }
        if (shouldAppendLog && !shouldWait()) {
          // keep appending log entries or sending heartbeats
          appendLog();
        }
      }
      checkSlowness();
    }

    Optional.ofNullable(appendLogRequestObserver).ifPresent(StreamObserver::onCompleted);
  }

  private long getWaitTimeMs() {
    if (!shouldSendRequest()) {
      return getHeartbeatRemainingTime(); // No requests, wait until heartbeat
    } else if (shouldWait()) {
      return halfMinTimeoutMs; // Should wait for a short time
    }
    return 0L;
  }

  private void mayWait() {
    // use lastSend time instead of lastResponse time
    final long waitTimeMs = getWaitTimeMs();
    if (waitTimeMs <= 0L) {
      return;
    }

    synchronized(this) {
      try {
        LOG.trace("{}: wait {}ms", this, waitTimeMs);
        wait(waitTimeMs);
      } catch(InterruptedException ie) {
        LOG.warn(this + ": Wait interrupted by " + ie);
      }
    }
  }

  @Override
  protected boolean shouldSendRequest() {
    return appendLogRequestObserver == null || super.shouldSendRequest();
  }

  /** @return true iff not received first response or queue is full. */
  private boolean shouldWait() {
    final int size = pendingRequests.size();
    if (size == 0) {
      return false;
    }
    return !firstResponseReceived || size >= maxPendingRequestsNum;
  }

  private void appendLog() throws IOException {
    final AppendEntriesRequestProto pending;
    final StreamObserver<AppendEntriesRequestProto> s;
    synchronized (this) {
      // prepare and enqueue the append request. note changes on follower's
      // nextIndex and ops on pendingRequests should always be associated
      // together and protected by the lock
      pending = createRequest(callId++);
      if (pending == null) {
        return;
      }
      pendingRequests.put(pending.getServerRequest().getCallId(), pending);
      increaseNextIndex(pending);
      if (appendLogRequestObserver == null) {
        appendLogRequestObserver = getClient().appendEntries(new AppendLogResponseHandler());
      }
      s = appendLogRequestObserver;
    }

    if (isAppenderRunning()) {
      sendRequest(pending, s);
    }
  }

  private void sendRequest(AppendEntriesRequestProto request,
      StreamObserver<AppendEntriesRequestProto> s) {
    CodeInjectionForTesting.execute(GrpcService.GRPC_SEND_SERVER_REQUEST,
        server.getId(), null, request);

    s.onNext(request);
    scheduler.onTimeout(requestTimeoutDuration, () -> timeoutAppendRequest(request), LOG,
        () -> "Timeout check failed for append entry request: " + request);
    follower.updateLastRpcSendTime();
  }

  private void timeoutAppendRequest(AppendEntriesRequestProto request) {
    AppendEntriesRequestProto pendingRequest = pendingRequests.remove(request.getServerRequest().getCallId());
    if (pendingRequest != null) {
      LOG.warn( "{}: appendEntries Timeout, request={}", this, ServerProtoUtils.toString(pendingRequest));
    }
  }

  private void increaseNextIndex(AppendEntriesRequestProto request) {
    final int count = request.getEntriesCount();
    if (count > 0) {
      follower.increaseNextIndex(request.getEntries(count - 1).getIndex() + 1);
    }
  }

  /**
   * StreamObserver for handling responses from the follower
   */
  private class AppendLogResponseHandler
      implements StreamObserver<AppendEntriesReplyProto> {
    /**
     * After receiving a appendEntries reply, do the following:
     * 1. If the reply is success, update the follower's match index and submit
     *    an event to leaderState
     * 2. If the reply is NOT_LEADER, step down
     * 3. If the reply is INCONSISTENCY, increase/ decrease the follower's next
     *    index based on the response
     */
    @Override
    public void onNext(AppendEntriesReplyProto reply) {
      final AppendEntriesRequestProto request = pendingRequests.remove(reply.getServerReply().getCallId());
      if (LOG.isDebugEnabled()) {
        LOG.debug("{}: received {} reply {}, request={}",
            follower.getName(), firstResponseReceived? "a": "the first",
            ServerProtoUtils.toString(reply), ServerProtoUtils.toString(request));
      }

      try {
        onNextImpl(request, reply);
      } catch(Throwable t) {
        LOG.error("Failed onNext request=" + ServerProtoUtils.toString(request)
            + ", reply=" + ServerProtoUtils.toString(reply), t);
      }
    }

    private void onNextImpl(AppendEntriesRequestProto request, AppendEntriesReplyProto reply) {
      // update the last rpc time
      follower.updateLastRpcResponseTime();

      if (!firstResponseReceived) {
        firstResponseReceived = true;
      }
      if (request == null) {
        // The request is already handled (probably timeout), ignore the reply.
        LOG.warn("{}: Request not found, ignoring reply: {}", this, ServerProtoUtils.toString(reply));
        return;
      }

      switch (reply.getResult()) {
        case SUCCESS:
          updateCommitIndex(reply.getFollowerCommit());
          if (checkAndUpdateMatchIndex(request)) {
            submitEventOnSuccessAppend();
          }
          break;
        case NOT_LEADER:
          if (checkResponseTerm(reply.getTerm())) {
            return;
          }
          break;
        case INCONSISTENCY:
          updateNextIndex(reply.getNextIndex());
          break;
        default:
          throw new IllegalStateException("Unexpected reply result: " + reply.getResult());
      }
      notifyAppend();
    }

    /**
     * for now we simply retry the first pending request
     */
    @Override
    public void onError(Throwable t) {
      if (!isAppenderRunning()) {
        LOG.info("{} is stopped", GrpcLogAppender.this);
        return;
      }
      GrpcUtil.warn(LOG, () -> getFollower().getName() + ": Failed appendEntries", t);

      long callId = GrpcUtil.getCallId(t);
      resetClient(pendingRequests.remove(callId));
    }

    @Override
    public void onCompleted() {
      LOG.info("{}: follower responses appendEntries COMPLETED", getFollower().getName());
      resetClient(null);
    }
  }

  private boolean checkAndUpdateMatchIndex(AppendEntriesRequestProto request) {
    final int n = request.getEntriesCount();
    final long newMatchIndex = n == 0? request.getPreviousLog().getIndex(): request.getEntries(n - 1).getIndex();
    return follower.updateMatchIndex(newMatchIndex);
  }

  private synchronized void updateNextIndex(long replyNextIndex) {
    pendingRequests.clear();
    follower.updateNextIndex(replyNextIndex);
  }

  private class InstallSnapshotResponseHandler
      implements StreamObserver<InstallSnapshotReplyProto> {
    private final Queue<Integer> pending;
    private final AtomicBoolean done = new AtomicBoolean(false);

    InstallSnapshotResponseHandler() {
      pending = new LinkedList<>();
    }

    synchronized void addPending(InstallSnapshotRequestProto request) {
      pending.offer(request.getSnapshotChunk().getRequestIndex());
    }

    synchronized void removePending(InstallSnapshotReplyProto reply) {
      final Integer index = pending.poll();
      Objects.requireNonNull(index, "index == null");
      Preconditions.assertTrue(index == reply.getRequestIndex());
    }

    boolean isDone() {
      return done.get();
    }

    void close() {
      done.set(true);
      GrpcLogAppender.this.notifyAppend();
    }

    synchronized boolean hasAllResponse() {
      return pending.isEmpty();
    }

    @Override
    public void onNext(InstallSnapshotReplyProto reply) {
      LOG.debug("{}: received {} response", getFollower().getName(), firstResponseReceived? "a": "the first");

      // update the last rpc time
      follower.updateLastRpcResponseTime();

      if (!firstResponseReceived) {
        firstResponseReceived = true;
      }

      switch (reply.getResult()) {
        case SUCCESS:
        case IN_PROGRESS:
          removePending(reply);
          break;
        case ALREADY_INSTALLED:
          long followerLatestSnapshotIndex = reply.getSnapshotIndex();
          LOG.info("{}: Latest snapshot index on follower {} is {}.",
              server.getId(), follower.getPeer(), followerLatestSnapshotIndex);
          follower.setSnapshotIndex(followerLatestSnapshotIndex);
          removePending(reply);
          break;
        case NOT_LEADER:
          checkResponseTerm(reply.getTerm());
          break;
        case CONF_MISMATCH:
          LOG.error("{}: Configuration Mismatch ({}): Leader {} has it set to {} but follower {} has it set to {}",
              server.getId(), RaftServerConfigKeys.Log.Appender.INSTALL_SNAPSHOT_ENABLED_KEY,
              server.getId(), installSnapshotEnabled, getFollowerId(), !installSnapshotEnabled);
        case UNRECOGNIZED:
          break;
      }
    }

    @Override
    public void onError(Throwable t) {
      if (!isAppenderRunning()) {
        LOG.info("{} is stopped", GrpcLogAppender.this);
        return;
      }
      LOG.info("{}: got error when installing snapshot: {}", getFollower().getName(), t);
      resetClient(null);
      close();
    }

    @Override
    public void onCompleted() {
      LOG.info("{}: follower responses installSnapshot Completed", getFollower().getName());
      close();
    }
  }

  /**
   * Send installSnapshot request to Follower with a snapshot.
   * @param snapshot the snapshot to be sent to Follower
   */
  private void installSnapshot(SnapshotInfo snapshot) {
    LOG.info("{}: follower's next index is {}, log's start index is {}, will install snapshot",
        getFollower().getName(), follower.getNextIndex(), raftLog.getStartIndex());

    final InstallSnapshotResponseHandler responseHandler = new InstallSnapshotResponseHandler();
    StreamObserver<InstallSnapshotRequestProto> snapshotRequestObserver = null;
    final String requestId = UUID.randomUUID().toString();
    try {
      snapshotRequestObserver = getClient().installSnapshot(responseHandler);
      for (InstallSnapshotRequestProto request :
          new SnapshotRequestIter(snapshot, requestId)) {
        if (isAppenderRunning()) {
          snapshotRequestObserver.onNext(request);
          follower.updateLastRpcSendTime();
          responseHandler.addPending(request);
        } else {
          break;
        }
      }
      snapshotRequestObserver.onCompleted();
    } catch (Exception e) {
      LOG.warn("{}: failed to install snapshot {}: {}", this, snapshot.getFiles(), e);
      if (snapshotRequestObserver != null) {
        snapshotRequestObserver.onError(e);
      }
      return;
    }

    synchronized (this) {
      while (isAppenderRunning() && !responseHandler.isDone()) {
        try {
          wait();
        } catch (InterruptedException ignored) {
        }
      }
    }

    if (responseHandler.hasAllResponse()) {
      follower.setSnapshotIndex(snapshot.getTermIndex().getIndex());
      LOG.info("{}: install snapshot-{} successfully", getFollower().getName(), snapshot.getTermIndex().getIndex());
    }
  }

  /**
   * Send installSnapshot request to Follower with only a notification that a snapshot needs to be installed.
   * @param firstAvailableLogTermIndex the first available log's index on the Leader
   */
  private void installSnapshot(TermIndex firstAvailableLogTermIndex) {
    LOG.info("{}: follower {}'s next index is {}, log's start index is {}, " +
            "need to notify follower to install snapshot",
        server.getId(), follower.getPeer(), follower.getNextIndex(),
        raftLog.getStartIndex());

    final InstallSnapshotResponseHandler responseHandler = new InstallSnapshotResponseHandler();
    StreamObserver<InstallSnapshotRequestProto> snapshotRequestObserver = null;
    // prepare and enqueue the notify install snapshot request.
    InstallSnapshotRequestProto request =
        createInstallSnapshotNotificationRequest(firstAvailableLogTermIndex);
    try {
      snapshotRequestObserver = getClient().installSnapshot(responseHandler);
      snapshotRequestObserver.onNext(request);
      follower.updateLastRpcSendTime();
      responseHandler.addPending(request);
      snapshotRequestObserver.onCompleted();
    } catch (Exception e) {
      LOG.warn("{} failed to notify follower {} to install snapshot. " +
          "Exception: {}", this, follower, e);
      if (snapshotRequestObserver != null) {
        snapshotRequestObserver.onError(e);
      }
      return;
    }

    synchronized (this) {
      if (isAppenderRunning() && !responseHandler.isDone()) {
        try {
          wait();
        } catch (InterruptedException ignored) {
        }
      }
    }
  }

  /**
   * Should the Leader notify the Follower to install the snapshot through
   * its own State Machine.
   * @return the first available log's start term index
   */
  private TermIndex shouldNotifyToInstallSnapshot() {
    if (follower.getNextIndex() < raftLog.getStartIndex()) {
      // The Leader does not have the logs from the Follower's last log
      // index onwards. And install snapshot is disabled. So the Follower
      // should be notified to install the latest snapshot through its
      // State Machine.
      return raftLog.getTermIndex(raftLog.getStartIndex());
    }
    return null;
  }
}
