package edu.jhu.hltcoe.util.dist;

import java.util.Arrays;

import cern.jet.random.tdouble.Gamma;

import edu.jhu.hltcoe.math.Multinomials;
import edu.jhu.hltcoe.util.Prng;

public class Dirichlet {

    private static final double EPSILON = Double.MIN_VALUE;

    private double[] alpha;
    
    public Dirichlet(double alphaVal, int k) {
        this.alpha = new double[k];
        Arrays.fill(this.alpha, alphaVal);
    }
    
    public Dirichlet(double[] alpha) {
        this.alpha = alpha;
    }
    
    public double[] draw() {
        return staticDraw(alpha);
    }
    
    public static double[] staticDraw(double[] alpha) {
        double dist[] = new double[alpha.length];

        Gamma gammaDist = new Gamma(1, 1, Prng.doubleMtColt);
        
        // For each dimension, draw a sample from Gamma(mp_i, 1).
        for (int i = 0; i < dist.length; i++) {
            dist[i] = gammaDist.nextDouble(alpha[i], 1);
            if (dist[i] <= 0) {
                dist[i] = EPSILON;
            }
        }
        
        // Normalize the distribution.
        Multinomials.normalizeProps(dist);

        return dist;
    }
    
}
