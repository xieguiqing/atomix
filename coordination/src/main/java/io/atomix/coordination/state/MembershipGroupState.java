/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.coordination.state;

import io.atomix.copycat.client.session.Session;
import io.atomix.copycat.server.Commit;
import io.atomix.copycat.server.session.SessionListener;
import io.atomix.resource.ResourceStateMachine;

import java.time.Duration;
import java.util.*;

/**
 * Group state machine.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class MembershipGroupState extends ResourceStateMachine implements SessionListener {
  private final Set<Session> sessions = new HashSet<>();
  private final Map<Long, Commit<MembershipGroupCommands.Join>> members = new HashMap<>();
  private final Map<Long, Map<String, Commit<MembershipGroupCommands.SetProperty>>> properties = new HashMap<>();
  private final Queue<Commit<MembershipGroupCommands.Join>> candidates = new ArrayDeque<>();
  private Commit<MembershipGroupCommands.Join> leader;
  private long term;

  @Override
  public void register(Session session) {

  }

  @Override
  public void unregister(Session session) {

  }

  @Override
  public void expire(Session session) {

  }

  @Override
  public void close(Session session) {
    Map<Long, Commit<MembershipGroupCommands.Join>> left = new HashMap<>();

    // Remove the session from the sessions set.
    sessions.remove(session);

    // Iterate through all open members.
    Iterator<Map.Entry<Long, Commit<MembershipGroupCommands.Join>>> iterator = members.entrySet().iterator();
    while (iterator.hasNext()) {
      // If the member is associated with the closed session, remove it from the members list.
      Commit<MembershipGroupCommands.Join> commit = iterator.next().getValue();
      if (commit.session().equals(session)) {
        iterator.remove();

        long memberId = commit.index();

        // Clear properties associated with the member.
        Map<String, Commit<MembershipGroupCommands.SetProperty>> properties = this.properties.remove(memberId);
        if (properties != null) {
          properties.values().forEach(Commit::close);
        }

        candidates.remove(commit);
        left.put(commit.index(), commit);
      }
    }

    // If the current leader is one of the members that left the cluster, resign the leadership
    // and elect a new leader. This must be done after all the removed members are removed from internal state.
    if (leader != null && left.containsKey(leader.index())) {
      resignLeader(false);
      incrementTerm();
      electLeader();
    }

    // Iterate through the remaining sessions and publish a leave event for each removed member.
    sessions.forEach(s -> {
      if (s.state() == Session.State.OPEN) {
        for (Map.Entry<Long, Commit<MembershipGroupCommands.Join>> entry : left.entrySet()) {
          s.publish("leave", entry.getValue().index());
        }
      }
    });

    // Close the commits for the members that left the group.
    for (Map.Entry<Long, Commit<MembershipGroupCommands.Join>> entry : left.entrySet()) {
      entry.getValue().close();
    }
  }

  /**
   * Increments the term.
   */
  private void incrementTerm() {
    term = context.index();
    for (Session session : sessions) {
      if (session.state() == Session.State.OPEN) {
        session.publish("term", term);
      }
    }
  }

  /**
   * Resigns a leader.
   */
  private void resignLeader(boolean toCandidate) {
    if (leader != null) {
      for (Session session : sessions) {
        if (session.state() == Session.State.OPEN) {
          session.publish("resign", leader.index());
        }
      }

      if (toCandidate) {
        candidates.add(leader);
      }
      leader = null;
    }
  }

  /**
   * Elects a leader if necessary.
   */
  private void electLeader() {
    Commit<MembershipGroupCommands.Join> commit = candidates.poll();
    if (commit != null) {
      leader = commit;
      for (Session session : sessions) {
        if (session.state() == Session.State.OPEN) {
          session.publish("elect", leader.index());
        }
      }
    }
  }

  /**
   * Applies join commits.
   */
  public long join(Commit<MembershipGroupCommands.Join> commit) {
    try {
      long memberId = commit.index();

      members.put(memberId, commit);
      candidates.add(commit);

      // Iterate through available sessions and publish a join event to each session.
      for (Session session : sessions) {
        if (session.state() == Session.State.OPEN) {
          session.publish("join", memberId);
        }
      }

      // If the term has not yet been set, set it.
      if (term == 0) {
        incrementTerm();
      }

      // If a leader has not yet been elected, elect one.
      if (leader == null) {
        electLeader();
      }

      return memberId;
    } catch (Exception e) {
      commit.close();
      throw e;
    }
  }

  /**
   * Applies leave commits.
   */
  public void leave(Commit<MembershipGroupCommands.Leave> commit) {
    try {
      long memberId = commit.operation().member();

      // Remove the member from the members list.
      Commit<MembershipGroupCommands.Join> join = members.remove(memberId);
      if (join != null) {

        // Remove any properties set for the member.
        Map<String, Commit<MembershipGroupCommands.SetProperty>> properties = this.properties.remove(memberId);
        if (properties != null) {
          properties.values().forEach(Commit::close);
        }

        // Remove the join from the leader queue.
        candidates.remove(join);

        // If the leaving member was the leader, increment the term and elect a new leader.
        if (leader.index() == memberId) {
          resignLeader(false);
          incrementTerm();
          electLeader();
        }

        // Publish a leave event to all listening sessions.
        for (Session session : sessions) {
          if (session.state() == Session.State.OPEN) {
            session.publish("leave", memberId);
          }
        }

        // Close the join commit to ensure it's garbage collected.
        join.close();
      }
    } finally {
      commit.close();
    }
  }

  /**
   * Handles a listen commit.
   */
  public Set<Long> listen(Commit<MembershipGroupCommands.Listen> commit) {
    try {
      sessions.add(commit.session());
      return new HashSet<>(members.keySet());
    } finally {
      commit.close();
    }
  }

  /**
   * Handles a resign commit.
   */
  public void resign(Commit<MembershipGroupCommands.Resign> commit) {
    try {
      if (leader.index() == commit.operation().member()) {
        resignLeader(true);
        incrementTerm();
        electLeader();
      }
    } finally {
      commit.close();
    }
  }

  /**
   * Handles a set property commit.
   */
  public void setProperty(Commit<MembershipGroupCommands.SetProperty> commit) {
    Map<String, Commit<MembershipGroupCommands.SetProperty>> properties = this.properties.get(commit.operation().member());
    if (properties == null) {
      properties = new HashMap<>();
      this.properties.put(commit.operation().member(), properties);
    }
    properties.put(commit.operation().property(), commit);
  }

  /**
   * Handles a set property commit.
   */
  public Object getProperty(Commit<MembershipGroupCommands.GetProperty> commit) {
    try {
      Map<String, Commit<MembershipGroupCommands.SetProperty>> properties = this.properties.get(commit.operation().member());
      if (properties != null) {
        Commit<MembershipGroupCommands.SetProperty> value = properties.get(commit.operation().property());
        return value != null ? value.operation().value() : null;
      }
      return null;
    } finally {
      commit.close();
    }
  }

  /**
   * Handles a set property commit.
   */
  public void removeProperty(Commit<MembershipGroupCommands.RemoveProperty> commit) {
    try {
      Map<String, Commit<MembershipGroupCommands.SetProperty>> properties = this.properties.get(commit.operation().member());
      if (properties != null) {
        Commit<MembershipGroupCommands.SetProperty> previous = properties.remove(commit.operation().property());
        if (previous != null) {
          previous.close();
        }

        if (properties.isEmpty()) {
          this.properties.remove(commit.operation().member());
        }
      }
    } finally {
      commit.close();
    }
  }

  /**
   * Handles a send commit.
   */
  public void send(Commit<MembershipGroupCommands.Send> commit) {
    try {
      Commit<MembershipGroupCommands.Join> join = members.get(commit.operation().member());
      if (join == null) {
        throw new IllegalArgumentException("unknown member: " + commit.operation().member());
      }

      join.session().publish("message", new MembershipGroupCommands.Message(commit.operation().member(), commit.operation().topic(), commit.operation().message()));
    } finally {
      commit.close();
    }
  }

  /**
   * Handles a schedule commit.
   */
  public void schedule(Commit<MembershipGroupCommands.Schedule> commit) {
    try {
      if (!members.containsKey(commit.operation().member())) {
        throw new IllegalArgumentException("unknown member: " + commit.operation().member());
      }

      executor.schedule(Duration.ofMillis(commit.operation().delay()), () -> {
        Commit<MembershipGroupCommands.Join> member = members.get(commit.operation().member());
        if (member != null) {
          member.session().publish("execute", commit.operation().callback());
        }
        commit.close();
      });
    } catch (Exception e){
      commit.close();
      throw e;
    }
  }

  /**
   * Handles an execute commit.
   */
  public void execute(Commit<MembershipGroupCommands.Execute> commit) {
    try {
      Commit<MembershipGroupCommands.Join> member = members.get(commit.operation().member());
      if (member == null) {
        throw new IllegalArgumentException("unknown member: " + commit.operation().member());
      }

      member.session().publish("execute", commit.operation().callback());
    } finally {
      commit.close();
    }
  }

  @Override
  public void delete() {
    members.values().forEach(Commit::close);
    members.clear();
  }

}
