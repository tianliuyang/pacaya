package edu.jhu.pacaya.gm.model.globalfac;

import edu.jhu.pacaya.gm.model.ExplicitFactor;

/**
 * FOR TESTING ONLY. Treats a GlobalFactor as an ExplicitFactor.
 * 
 * TODO: Implement AutodiffFactor.
 * 
 * @see GlobalExplicitFactor
 * 
 * @author mgormley
 */
public class ExplicitGlobalFactor extends ExplicitFactor {

    private static final long serialVersionUID = 1L;

    public ExplicitGlobalFactor(GlobalFactor gf) {
        super(gf.getVars());
        // Initialize from the global factor.
        for (int c=0; c<this.size(); c++) {
            double score = gf.getLogUnormalizedScore(c);
            this.setValue(c, score);
        }
    }

}
