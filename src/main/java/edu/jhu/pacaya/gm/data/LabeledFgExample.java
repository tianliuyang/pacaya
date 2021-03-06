package edu.jhu.pacaya.gm.data;

import java.io.Serializable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.jhu.pacaya.gm.feat.FactorTemplateList;
import edu.jhu.pacaya.gm.feat.FeatureExtractor;
import edu.jhu.pacaya.gm.model.FactorGraph;
import edu.jhu.pacaya.gm.model.Var;
import edu.jhu.pacaya.gm.model.Var.VarType;
import edu.jhu.pacaya.gm.model.VarConfig;
import edu.jhu.pacaya.gm.model.VarSet;

/**
 * Factor graph example. This class facilitates creation of the clamped factor
 * graphs, creation/caching of feature vectors, and storage of the training data
 * assignments to variables.
 * 
 * @author mgormley
 * 
 */
public class LabeledFgExample extends UnlabeledFgExample implements LFgExample, Serializable {
    
    private static final Logger log = LoggerFactory.getLogger(LabeledFgExample.class);
    private static final long serialVersionUID = 1L;
    
    /** The variable assignments given in the gold data for all the variables in the factor graph. */
    private VarConfig goldConfig;
    /** The weight of this example for use in training. */
    private double weight = 1.0;
        
    // TODO: Figure out how to remove this "initializing" constructor.
    // TODO: Maybe convert to factory methods.
    public LabeledFgExample(FactorGraph fg, VarConfig goldConfig, FactorTemplateList fts) {
        this(fg, goldConfig);
        // Update the factor templates.
        fts.lookupTemplateIds(this.getFactorGraph());
        fts.getTemplateIds(this.getFactorGraph());
    }

    /**
     * Constructs a train or test example for a Factor Graph.
     * 
     * @param fg The factor graph.
     * @param goldConfig The gold assignment to the variables.
     */
    public LabeledFgExample(FactorGraph fg, VarConfig goldConfig) {
        super(fg);
        checkGoldConfig(fg, goldConfig);
        this.goldConfig = goldConfig;
    }

    private static void checkGoldConfig(FactorGraph fg, VarConfig goldConfig) {
        for (Var var : fg.getVars()) {
            // Latent variables don't need to be specified in the gold variable assignment.
            if (var.getType() != VarType.LATENT && goldConfig.getState(var, -1) == -1) {
                int numNonLat = VarSet.getVarsOfType(fg.getVars(), VarType.PREDICTED).size();
                log.error(String.format("Missing vars. #non-latent=%d #assign=%d", numNonLat, goldConfig.size()));
                throw new IllegalStateException("Vars missing from train configuration: " + var);
            }
        }
    }
    
    /** Gets the gold configuration of the variables. */
    public VarConfig getGoldConfig() {
        return goldConfig;
    }

    /** Gets the gold configuration of the predicted variables ONLY for the given factor. */ 
    public VarConfig getGoldConfigPred(int factorId) {
        VarSet vars = fgLatPred.getFactor(factorId).getVars();
        return goldConfig.getIntersection(VarSet.getVarsOfType(vars, VarType.PREDICTED));
    }
    
    /** Gets the gold configuration index of the predicted variables for the given factor. */
    public int getGoldConfigIdxPred(int factorId) {
        VarSet vars = VarSet.getVarsOfType(fgLatPred.getFactor(factorId).getVars(), VarType.PREDICTED);
        return goldConfig.getConfigIndexOfSubset(vars);
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }
    
    @Override
    public double getWeight() { 
        return weight;
    }
}
