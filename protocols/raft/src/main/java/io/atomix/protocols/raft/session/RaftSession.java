/*
 * Copyright 2016-present Open Networking Foundation
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
 * limitations under the License
 */
package io.atomix.protocols.raft.session;

import io.atomix.protocols.raft.event.EventType;
import io.atomix.protocols.raft.event.RaftEvent;
import io.atomix.protocols.raft.ReadConsistency;
import io.atomix.protocols.raft.service.ServiceType;
import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.storage.buffer.HeapBytes;

import java.util.function.Function;

/**
 * Provides an interface to communicating with a client via session events.
 * <p>
 * Sessions represent a connection between a single client and all servers in a Raft cluster. Session information
 * is replicated via the Raft consensus algorithm, and clients can safely switch connections between servers without
 * losing their session. All consistency guarantees are provided within the context of a session. Once a session is
 * expired or closed, linearizability, sequential consistency, and other guarantees for events and operations are
 * effectively lost. Session implementations guarantee linearizability for session messages by coordinating between
 * the client and a single server at any given time. This means messages {@link #publish(RaftEvent) published}
 * via the {@link RaftSession} are guaranteed to arrive on the other side of the connection exactly once and in the order
 * in which they are sent by replicated state machines. In the event of a server-to-client message being lost, the
 * message will be resent so long as at least one Raft server is able to communicate with the client and the client's
 * session does not expire while switching between servers.
 * <p>
 * Messages are sent to the other side of the session using the {@link #publish(RaftEvent)} method:
 * <pre>
 *   {@code
 *     session.publish("myEvent", "Hello world!");
 *   }
 * </pre>
 * When the message is published, it will be queued to be sent to the other side of the connection. Raft guarantees
 * that the message will eventually be received by the client unless the session itself times out or is closed.
 */
public interface RaftSession {

  /**
   * Returns the session identifier.
   *
   * @return The session identifier.
   */
  SessionId sessionId();

  /**
   * Returns the session's service name.
   *
   * @return The session's service name.
   */
  String serviceName();

  /**
   * Returns the session's service type.
   *
   * @return The session's service type.
   */
  ServiceType serviceType();

  /**
   * Returns the member identifier to which the session belongs.
   *
   * @return The member to which the session belongs.
   */
  MemberId memberId();

  /**
   * Returns the session's read consistency.
   *
   * @return The session's read consistency.
   */
  ReadConsistency readConsistency();

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  long timeout();

  /**
   * Returns the session state.
   *
   * @return The session state.
   */
  State getState();

  /**
   * Adds a state change listener to the session.
   *
   * @param listener the state change listener to add
   */
  void addListener(RaftSessionEventListener listener);

  /**
   * Removes a state change listener from the session.
   *
   * @param listener the state change listener to remove
   */
  void removeListener(RaftSessionEventListener listener);

  /**
   * Publishes an empty event to the session.
   *
   * @param eventType the event type
   */
  default void publish(EventType eventType) {
    publish(new RaftEvent(eventType, HeapBytes.EMPTY));
  }

  /**
   * Publishes an event to the session.
   *
   * @param eventType the event identifier
   * @param encoder the event value encoder
   * @param event the event value
   * @param <T> the event type
   */
  default <T> void publish(EventType eventType, Function<T, byte[]> encoder, T event) {
    publish(eventType, encoder.apply(event));
  }

  /**
   * Publishes an event to the session.
   *
   * @param eventType the event identifier
   * @param event the event to publish
   * @throws NullPointerException if the event is {@code null}
   */
  default void publish(EventType eventType, byte[] event) {
    publish(new RaftEvent(eventType, event));
  }

  /**
   * Publishes an event to the session.
   *
   * @param event the event to publish
   */
  void publish(RaftEvent event);

  /**
   * Session state enums.
   */
  enum State {
    OPEN(true),
    SUSPICIOUS(true),
    EXPIRED(false),
    CLOSED(false);

    private final boolean active;

    State(boolean active) {
      this.active = active;
    }

    public boolean active() {
      return active;
    }
  }

}
