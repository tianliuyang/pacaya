package edu.jhu.nlp.depparse;

import java.io.Serializable;
import java.util.Arrays;

import org.apache.log4j.Logger;

import edu.jhu.autodiff.erma.InsideOutsideDepParse;
import edu.jhu.nlp.Annotator;
import edu.jhu.nlp.Trainable;
import edu.jhu.nlp.data.DepEdgeMask;
import edu.jhu.nlp.data.LabelSequence;
import edu.jhu.nlp.data.simple.AnnoSentence;
import edu.jhu.nlp.data.simple.AnnoSentenceCollection;
import edu.jhu.prim.matrix.DenseIntegerMatrix;
import edu.jhu.util.Alphabet;

/**
 * Distance-based pruning method from Rush & Petrov (2012).
 * 
 * For any pair of POS tag types, this approach prunes any edge for which the
 * distance is longer than the maximum distance observed at training time for
 * that POS tag type pair.
 * 
 * @author mgormley
 */
public class PosTagDistancePruner implements Trainable, Annotator, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(PosTagDistancePruner.class);
    private Alphabet<String> alphabet = new Alphabet<String>();
    private DenseIntegerMatrix mat;
    
    public PosTagDistancePruner() { }
    
    @Override
    public void train(AnnoSentenceCollection trainInput, AnnoSentenceCollection trainGold, 
            AnnoSentenceCollection devInput, AnnoSentenceCollection devGold) {
        for (AnnoSentence sent : trainGold) {
            // Populate the alphabet
            new LabelSequence<String>(alphabet, sent.getPosTags());
        }
        // Don't stop growth of alphabet.
        
        mat = new DenseIntegerMatrix(alphabet.size(), alphabet.size());
        mat.fill(0);
        // For each sentence...
        for (AnnoSentence sent : trainGold) {
            LabelSequence<String> tagSeq = new LabelSequence<String>(alphabet, sent.getPosTags());        
            int[] tags = tagSeq.getLabelIds();
            int[] parents = sent.getParents();            
            // For each observed dependency edge...
            for (int c=0; c<parents.length; c++) {
                int p = parents[c];
                if (p == -1) {
                    // We don't prune edges to the wall node.
                    continue;
                }
                int dist = Math.abs(p - c);
                if (dist > mat.get(tags[p], tags[c])) {
                    // Record the max dependency length for the observed parent
                    // / child tag types.
                    mat.set(tags[p], tags[c], dist);
                }
            }
        }
    }
    
    @Override
    public void annotate(AnnoSentenceCollection sents) {
        if (mat == null) {
            throw new IllegalStateException("The train() method must be called before annotate()");
        }
        int numEdgesTot = 0;
        int numEdgesKept = 0;
        // For each sentence...
        for (AnnoSentence sent : sents) {
            // Get existing DepEdgeMask or create a new one.
            DepEdgeMask mask = sent.getDepEdgeMask();
            if (mask == null) {
                mask = new DepEdgeMask(sent.size(), true);
                sent.setDepEdgeMask(mask);
            }
            
            LabelSequence<String> tagSeq = new LabelSequence<String>(alphabet, sent.getPosTags());        
            int[] tags = tagSeq.getLabelIds();

            // For each possible dependency edge (not including edges to the wall)...
            for (int i = 0; i < tags.length; i++) {
                for (int j = 0; j < tags.length; j++) {
                    numEdgesTot++;
                    if (tags[i] >= mat.getNumRows() || tags[j] >= mat.getNumRows()) {
                        // Don't prune unknown tags.
                        continue;
                    }
                    int dist = Math.abs(i - j);
                    if (dist > mat.get(tags[i], tags[j])) {
                        // Prune any edge for which the distance is longer than
                        // the longest observed distance for the parent / child
                        // tag types.
                        mask.setIsPruned(i, j, true);
                        if (log.isTraceEnabled()) {
                            log.trace(String.format("Pruned edge: parent=%s child=%s dist=%d",
                                    alphabet.lookupObject(tags[i]), alphabet.lookupObject(tags[j]), dist));
                        }
                    } else {
                        numEdgesKept++;
                    }
                }
            }
            // Count the edges to the wall, which will never be pruned.
            numEdgesTot += sent.size();
            // Check that there still exists some singly-rooted spanning tree that wasn't pruned.n            
            if (InsideOutsideDepParse.singleRoot && !mask.allowsSingleRootTrees()) {
                log.warn("All single-root trees pruned");
                log.trace(String.format("Pruned sentence: \n%s\n%s", sent.getWords().toString(), mask.toString()));
                if (sent.getParents() != null) {
                    log.trace("Pruned parents: " + Arrays.toString(sent.getParents()));
                }
            } else if (!InsideOutsideDepParse.singleRoot && !mask.allowsMultiRootTrees()) {
                log.warn("All multi-root trees pruned");
            }
        }
        
        int numEdgesPruned = numEdgesTot - numEdgesKept;
        log.info(String.format("Pruned %d / %d = %f edges", numEdgesPruned, numEdgesTot, (double) numEdgesPruned / numEdgesTot));        
    }
    
}
