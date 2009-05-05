package org.apache.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.FieldValueHitQueue.Entry;
import org.apache.lucene.util.PriorityQueue;

/**
 * A {@link Collector} that sorts by {@link SortField} using
 * {@link FieldComparator}s.
 * 
 * <p><b>NOTE:</b> This API is experimental and might change in
 * incompatible ways in the next release.</p>
 */
public abstract class TopFieldCollector extends TopDocsCollector {
  
  // TODO: one optimization we could do is to pre-fill
  // the queue with sentinel value that guaranteed to
  // always compare lower than a real hit; this would
  // save having to check queueFull on each insert

  /*
   * Implements a TopFieldCollector over one SortField criteria, without
   * tracking document scores and maxScore.
   */
  private static class OneComparatorNonScoringCollector extends
      TopFieldCollector {

    final FieldComparator comparator;
    final int reverseMul;
    
    public OneComparatorNonScoringCollector(FieldValueHitQueue queue,
        int numHits, boolean fillFields) throws IOException {
      super(queue, numHits, fillFields);
      comparator = queue.getComparators()[0];
      reverseMul = queue.getReverseMul()[0];
    }
    
    private final void updateBottom(int doc) {
      // bottom.score is already set to Float.NaN in add().
      bottom.docID = docBase + doc;
      pq.adjustTop();
      bottom = (FieldValueHitQueue.Entry) pq.top();
    }

    public void collect(int doc) throws IOException {
      ++totalHits;
      if (queueFull) {
        // Fastmatch: return if this hit is not competitive
        final int cmp = reverseMul * comparator.compareBottom(doc);
        if (cmp < 0 || (cmp == 0 && doc + docBase > bottom.docID)) {
          return;
        }
        
        // This hit is competitive - replace bottom element in queue & adjustTop
        comparator.copy(bottom.slot, doc);
        updateBottom(doc);
        comparator.setBottom(bottom.slot);
      } else {
        // Startup transient: queue hasn't gathered numHits yet
        final int slot = totalHits - 1;
        // Copy hit into queue
        comparator.copy(slot, doc);
        add(slot, doc, Float.NaN);
        if (queueFull) {
          comparator.setBottom(bottom.slot);
        }
      }
    }
    
    public void setNextReader(IndexReader reader, int docBase) throws IOException {
      final int numSlotsFull = queueFull ? numHits : totalHits;
      this.docBase = docBase;
      comparator.setNextReader(reader, docBase, numSlotsFull);
    }
    
    public void setScorer(Scorer scorer) throws IOException {
      comparator.setScorer(scorer);
    }
    
  }

  /*
   * Implements a TopFieldCollector over one SortField criteria, while tracking
   * document scores but no maxScore.
   */
  private static class OneComparatorScoringNoMaxScoreCollector extends
      OneComparatorNonScoringCollector {

    private Scorer scorer;

    public OneComparatorScoringNoMaxScoreCollector(FieldValueHitQueue queue,
        int numHits, boolean fillFields) throws IOException {
      super(queue, numHits, fillFields);
    }
    
    private final void updateBottom(int doc, float score) {
      bottom.docID = docBase + doc;
      bottom.score = score;
      pq.adjustTop();
      bottom = (FieldValueHitQueue.Entry) pq.top();
    }

    public void collect(int doc) throws IOException {
      ++totalHits;
      if (queueFull) {
        // Fastmatch: return if this hit is not competitive
        final int cmp = reverseMul * comparator.compareBottom(doc);
        if (cmp < 0 || (cmp == 0 && doc + docBase > bottom.docID)) {
          return;
        }
        
        // Compute the score only if the hit is competitive.
        final float score = scorer.score();

        // This hit is competitive - replace bottom element in queue & adjustTop
        comparator.copy(bottom.slot, doc);
        updateBottom(doc, score);
        comparator.setBottom(bottom.slot);
      } else {
        // Compute the score only if the hit is competitive.
        final float score = scorer.score();

        // Startup transient: queue hasn't gathered numHits yet
        final int slot = totalHits - 1;
        // Copy hit into queue
        comparator.copy(slot, doc);
        add(slot, doc, score);
        if (queueFull) {
          comparator.setBottom(bottom.slot);
        }
      }
    }
    
    public void setNextReader(IndexReader reader, int docBase) throws IOException {
      final int numSlotsFull = queueFull ? numHits : totalHits;
      this.docBase = docBase;
      comparator.setNextReader(reader, docBase, numSlotsFull);
    }
    
    public void setScorer(Scorer scorer) throws IOException {
      this.scorer = scorer;
      comparator.setScorer(scorer);
    }
    
  }

  /*
   * Implements a TopFieldCollector over one SortField criteria, with tracking
   * document scores and maxScore.
   */
  private final static class OneComparatorScoringMaxScoreCollector extends
      OneComparatorNonScoringCollector {

    private Scorer scorer;
    
    public OneComparatorScoringMaxScoreCollector(FieldValueHitQueue queue,
        int numHits, boolean fillFields) throws IOException {
      super(queue, numHits, fillFields);
      // Must set maxScore to NEG_INF, or otherwise Math.max always returns NaN.
      maxScore = Float.NEGATIVE_INFINITY;
    }
    
    private final void updateBottom(int doc, float score) {
      bottom.docID = docBase + doc;
      bottom.score = score;
      pq.adjustTop();
      bottom = (FieldValueHitQueue.Entry) pq.top();
    }

    public void collect(int doc) throws IOException {
      final float score = scorer.score();
      if (score > maxScore) {
        maxScore = score;
      }
      ++totalHits;
      if (queueFull) {
        // Fastmatch: return if this hit is not competitive
        final int cmp = reverseMul * comparator.compareBottom(doc);
        if (cmp < 0 || (cmp == 0 && doc + docBase > bottom.docID)) {
          return;
        }
        
        // This hit is competitive - replace bottom element in queue & adjustTop
        comparator.copy(bottom.slot, doc);
        updateBottom(doc, score);
        comparator.setBottom(bottom.slot);
      } else {
        // Startup transient: queue hasn't gathered numHits yet
        final int slot = totalHits - 1;
        // Copy hit into queue
        comparator.copy(slot, doc);
        add(slot, doc, score);
        if (queueFull) {
          comparator.setBottom(bottom.slot);
        }
      }

    }
    
    public void setScorer(Scorer scorer) throws IOException {
      this.scorer = scorer;
      super.setScorer(scorer);
    }
  }

  /*
   * Implements a TopFieldCollector over multiple SortField criteria, without
   * tracking document scores and maxScore.
   */
  private static class MultiComparatorNonScoringCollector extends TopFieldCollector {
    
    final FieldComparator[] comparators;
    final int[] reverseMul;
    
    public MultiComparatorNonScoringCollector(FieldValueHitQueue queue,
        int numHits, boolean fillFields) throws IOException {
      super(queue, numHits, fillFields);
      comparators = queue.getComparators();
      reverseMul = queue.getReverseMul();
    }
    
    private final void updateBottom(int doc) {
      // bottom.score is already set to Float.NaN in add().
      bottom.docID = docBase + doc;
      pq.adjustTop();
      bottom = (FieldValueHitQueue.Entry) pq.top();
    }

    public void collect(int doc) throws IOException {
      ++totalHits;
      if (queueFull) {
        // Fastmatch: return if this hit is not competitive
        for (int i = 0;; i++) {
          final int c = reverseMul[i] * comparators[i].compareBottom(doc);
          if (c < 0) {
            // Definitely not competitive
            return;
          } else if (c > 0) {
            // Definitely competitive
            break;
          } else if (i == comparators.length - 1) {
            // This is the equals case.
            if (doc + docBase > bottom.docID) {
              // Definitely not competitive
              return;
            }
            break;
          }
        }

        // This hit is competitive - replace bottom element in queue & adjustTop
        for (int i = 0; i < comparators.length; i++) {
          comparators[i].copy(bottom.slot, doc);
        }

        updateBottom(doc);

        for (int i = 0; i < comparators.length; i++) {
          comparators[i].setBottom(bottom.slot);
        }
      } else {
        // Startup transient: queue hasn't gathered numHits yet
        final int slot = totalHits - 1;
        // Copy hit into queue
        for (int i = 0; i < comparators.length; i++) {
          comparators[i].copy(slot, doc);
        }
        add(slot, doc, Float.NaN);
        if (queueFull) {
          for (int i = 0; i < comparators.length; i++) {
            comparators[i].setBottom(bottom.slot);
          }
        }
      }
    }

    public void setNextReader(IndexReader reader, int docBase) throws IOException {
      final int numSlotsFull = queueFull ? numHits : totalHits;
      this.docBase = docBase;
      for (int i = 0; i < comparators.length; i++) {
        comparators[i].setNextReader(reader, docBase, numSlotsFull);
      }
    }

    public void setScorer(Scorer scorer) throws IOException {
      // set the scorer on all comparators
      for (int i = 0; i < comparators.length; i++) {
        comparators[i].setScorer(scorer);
      }
    }
  }
  
  /*
   * Implements a TopFieldCollector over multiple SortField criteria, with
   * tracking document scores and maxScore.
   */
  private final static class MultiComparatorScoringMaxScoreCollector extends MultiComparatorNonScoringCollector {
    
    private Scorer scorer;
    
    public MultiComparatorScoringMaxScoreCollector(FieldValueHitQueue queue,
        int numHits, boolean fillFields) throws IOException {
      super(queue, numHits, fillFields);
      // Must set maxScore to NEG_INF, or otherwise Math.max always returns NaN.
      maxScore = Float.NEGATIVE_INFINITY;
    }
    
    private final void updateBottom(int doc, float score) {
      bottom.docID = docBase + doc;
      bottom.score = score;
      pq.adjustTop();
      bottom = (FieldValueHitQueue.Entry) pq.top();
    }

    public void collect(int doc) throws IOException {
      final float score = scorer.score();
      if (score > maxScore) {
        maxScore = score;
      }
      ++totalHits;
      if (queueFull) {
        // Fastmatch: return if this hit is not competitive
        for (int i = 0;; i++) {
          final int c = reverseMul[i] * comparators[i].compareBottom(doc);
          if (c < 0) {
            // Definitely not competitive
            return;
          } else if (c > 0) {
            // Definitely competitive
            break;
          } else if (i == comparators.length - 1) {
            // This is the equals case.
            if (doc + docBase > bottom.docID) {
              // Definitely not competitive
              return;
            }
            break;
          }
        }

        // This hit is competitive - replace bottom element in queue & adjustTop
        for (int i = 0; i < comparators.length; i++) {
          comparators[i].copy(bottom.slot, doc);
        }

        updateBottom(doc, score);

        for (int i = 0; i < comparators.length; i++) {
          comparators[i].setBottom(bottom.slot);
        }
      } else {
        // Startup transient: queue hasn't gathered numHits yet
        final int slot = totalHits - 1;
        // Copy hit into queue
        for (int i = 0; i < comparators.length; i++) {
          comparators[i].copy(slot, doc);
        }
        add(slot, doc, score);
        if (queueFull) {
          for (int i = 0; i < comparators.length; i++) {
            comparators[i].setBottom(bottom.slot);
          }
        }
      }
    }

    public void setScorer(Scorer scorer) throws IOException {
      this.scorer = scorer;
      super.setScorer(scorer);
    }
  }

  /*
   * Implements a TopFieldCollector over multiple SortField criteria, with
   * tracking document scores and maxScore.
   */
  private final static class MultiComparatorScoringNoMaxScoreCollector extends MultiComparatorNonScoringCollector {
    
    private Scorer scorer;
    
    public MultiComparatorScoringNoMaxScoreCollector(FieldValueHitQueue queue,
        int numHits, boolean fillFields) throws IOException {
      super(queue, numHits, fillFields);
    }
    
    private final void updateBottom(int doc, float score) {
      bottom.docID = docBase + doc;
      bottom.score = score;
      pq.adjustTop();
      bottom = (FieldValueHitQueue.Entry) pq.top();
    }

    public void collect(int doc) throws IOException {
      ++totalHits;
      if (queueFull) {
        // Fastmatch: return if this hit is not competitive
        for (int i = 0;; i++) {
          final int c = reverseMul[i] * comparators[i].compareBottom(doc);
          if (c < 0) {
            // Definitely not competitive
            return;
          } else if (c > 0) {
            // Definitely competitive
            break;
          } else if (i == comparators.length - 1) {
            // This is the equals case.
            if (doc + docBase > bottom.docID) {
              // Definitely not competitive
              return;
            }
            break;
          }
        }

        // This hit is competitive - replace bottom element in queue & adjustTop
        for (int i = 0; i < comparators.length; i++) {
          comparators[i].copy(bottom.slot, doc);
        }

        // Compute score only if it is competitive.
        final float score = scorer.score();
        updateBottom(doc, score);

        for (int i = 0; i < comparators.length; i++) {
          comparators[i].setBottom(bottom.slot);
        }
      } else {
        // Startup transient: queue hasn't gathered numHits yet
        final int slot = totalHits - 1;
        // Copy hit into queue
        for (int i = 0; i < comparators.length; i++) {
          comparators[i].copy(slot, doc);
        }

        // Compute score only if it competitive.
        final float score = scorer.score();
        add(slot, doc, score);
        if (queueFull) {
          for (int i = 0; i < comparators.length; i++) {
            comparators[i].setBottom(bottom.slot);
          }
        }
      }
    }

    public void setScorer(Scorer scorer) throws IOException {
      this.scorer = scorer;
      super.setScorer(scorer);
    }
  }

  private static final ScoreDoc[] EMPTY_SCOREDOCS = new ScoreDoc[0];
  
  private final boolean fillFields;

  /*
   * Stores the maximum score value encountered, needed for normalizing. If
   * document scores are not tracked, this value is initialized to NaN.
   */
  float maxScore = Float.NaN;

  final int numHits;
  FieldValueHitQueue.Entry bottom = null;
  boolean queueFull;
  int docBase;
  
  // Declaring the constructor private prevents extending this class by anyone
  // else. Note that the class cannot be final since it's extended by the
  // internal versions. If someone will define a constructor with any other
  // visibility, then anyone will be able to extend the class, which is not what
  // we want.
  private TopFieldCollector(PriorityQueue pq, int numHits, boolean fillFields) {
    super(pq);
    this.numHits = numHits;
    this.fillFields = fillFields;
  }

  /**
   * Creates a new {@link TopFieldCollector} from the given arguments.
   * 
   * @param sort
   *          the sort criteria (SortFields).
   * @param numHits
   *          the number of results to collect.
   * @param fillFields
   *          specifies whether the actual field values should be returned on
   *          the results (FieldDoc).
   * @param trackDocScores
   *          specifies whether document scores should be tracked and set on the
   *          results. Note that if set to false, then the results' scores will
   *          be set to Float.NaN. Setting this to true affects performance, as
   *          it incurs the score computation on each competitive result.
   *          Therefore if document scores are not required by the application,
   *          it is recommended to set it to false.
   * @param trackMaxScore
   *          specifies whether the query's maxScore should be tracked and set
   *          on the resulting {@link TopDocs}. Note that if set to false,
   *          {@link TopDocs#getMaxScore()} returns Float.NaN. Setting this to
   *          true affects performance as it incurs the score computation on
   *          each result. Also, setting this true automatically sets
   *          <code>trackDocScores</code> to true as well.
   * @return a {@link TopFieldCollector} instance which will sort the results by
   *         the sort criteria.
   * @throws IOException
   */
  public static TopFieldCollector create(Sort sort, int numHits,
      boolean fillFields, boolean trackDocScores, boolean trackMaxScore)
      throws IOException {
    if (sort.fields.length == 0) {
      throw new IllegalArgumentException("Sort must contain at least one field");
    }
    
    FieldValueHitQueue queue = FieldValueHitQueue.create(sort.fields, numHits);
    if (queue.getComparators().length == 1) {
      if (trackMaxScore) {
        return new OneComparatorScoringMaxScoreCollector(queue, numHits, fillFields);
      } else if (trackDocScores) {
        return new OneComparatorScoringNoMaxScoreCollector(queue, numHits, fillFields);
      } else {
        return new OneComparatorNonScoringCollector(queue, numHits, fillFields);
      }
    }

    // multiple comparators.
    if (trackMaxScore) {
      return new MultiComparatorScoringMaxScoreCollector(queue, numHits, fillFields);
    } else if (trackDocScores) {
      return new MultiComparatorScoringNoMaxScoreCollector(queue, numHits, fillFields);
    } else {
      return new MultiComparatorNonScoringCollector(queue, numHits, fillFields);
    }
  }
  
  final void add(int slot, int doc, float score) {
    pq.put(new FieldValueHitQueue.Entry(slot, docBase + doc, score));
    bottom = (FieldValueHitQueue.Entry) pq.top();
    queueFull = totalHits == numHits;
  }

  /*
   * Only the following callback methods need to be overridden since
   * topDocs(int, int) calls them to return the results.
   */

  protected void populateResults(ScoreDoc[] results, int howMany) {
    FieldValueHitQueue queue = (FieldValueHitQueue) pq;
    if (fillFields) {
      for (int i = queue.size() - 1; i >= 0; i--) {
        results[i] = queue.fillFields((FieldValueHitQueue.Entry) queue.pop());
      }
    } else {
      for (int i = queue.size() - 1; i >= 0; i--) {
        Entry entry = (FieldValueHitQueue.Entry) queue.pop();
        results[i] = new FieldDoc(entry.docID, entry.score);
      }
    }
  }
  
  protected TopDocs newTopDocs(ScoreDoc[] results, int start) {
    if (results == null) {
      results = EMPTY_SCOREDOCS;
      // Set maxScore to NaN, in case this is a maxScore tracking collector.
      maxScore = Float.NaN;
    }

    // If this is a maxScoring tracking collector and there were no results, 
    return new TopFieldDocs(totalHits, results, ((FieldValueHitQueue) pq).getFields(), maxScore);
  }
  
}