// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.local;

import static com.google.firebase.firestore.util.Assert.hardAssert;

import com.google.firebase.firestore.core.ListenSequence;
import com.google.firebase.firestore.model.DocumentKey;
import com.google.firebase.firestore.util.Consumer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Provides LRU garbage collection functionality for MemoryPersistence. */
class MemoryLruReferenceDelegate implements ReferenceDelegate, LruDelegate {
  private final MemoryPersistence persistence;
  private final Map<DocumentKey, Long> orphanedSequenceNumbers;
  private ReferenceSet additionalReferences;
  private final LruGarbageCollector garbageCollector;
  private final ListenSequence listenSequence;
  private long currentSequenceNumber;

  MemoryLruReferenceDelegate(MemoryPersistence persistence) {
    this.persistence = persistence;
    this.orphanedSequenceNumbers = new HashMap<>();
    this.listenSequence =
        new ListenSequence(persistence.getQueryCache().getHighestListenSequenceNumber());
    this.currentSequenceNumber = ListenSequence.INVALID;
    this.garbageCollector = new LruGarbageCollector(this);
  }

  @Override
  public LruGarbageCollector getGarbageCollector() {
    return garbageCollector;
  }

  @Override
  public void onTransactionStarted() {
    hardAssert(
        currentSequenceNumber == ListenSequence.INVALID,
        "Starting a transaction without committing the previous one");
    currentSequenceNumber = listenSequence.next();
  }

  @Override
  public void onTransactionCommitted() {
    hardAssert(
        currentSequenceNumber != ListenSequence.INVALID,
        "Committing a transaction without having started one");
    currentSequenceNumber = ListenSequence.INVALID;
  }

  @Override
  public long getCurrentSequenceNumber() {
    hardAssert(
        currentSequenceNumber != ListenSequence.INVALID,
        "Attempting to get a sequence number outside of a transaction");
    return currentSequenceNumber;
  }

  @Override
  public void forEachTarget(Consumer<QueryData> consumer) {
    persistence.getQueryCache().forEachTarget(consumer);
  }

  @Override
  public long getTargetCount() {
    return persistence.getQueryCache().getTargetCount();
  }

  @Override
  public void forEachOrphanedDocumentSequenceNumber(Consumer<Long> consumer) {
    for (Long sequenceNumber : orphanedSequenceNumbers.values()) {
      consumer.accept(sequenceNumber);
    }
  }

  @Override
  public void setAdditionalReferences(ReferenceSet additionalReferences) {
    this.additionalReferences = additionalReferences;
  }

  @Override
  public int removeQueries(long upperBound, Set<Integer> activeTargetIds) {
    return persistence.getQueryCache().removeQueries(upperBound, activeTargetIds);
  }

  @Override
  public int removeOrphanedDocuments(long upperBound) {
    return persistence.getRemoteDocumentCache().removeOrphanedDocuments(this, upperBound);
  }

  @Override
  public void removeMutationReference(DocumentKey key) {
    orphanedSequenceNumbers.put(key, getCurrentSequenceNumber());
  }

  @Override
  public void removeTarget(QueryData queryData) {
    QueryData updated =
        queryData.copy(
            queryData.getSnapshotVersion(), queryData.getResumeToken(), getCurrentSequenceNumber());
    persistence.getQueryCache().updateQueryData(updated);
  }

  @Override
  public void addReference(DocumentKey key) {
    orphanedSequenceNumbers.put(key, getCurrentSequenceNumber());
  }

  @Override
  public void removeReference(DocumentKey key) {
    orphanedSequenceNumbers.put(key, getCurrentSequenceNumber());
  }

  @Override
  public void updateLimboDocument(DocumentKey key) {
    orphanedSequenceNumbers.put(key, getCurrentSequenceNumber());
  }

  private boolean mutationQueuesContainsKey(DocumentKey key) {
    for (MemoryMutationQueue mutationQueue : persistence.getMutationQueues()) {
      if (mutationQueue.containsKey(key)) {
        return true;
      }
    }
    return false;
  }

  public boolean isPinned(DocumentKey key, long upperBound) {
    if (mutationQueuesContainsKey(key)) {
      return true;
    }

    if (additionalReferences.containsKey(key)) {
      return true;
    }

    if (persistence.getQueryCache().containsKey(key)) {
      return true;
    }

    Long sequenceNumber = orphanedSequenceNumbers.get(key);
    return sequenceNumber != null && sequenceNumber > upperBound;
  }
}