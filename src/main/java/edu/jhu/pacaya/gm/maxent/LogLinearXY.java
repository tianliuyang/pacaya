package edu.jhu.pacaya.gm.maxent;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.jhu.pacaya.gm.data.FgExampleList;
import edu.jhu.pacaya.gm.data.FgExampleMemoryStore;
import edu.jhu.pacaya.gm.data.LFgExample;
import edu.jhu.pacaya.gm.data.LabeledFgExample;
import edu.jhu.pacaya.gm.decode.MbrDecoder;
import edu.jhu.pacaya.gm.decode.MbrDecoder.MbrDecoderPrm;
import edu.jhu.pacaya.gm.feat.FeatureVector;
import edu.jhu.pacaya.gm.feat.StringIterable;
import edu.jhu.pacaya.gm.inf.BruteForceInferencer.BruteForceInferencerPrm;
import edu.jhu.pacaya.gm.inf.BeliefPropagation.BpScheduleType;
import edu.jhu.pacaya.gm.inf.BeliefPropagation.BpUpdateOrder;
import edu.jhu.pacaya.gm.inf.BeliefPropagation.BeliefPropagationPrm;
import edu.jhu.pacaya.gm.maxent.LogLinearXYData.LogLinearExample;
import edu.jhu.pacaya.gm.model.ExpFamFactor;
import edu.jhu.pacaya.gm.model.FactorGraph;
import edu.jhu.pacaya.gm.model.FgModel;
import edu.jhu.pacaya.gm.model.Var;
import edu.jhu.pacaya.gm.model.Var.VarType;
import edu.jhu.pacaya.gm.model.VarConfig;
import edu.jhu.pacaya.gm.model.VarSet;
import edu.jhu.pacaya.gm.model.VarTensor;
import edu.jhu.pacaya.gm.train.CrfTrainer;
import edu.jhu.pacaya.gm.train.CrfTrainer.CrfTrainerPrm;
import edu.jhu.pacaya.util.semiring.LogSemiring;
import edu.jhu.prim.bimap.IntObjectBimap;
import edu.jhu.prim.tuple.Pair;

/**
 * Log-linear model trainer and decoder.
 * 
 * @author mgormley
 */
public class LogLinearXY {

    private static final Logger log = LoggerFactory.getLogger(LogLinearXY.class);

    public static class LogLinearXYPrm {
        public boolean cacheExamples = true;
        public CrfTrainerPrm crfPrm = new CrfTrainerPrm();
        public LogLinearXYPrm() {
            crfPrm.infFactory = new BruteForceInferencerPrm(LogSemiring.getInstance());
        }
    }
    
    private LogLinearXYPrm prm;
    
    public LogLinearXY(LogLinearXYPrm prm) {
        this.prm = prm;
    }
    
    private IntObjectBimap<String> alphabet = null;
    private List<String> stateNames = null;
    
    /**
     * Trains a log-linear model.
     * 
     * @param data The log-linear model training examples created by
     *            LogLinearData.
     * @return Trained model.
     */
    public FgModel train(LogLinearXYData data) {
        IntObjectBimap<String> alphabet = data.getFeatAlphabet();
        FgExampleList list = getData(data);
        log.info("Number of train instances: " + list.size());                
        log.info("Number of model parameters: " + alphabet.size());
        FgModel model = new FgModel(alphabet.size(), new StringIterable(alphabet.getObjects()));
        CrfTrainer trainer = new CrfTrainer(prm.crfPrm);
        trainer.train(model, list);
        return model;
    }

    /**
     * Decodes a single example.
     * 
     * @param model The log-linear model.
     * @param ex The example to decode.
     * @return A pair containing the most likely label (i.e. value of y) and the
     *         distribution over y values.
     */
    public Pair<String, VarTensor> decode(FgModel model, LogLinearExample llex) {
        LFgExample ex = getFgExample(llex);
        
        MbrDecoderPrm prm = new MbrDecoderPrm();
        prm.infFactory = getBpPrm(); 
        MbrDecoder decoder = new MbrDecoder(prm);
        decoder.decode(model, ex);
        List<VarTensor> marginals = decoder.getVarMarginals();
        VarConfig vc = decoder.getMbrVarConfig();
        String stateName = vc.getStateName(ex.getFactorGraph().getVar(0));
        if (marginals.size() != 1) {
            throw new IllegalStateException("Example is not from a LogLinearData factory");
        }
        return new Pair<String,VarTensor>(stateName, marginals.get(0));
    }

    /**
     * For testing only. Converts to the graphical model's representation of the data.
     */
    public FgExampleList getData(LogLinearXYData data) {
        IntObjectBimap<String> alphabet = data.getFeatAlphabet();
        List<LogLinearExample> exList = data.getData();    
        if (this.alphabet == null) {
            this.alphabet = alphabet;
            this.stateNames = getStateNames(exList, data.getYAlphabet());
        }
        
        if (prm.cacheExamples) {
            FgExampleMemoryStore store = new FgExampleMemoryStore();
            for (final LogLinearExample desc : exList) {
                LFgExample ex = getFgExample(desc);
                store.add(ex);
            }
            return store;
        } else {
            return new FgExampleList() {
                @Override
                public LFgExample get(int i) {
                    return getFgExample(exList.get(i));
                }
                @Override
                public int size() {
                    return exList.size();
                }                
            };
        }
    }

    private LFgExample getFgExample(final LogLinearExample desc) {
        if (alphabet == null) {
            throw new IllegalStateException("decode can only be called after train");
        }
        
        Var v0 = getVar();
        final VarConfig trainConfig = new VarConfig();
        trainConfig.put(v0, desc.getY());
        
        FactorGraph fg = new FactorGraph();
        VarSet vars = new VarSet(v0);
        ExpFamFactor f0 = new ExpFamFactor(vars) {
            
            @Override
            public FeatureVector getFeatures(int config) {
                return desc.getFeatures(config);
            }
            
        };
        fg.addFactor(f0);
        LabeledFgExample ex = new LabeledFgExample(fg, trainConfig);
        ex.setWeight(desc.getWeight());
        return ex;
    }

    private Var getVar() {
        return new Var(VarType.PREDICTED, stateNames.size(), "v0", stateNames);
    }
    
    private static List<String> getStateNames(List<LogLinearExample> exList, IntObjectBimap<Object> yAlphabet) {
        StringIterable iter = new StringIterable(yAlphabet.getObjects());
        List<String> list = new ArrayList<String>();
        for (String s : iter) {
            list.add(s);
        }
        return list;
    }
    
    private BeliefPropagationPrm getBpPrm() {
        BeliefPropagationPrm bpPrm = new BeliefPropagationPrm();
        bpPrm.s = LogSemiring.getInstance();
        bpPrm.schedule = BpScheduleType.TREE_LIKE;
        bpPrm.updateOrder = BpUpdateOrder.SEQUENTIAL;
        bpPrm.normalizeMessages = false;
        bpPrm.keepTape = false;
        return bpPrm;
    }
    
}
