/**
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

package kafka.controller

import kafka.cluster.Broker
import org.apache.kafka.common.TopicPartition

import scala.collection.{Map, Seq, Set, mutable}

object ReplicaAssignment {
  def fromOldAndNewReplicas(oldReplicas: Seq[Int], newReplicas: Seq[Int]): ReplicaAssignment = {
    val fullReplicaSet = (newReplicas ++ oldReplicas).distinct
    ReplicaAssignment(
      fullReplicaSet,
      fullReplicaSet.diff(oldReplicas),
      fullReplicaSet.diff(newReplicas)
    )
  }

  def apply(replicas: Seq[Int]): ReplicaAssignment = {
    apply(replicas, Seq.empty, Seq.empty)
  }
}

case class ReplicaAssignment(replicas: Seq[Int],
                             addingReplicas: Seq[Int],
                             removingReplicas: Seq[Int]) {

  lazy val originReplicas: Seq[Int] = replicas.diff(addingReplicas)
  lazy val targetReplicas: Seq[Int] = replicas.diff(removingReplicas)

  def isBeingReassigned: Boolean = {
    addingReplicas.nonEmpty || removingReplicas.nonEmpty
  }

  def reassignTo(newReplicas: Seq[Int]): ReplicaAssignment = {
    ReplicaAssignment.fromOldAndNewReplicas(originReplicas, newReplicas)
  }

  override def toString: String = s"ReplicaAssignment(" +
    s"replicas=${replicas.mkString(",")}, " +
    s"addingReplicas=${addingReplicas.mkString(",")}, " +
    s"removingReplicas=${removingReplicas.mkString(",")})"
}

class ControllerContext {
  val stats = new ControllerStats
  var offlinePartitionCount = 0
  var shuttingDownBrokerIds: Map[Int, Long] = mutable.Map.empty
  var skipShutdownSafetyCheck: Map[Int, Long] = mutable.Map.empty
  private var liveBrokers: Set[Broker] = Set.empty
  private var liveBrokerEpochs: Map[Int, Long] = Map.empty
  var epoch: Int = KafkaController.InitialControllerEpoch
  var epochZkVersion: Int = KafkaController.InitialControllerEpochZkVersion

  var allTopics: Set[String] = Set.empty
  val partitionAssignments = mutable.Map.empty[String, mutable.Map[Int, ReplicaAssignment]]
  val partitionLeadershipInfo = mutable.Map.empty[TopicPartition, LeaderIsrAndControllerEpoch]
  val partitionsBeingReassigned = mutable.Set.empty[TopicPartition]
  val partitionStates = mutable.Map.empty[TopicPartition, PartitionState]
  val replicaStates = mutable.Map.empty[PartitionAndReplica, ReplicaState]
  val replicasOnOfflineDirs: mutable.Map[Int, Set[TopicPartition]] = mutable.Map.empty

  // A map to indicate the explicitly configured value of min.insync.replicas config per corresponding topic.
  val topicMinIsrConfig = mutable.Map.empty[String, Int]

  val topicsToBeDeleted = mutable.Set.empty[String]

  /** The following topicsWithDeletionStarted variable is used to properly update the offlinePartitionCount metric.
   * When a topic is going through deletion, we don't want to keep track of its partition state
   * changes in the offlinePartitionCount metric. This goal means if some partitions of a topic are already
   * in OfflinePartition state when deletion starts, we need to change the corresponding partition
   * states to NonExistentPartition first before starting the deletion.
   *
   * However we can NOT change partition states to NonExistentPartition at the time of enqueuing topics
   * for deletion. The reason is that when a topic is enqueued for deletion, it may be ineligible for
   * deletion due to ongoing partition reassignments. Hence there might be a delay between enqueuing
   * a topic for deletion and the actual start of deletion. In this delayed interval, partitions may still
   * transition to or out of the OfflinePartition state.
   *
   * Hence we decide to change partition states to NonExistentPartition only when the actual deletion have started.
   * For topics whose deletion have actually started, we keep track of them in the following topicsWithDeletionStarted
   * variable. And once a topic is in the topicsWithDeletionStarted set, we are sure there will no longer
   * be partition reassignments to any of its partitions, and only then it's safe to move its partitions to
   * NonExistentPartition state. Once a topic is in the topicsWithDeletionStarted set, we will stop monitoring
   * its partition state changes in the offlinePartitionCount metric
   */
  val topicsWithDeletionStarted = mutable.Set.empty[String]
  val topicsIneligibleForDeletion = mutable.Set.empty[String]

  @volatile var livePreferredControllerIds: Set[Int] = Set.empty

  private def clearTopicsState(): Unit = {
    allTopics = Set.empty
    partitionAssignments.clear()
    partitionLeadershipInfo.clear()
    partitionsBeingReassigned.clear()
    replicasOnOfflineDirs.clear()
    partitionStates.clear()
    offlinePartitionCount = 0
    replicaStates.clear()
  }

  def partitionReplicaAssignment(topicPartition: TopicPartition): Seq[Int] = {
    partitionAssignments.getOrElse(topicPartition.topic, mutable.Map.empty)
      .get(topicPartition.partition) match {
        case Some(partitionAssignment) => partitionAssignment.replicas
        case None => Seq.empty
      }
  }

  def partitionFullReplicaAssignment(topicPartition: TopicPartition): ReplicaAssignment = {
    partitionAssignments.getOrElse(topicPartition.topic, mutable.Map.empty).get(topicPartition.partition) match {
      case Some(partitionAssignment) => partitionAssignment
      case None => ReplicaAssignment(Seq(), Seq(), Seq())
    }
  }

  def updatePartitionReplicaAssignment(topicPartition: TopicPartition, newReplicas: Seq[Int]): Unit = {
    val assignments = partitionAssignments.getOrElseUpdate(topicPartition.topic, mutable.Map.empty)
    val newAssignment = assignments.get(topicPartition.partition) match {
      case Some(partitionAssignment) =>
        ReplicaAssignment(
          newReplicas,
          partitionAssignment.addingReplicas,
          partitionAssignment.removingReplicas
        )
      case None =>
        ReplicaAssignment(
          newReplicas,
          Seq.empty,
          Seq.empty
        )
    }
    updatePartitionFullReplicaAssignment(topicPartition, newAssignment)
  }

  def updatePartitionFullReplicaAssignment(topicPartition: TopicPartition, newAssignment: ReplicaAssignment): Unit = {
    val assignments = partitionAssignments.getOrElseUpdate(topicPartition.topic, mutable.Map.empty)
    assignments.put(topicPartition.partition, newAssignment)
  }

  def partitionReplicaAssignmentForTopic(topic : String): Map[TopicPartition, Seq[Int]] = {
    partitionAssignments.getOrElse(topic, Map.empty).map {
      case (partition, assignment) => (new TopicPartition(topic, partition), assignment.replicas)
    }.toMap
  }

  def partitionFullReplicaAssignmentForTopic(topic : String): Map[TopicPartition, ReplicaAssignment] = {
    partitionAssignments.getOrElse(topic, Map.empty).map {
      case (partition, assignment) => (new TopicPartition(topic, partition), assignment)
    }.toMap
  }

  def allPartitions: Set[TopicPartition] = {
    partitionAssignments.flatMap {
      case (topic, topicReplicaAssignment) => topicReplicaAssignment.map {
        case (partition, _) => new TopicPartition(topic, partition)
      }
    }.toSet
  }

  def setLiveBrokerAndEpochs(brokerAndEpochs: Map[Broker, Long]): Unit = {
    liveBrokers = brokerAndEpochs.keySet
    liveBrokerEpochs =
      brokerAndEpochs map { case (broker, brokerEpoch) => (broker.id, brokerEpoch)}
  }

  def addLiveBrokersAndEpochs(brokerAndEpochs: Map[Broker, Long]): Unit = {
    liveBrokers = liveBrokers ++ brokerAndEpochs.keySet
    liveBrokerEpochs = liveBrokerEpochs ++
      (brokerAndEpochs map { case (broker, brokerEpoch) => (broker.id, brokerEpoch)})

    shuttingDownBrokerIds = shuttingDownBrokerIds.filter(b =>
      liveBrokerEpochs.contains(b._1) && b._2 >= liveBrokerEpochs(b._1))
  }

  def removeLiveBrokers(brokerIds: Set[Int]): Unit = {
    liveBrokers = liveBrokers.filter(broker => !brokerIds.contains(broker.id))
    liveBrokerEpochs = liveBrokerEpochs.filter { case (id, _) => !brokerIds.contains(id) }

    shuttingDownBrokerIds = shuttingDownBrokerIds.filterKeys(brokerIds.contains)
  }

  def updateBrokerMetadata(oldMetadata: Broker, newMetadata: Broker): Unit = {
    liveBrokers -= oldMetadata
    liveBrokers += newMetadata
  }

  def setLivePreferredControllerIds(preferredControllerIds: Set[Int]): Unit = {
    livePreferredControllerIds = preferredControllerIds
  }

  // getter
  def liveBrokerIds: Set[Int] = liveBrokerEpochs.filter(b => b._2 > (shuttingDownBrokerIds.getOrElse(b._1, -1L))).keySet
  def liveOrShuttingDownBrokerIds: Set[Int] = liveBrokerEpochs.keySet
  def liveOrShuttingDownBrokers: Set[Broker] = liveBrokers
  def liveBrokerIdAndEpochs: Map[Int, Long] = liveBrokerEpochs
  def maxBrokerEpoch: Long = liveBrokerEpochs.values.max
  def liveOrShuttingDownBroker(brokerId: Int): Option[Broker] = liveOrShuttingDownBrokers.find(_.id == brokerId)

  def getLivePreferredControllerIds : Set[Int] = livePreferredControllerIds

  def partitionsOnBroker(brokerId: Int): Set[TopicPartition] = {
    partitionAssignments.flatMap {
      case (topic, topicReplicaAssignment) => topicReplicaAssignment.filter {
        case (_, partitionAssignment) => partitionAssignment.replicas.contains(brokerId)
      }.map {
        case (partition, _) => new TopicPartition(topic, partition)
      }
    }.toSet
  }

  def replicasOnBrokers(brokerIds: Set[Int]): Set[PartitionAndReplica] = {
    brokerIds.flatMap { brokerId =>
      partitionAssignments.flatMap {
        case (topic, topicReplicaAssignment) => topicReplicaAssignment.collect {
          case (partition, partitionAssignment) if partitionAssignment.replicas.contains(brokerId) =>
            PartitionAndReplica(new TopicPartition(topic, partition), brokerId)
        }
      }
    }
  }

  def replicasForTopic(topic: String): Set[PartitionAndReplica] = {
    partitionAssignments.getOrElse(topic, mutable.Map.empty).flatMap {
      case (partition, assignment) => assignment.replicas.map(r => PartitionAndReplica(new TopicPartition(topic, partition), r))
    }.toSet
  }

  def partitionsForTopic(topic: String): collection.Set[TopicPartition] = {
    partitionAssignments.getOrElse(topic, mutable.Map.empty).map {
      case (partition, _) => new TopicPartition(topic, partition)
    }.toSet
  }

  def allLiveReplicas(): Set[PartitionAndReplica] = {
    val snapshot = ControllerContextSnapshot(this)
    replicasOnBrokers(liveBrokerIds).filter { partitionAndReplica =>
      snapshot.isReplicaOnline(partitionAndReplica.replica, partitionAndReplica.topicPartition)
    }
  }

  /**
    * Get all online and offline replicas.
    *
    * @return a tuple consisting of first the online replicas and followed by the offline replicas
    */
  def onlineAndOfflineReplicas: (Set[PartitionAndReplica], Set[PartitionAndReplica]) = {
    val onlineReplicas = mutable.Set.empty[PartitionAndReplica]
    val offlineReplicas = mutable.Set.empty[PartitionAndReplica]
    val snapshot = ControllerContextSnapshot(this)
    for ((topic, partitionAssignments) <- partitionAssignments;
         (partitionId, assignment) <- partitionAssignments) {
      val partition = new TopicPartition(topic, partitionId)
      for (replica <- assignment.replicas) {
        val partitionAndReplica = PartitionAndReplica(partition, replica)
        if (snapshot.isReplicaOnline(replica, partition))
          onlineReplicas.add(partitionAndReplica)
        else
          offlineReplicas.add(partitionAndReplica)
      }
    }
    (onlineReplicas, offlineReplicas)
  }

  def replicasForPartition(partitions: collection.Set[TopicPartition]): collection.Set[PartitionAndReplica] = {
    partitions.flatMap { p =>
      val replicas = partitionReplicaAssignment(p)
      replicas.map(PartitionAndReplica(p, _))
    }
  }

  def resetContext(): Unit = {
    topicsToBeDeleted.clear()
    topicsWithDeletionStarted.clear()
    topicsIneligibleForDeletion.clear()
    shuttingDownBrokerIds = Map.empty
    epoch = 0
    epochZkVersion = 0
    clearTopicsState()
    setLiveBrokerAndEpochs(Map.empty)
  }

  def removeTopic(topic: String): Unit = {
    allTopics -= topic
    partitionAssignments.remove(topic)
    partitionStates.foreach {
      case (topicPartition, _) if topicPartition.topic == topic => partitionStates.remove(topicPartition)
      case _ =>
    }
    partitionLeadershipInfo.foreach {
      case (topicPartition, _) if topicPartition.topic == topic => partitionLeadershipInfo.remove(topicPartition)
      case _ =>
    }
  }

  def queueTopicDeletion(topics: Set[String]): Unit = {
    topicsToBeDeleted ++= topics
  }

  def beginTopicDeletion(topics: Set[String]): Unit = {
    topicsWithDeletionStarted ++= topics
  }

  def isTopicDeletionInProgress(topic: String): Boolean = {
    topicsWithDeletionStarted.contains(topic)
  }

  def isTopicQueuedUpForDeletion(topic: String): Boolean = {
    topicsToBeDeleted.contains(topic)
  }

  def isTopicEligibleForDeletion(topic: String): Boolean = {
    topicsToBeDeleted.contains(topic) && !topicsIneligibleForDeletion.contains(topic)
  }

  def topicsQueuedForDeletion: Set[String] = {
    topicsToBeDeleted
  }

  def replicasInState(topic: String, state: ReplicaState): Set[PartitionAndReplica] = {
    replicasForTopic(topic).filter(replica => replicaStates(replica) == state).toSet
  }

  def areAllReplicasInState(topic: String, state: ReplicaState): Boolean = {
    replicasForTopic(topic).forall(replica => replicaStates(replica) == state)
  }

  def isAnyReplicaInState(topic: String, state: ReplicaState): Boolean = {
    replicasForTopic(topic).exists(replica => replicaStates(replica) == state)
  }

  def checkValidReplicaStateChange(replicas: Seq[PartitionAndReplica], targetState: ReplicaState): (Seq[PartitionAndReplica], Seq[PartitionAndReplica]) = {
    replicas.partition(replica => isValidReplicaStateTransition(replica, targetState))
  }

  def checkValidPartitionStateChange(partitions: Seq[TopicPartition], targetState: PartitionState): (Seq[TopicPartition], Seq[TopicPartition]) = {
    partitions.partition(p => isValidPartitionStateTransition(p, targetState))
  }

  def putReplicaState(replica: PartitionAndReplica, state: ReplicaState): Unit = {
    replicaStates.put(replica, state)
  }

  def removeReplicaState(replica: PartitionAndReplica): Unit = {
    replicaStates.remove(replica)
  }

  def putReplicaStateIfNotExists(replica: PartitionAndReplica, state: ReplicaState): Unit = {
    replicaStates.getOrElseUpdate(replica, state)
  }

  def putPartitionState(partition: TopicPartition, targetState: PartitionState): Unit = {
    val currentState = partitionStates.put(partition, targetState).getOrElse(NonExistentPartition)
    updatePartitionStateMetrics(partition, currentState, targetState)
  }

  def excludeDeletingTopicFromOfflinePartitionCount(topic: String): Unit = {
    if (isTopicQueuedUpForDeletion(topic)) {
      offlinePartitionCount = offlinePartitionCount -
        partitionsForTopic(topic).count(partition => partitionState(partition) == OfflinePartition)
    }
  }

  private def updatePartitionStateMetrics(partition: TopicPartition,
                                          currentState: PartitionState,
                                          targetState: PartitionState): Unit = {
    if (!isTopicQueuedUpForDeletion(partition.topic)) {
      if (currentState != OfflinePartition && targetState == OfflinePartition) {
        offlinePartitionCount = offlinePartitionCount + 1
      } else if (currentState == OfflinePartition && targetState != OfflinePartition) {
        offlinePartitionCount = offlinePartitionCount - 1
      }
    }
  }

  def putPartitionStateIfNotExists(partition: TopicPartition, state: PartitionState): Unit = {
    if (partitionStates.getOrElseUpdate(partition, state) == state)
      updatePartitionStateMetrics(partition, NonExistentPartition, state)
  }

  def replicaState(replica: PartitionAndReplica): ReplicaState = {
    replicaStates(replica)
  }

  def partitionState(partition: TopicPartition): PartitionState = {
    partitionStates(partition)
  }

  def partitionsInState(state: PartitionState): Set[TopicPartition] = {
    partitionStates.filter { case (_, s) => s == state }.keySet.toSet
  }

  def partitionsInStates(states: Set[PartitionState]): Set[TopicPartition] = {
    partitionStates.filter { case (_, s) => states.contains(s) }.keySet.toSet
  }

  def partitionsInState(topic: String, state: PartitionState): Set[TopicPartition] = {
    partitionsForTopic(topic).filter { partition => state == partitionState(partition) }.toSet
  }

  def partitionsInStates(topic: String, states: Set[PartitionState]): Set[TopicPartition] = {
    partitionsForTopic(topic).filter { partition => states.contains(partitionState(partition)) }.toSet
  }

  private def isValidReplicaStateTransition(replica: PartitionAndReplica, targetState: ReplicaState): Boolean =
    targetState.validPreviousStates.contains(replicaStates(replica))

  private def isValidPartitionStateTransition(partition: TopicPartition, targetState: PartitionState): Boolean =
    targetState.validPreviousStates.contains(partitionStates(partition))

}

/**
 * The ControllerContextSnapshot is an immutable snapshot of the ControllorContext.
 * The motivation for this class is that we don't need to calculate certain fields
 * repeatedly, including liveBrokerIds and liveOrShuttingDownBrokerIds.
 */
case class ControllerContextSnapshot(controllerContext: ControllerContext) {
  val liveBrokerIds = controllerContext.liveBrokerIds
  val liveOrShuttingDownBrokerIds = controllerContext.liveOrShuttingDownBrokerIds
  val replicasOnOfflineDirs = controllerContext.replicasOnOfflineDirs

  def isReplicaOnline(brokerId: Int, topicPartition: TopicPartition, includeShuttingDownBrokers: Boolean = false): Boolean = {
    val brokerOnline = {
      if (includeShuttingDownBrokers) liveOrShuttingDownBrokerIds.contains(brokerId)
      else liveBrokerIds.contains(brokerId)
    }
    brokerOnline && !replicasOnOfflineDirs.getOrElse(brokerId, Set.empty).contains(topicPartition)
  }
}
