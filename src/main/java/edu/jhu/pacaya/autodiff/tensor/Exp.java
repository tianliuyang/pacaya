package edu.jhu.pacaya.autodiff.tensor;

import java.util.List;

import edu.jhu.pacaya.autodiff.AbstractModule;
import edu.jhu.pacaya.autodiff.Module;
import edu.jhu.pacaya.autodiff.Tensor;
import edu.jhu.pacaya.util.collections.QLists;

/**
 * Takes the exp of each entry.
 * 
 * @author mgormley
 */
public class Exp extends AbstractModule<Tensor> implements Module<Tensor> {

    private Module<Tensor> modInX;
    
    public Exp(Module<Tensor> modInX) {        
        super(modInX.getAlgebra());
        this.modInX = modInX;
    }
    
    /** Foward pass: y_i = exp(x_i) */
    @Override
    public Tensor forward() {
        Tensor x = modInX.getOutput();
        y = new Tensor(x); // copy
        y.exp();
        return y;
    }

    /** 
     * Backward pass: 
     *    dG/dx_i += dG/dy_i dy_i/dx_i = dG/dy_i exp(x_i)
     */
    @Override
    public void backward() {
        Tensor tmp = new Tensor(yAdj); // copy
        tmp.elemMultiply(y);
        modInX.getOutputAdj().elemAdd(tmp);
    }

    @Override
    public List<Module<Tensor>> getInputs() {
        return QLists.getList(modInX);
    }

}
