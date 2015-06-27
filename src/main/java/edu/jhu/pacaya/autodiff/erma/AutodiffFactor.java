package edu.jhu.pacaya.autodiff.erma;

import edu.jhu.pacaya.autodiff.Module;
import edu.jhu.pacaya.util.semiring.Algebra;

public interface AutodiffFactor {

    Module<?> getFactorModule(Module<MVecFgModel> modIn, Algebra s);

}
