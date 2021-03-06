/*
 * Copyright 2014-present Open Networking Foundation
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
package io.atomix.cluster;

import io.atomix.event.ListenerService;

import java.util.Set;

/**
 * Service for obtaining information about the individual nodes within
 * the controller cluster.
 */
public interface ClusterService extends ListenerService<ClusterEvent, ClusterEventListener> {

  /**
   * Returns the local controller node.
   *
   * @return local controller node
   */
  Node localNode();

  /**
   * Returns the set of current cluster members.
   *
   * @return set of cluster members
   */
  Set<Node> getNodes();

  /**
   * Returns the specified controller node.
   *
   * @param nodeId controller node identifier
   * @return controller node
   */
  Node getNode(NodeId nodeId);

  /**
   * Returns the availability state of the specified controller node. Note
   * that this does not imply that all the core and application components
   * have been fully activated; only that the node has joined the cluster.
   *
   * @param nodeId controller node identifier
   * @return availability state
   */
  Node.State getState(NodeId nodeId);

}
