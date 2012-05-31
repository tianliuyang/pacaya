package edu.jhu.hltcoe;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.jboss.dna.common.statistic.Stopwatch;

import edu.jhu.hltcoe.data.DepTreebank;
import edu.jhu.hltcoe.data.FileMapTagReducer;
import edu.jhu.hltcoe.data.Label;
import edu.jhu.hltcoe.data.Ptb45To17TagReducer;
import edu.jhu.hltcoe.data.Sentence;
import edu.jhu.hltcoe.data.SentenceCollection;
import edu.jhu.hltcoe.data.TaggedWord;
import edu.jhu.hltcoe.data.VerbTreeFilter;
import edu.jhu.hltcoe.eval.DependencyParserEvaluator;
import edu.jhu.hltcoe.eval.Evaluator;
import edu.jhu.hltcoe.gridsearch.dmv.BnBDmvTrainer;
import edu.jhu.hltcoe.gridsearch.dmv.DmvDantzigWolfeRelaxation;
import edu.jhu.hltcoe.gridsearch.dmv.DmvDantzigWolfeRelaxationTest;
import edu.jhu.hltcoe.gridsearch.dmv.DmvRelaxation;
import edu.jhu.hltcoe.gridsearch.dmv.DmvSolution;
import edu.jhu.hltcoe.gridsearch.dmv.IndexedDmvModel;
import edu.jhu.hltcoe.gridsearch.dmv.RelaxedDmvSolution;
import edu.jhu.hltcoe.gridsearch.dmv.DmvDantzigWolfeRelaxationTest.InitSol;
import edu.jhu.hltcoe.model.Model;
import edu.jhu.hltcoe.model.dmv.DmvDepTreeGenerator;
import edu.jhu.hltcoe.model.dmv.DmvModel;
import edu.jhu.hltcoe.model.dmv.DmvModelConverter;
import edu.jhu.hltcoe.model.dmv.DmvModelFactory;
import edu.jhu.hltcoe.model.dmv.DmvRandomWeightGenerator;
import edu.jhu.hltcoe.model.dmv.DmvUniformWeightGenerator;
import edu.jhu.hltcoe.model.dmv.DmvWeightGenerator;
import edu.jhu.hltcoe.model.dmv.SimpleStaticDmvModel;
import edu.jhu.hltcoe.parse.DmvCkyParser;
import edu.jhu.hltcoe.parse.ViterbiParser;
import edu.jhu.hltcoe.train.Trainer;
import edu.jhu.hltcoe.train.TrainerFactory;
import edu.jhu.hltcoe.util.Command;
import edu.jhu.hltcoe.util.Prng;
import edu.jhu.hltcoe.util.Time;

public class PipelineRunner {

    private static Logger log = Logger.getLogger(PipelineRunner.class);

    public PipelineRunner() {
    }

    public void run(CommandLine cmd) throws ParseException, IOException {  
        DepTreebank depTreebank;
        if (cmd.hasOption("train")) {
            // Read the data and (maybe) reduce size of treebank
            log.info("Reading data");
            String trainPath = cmd.getOptionValue("train");
            int maxSentenceLength = Command.getOptionValue(cmd, "maxSentenceLength", Integer.MAX_VALUE);
            int maxNumSentences = Command.getOptionValue(cmd, "maxNumSentences", Integer.MAX_VALUE); 
    
            depTreebank = new DepTreebank(maxSentenceLength, maxNumSentences);
            if (cmd.hasOption("mustContainVerb")) {
                depTreebank.setTreeFilter(new VerbTreeFilter());
            }
            depTreebank.loadPath(trainPath);
            
            String reduceTags = Command.getOptionValue(cmd, "reduceTags", "none");
            if ("45to17".equals(reduceTags)) {
                log.info("Reducing PTB from 45 to 17 tags");
                (new Ptb45To17TagReducer()).reduceTags(depTreebank);
            } else if (!"none".equals(reduceTags)) {
                log.info("Reducing tags with file map: " + reduceTags);
                (new FileMapTagReducer(new File(reduceTags))).reduceTags(depTreebank);
            }
        } else if (cmd.hasOption("synthetic")) {
            DmvModel trueModel = SimpleStaticDmvModel.getTwoPosTagInstance();
            long syntheticSeed = 123454321;
            if (cmd.hasOption("syntheticSeed")) {
                syntheticSeed = Long.parseLong(cmd.getOptionValue("syntheticSeed"));
            }
            DmvDepTreeGenerator generator = new DmvDepTreeGenerator(trueModel, syntheticSeed);
            int maxNumSentences = Command.getOptionValue(cmd, "maxNumSentences", 100); 
            depTreebank = generator.getTreebank(maxNumSentences);
        } else {
            throw new ParseException("Either the option --train or --synthetic must be specific");
        }
        
        log.info("Number of sentences: " + depTreebank.size());
        log.info("Number of tokens: " + depTreebank.getNumTokens());
        log.info("Number of types: " + depTreebank.getNumTypes());
        
        SentenceCollection sentences = depTreebank.getSentences();
        
        // Print sentences to a file
        printSentences(cmd, depTreebank, sentences);
          
        if (cmd.hasOption("relaxOnly")) {
            DmvDantzigWolfeRelaxation dw = DmvDantzigWolfeRelaxationTest.getDw(sentences, 100);
            DmvSolution initBoundsSol = updateBounds(cmd, sentences, dw);
            Stopwatch timer = new Stopwatch();
            timer.start();
            RelaxedDmvSolution relaxSol = dw.solveRelaxation();
            timer.stop();
            log.info(Time.totMs(timer));
            log.info("relaxBound: " + relaxSol.getScore());
            if (initBoundsSol != null) {
                log.info("relative: " + Math.abs(relaxSol.getScore() - initBoundsSol.getScore()) / Math.abs(initBoundsSol.getScore()));
            }
            //TODO: log.info("containsGoldSol: " + containsInitSol(dw.getBounds(), goldSol.getLogProbs()));
        } else {            
            // Train the model
            log.info("Training model");
            Trainer trainer = TrainerFactory.getTrainer(cmd);
            if (trainer instanceof BnBDmvTrainer) {
                BnBDmvTrainer bnb = (BnBDmvTrainer) trainer;
                bnb.init(sentences);
                updateBounds(cmd, sentences, bnb.getRootRelaxation());
                bnb.train();
            } else {
                trainer.train(sentences);
            }
            Model model = trainer.getModel();
            
            // Evaluate the model
            log.info("Evaluating model");
            // Note: this parser must return the log-likelihood from parser.getParseWeight()
            ViterbiParser parser = TrainerFactory.getEvalParser();
            Evaluator pwEval = new DependencyParserEvaluator(parser, depTreebank);
            pwEval.evaluate(model);
            pwEval.print();
            
            // Print learned model to a file
            String printModel = Command.getOptionValue(cmd, "printModel", null);
            if (printModel != null) {
                BufferedWriter writer = new BufferedWriter(new FileWriter(printModel));
                writer.write("Learned Model:\n");
                writer.write(model.toString());
                writer.close();
            }
        }
    }

    private DmvSolution updateBounds(CommandLine cmd, SentenceCollection sentences, DmvRelaxation dw) {
        if (cmd.hasOption("initBounds")) {
            InitSol opt = InitSol.getById(Command.getOptionValue(cmd, "initBounds", "none"));
            IndexedDmvModel idm = dw.getIdm();

            DmvSolution initBoundsSol;
            if (opt == InitSol.VITERBI_EM) {
                // TODO: hacky to call a test method and Trainer ignore parameters
                initBoundsSol = DmvDantzigWolfeRelaxationTest.getInitFeasSol(sentences);
            } else if (opt == InitSol.GOLD) {
                
                // TODO initSol = goldSol;
                throw new RuntimeException("not implemented");                
                
            } else if (opt == InitSol.RANDOM || opt == InitSol.UNIFORM){
                DmvWeightGenerator weightGen;
                if (opt == InitSol.RANDOM) {
                    Prng.seed(System.currentTimeMillis());
                    weightGen = new DmvRandomWeightGenerator(0.00001);
                } else {
                    weightGen = new DmvUniformWeightGenerator();
                }
                DmvModelFactory modelFactory = new DmvModelFactory(weightGen);
                DmvModel randModel = (DmvModel)modelFactory.getInstance(sentences);
                double[][] logProbs = idm.getCmLogProbs(DmvModelConverter.getDepProbMatrix(randModel, sentences.getLabelAlphabet()));
                ViterbiParser parser = new DmvCkyParser();
                DepTreebank treebank = parser.getViterbiParse(sentences, randModel);
                initBoundsSol = new DmvSolution(logProbs, idm, treebank, dw.computeTrueObjective(logProbs, treebank));            
            } else {
                throw new IllegalStateException("unsupported initialization: " + opt);
            }

            double offsetProb = Command.getOptionValue(cmd, "offsetProb", 1.0);
            double probOfSkipCm = Command.getOptionValue(cmd, "probOfSkipCm", 0.0);
            int numDoubledCms = Command.getOptionValue(cmd, "numDoubledCms", 0);
            if (numDoubledCms > 0) {
                // TODO:
                throw new RuntimeException("not implemented");
            }
            
            DmvDantzigWolfeRelaxationTest.setBoundsFromInitSol(dw, initBoundsSol, offsetProb, probOfSkipCm);
            
            return initBoundsSol;
        }
        return null;
    }

    private void printSentences(CommandLine cmd, DepTreebank depTreebank, SentenceCollection sentences)
            throws IOException {
        String printSentences = Command.getOptionValue(cmd, "printSentences", null);
        if (printSentences != null) {
            BufferedWriter writer = new BufferedWriter(new FileWriter(printSentences));
            // TODO: improve this
            log.info("Printing sentences...");
            writer.write("Sentences:\n");
            for (Sentence sent : sentences) {
                StringBuilder sb = new StringBuilder();
                for (Label label : sent) {
                    if (label instanceof TaggedWord) {
                        sb.append(((TaggedWord)label).getWord());
                    } else {
                        sb.append(label.getLabel());
                    }
                    sb.append(" ");
                }
                sb.append("\t");
                for (Label label : sent) {
                    if (label instanceof TaggedWord) {
                        sb.append(((TaggedWord)label).getTag());
                        sb.append(" ");
                    }
                }
                sb.append("\n");
                writer.write(sb.toString());
            }
            if (cmd.hasOption("synthetic")) {
                log.info("Print trees...");
                // Also print the synthetic trees
                writer.write("Trees:\n");
                writer.write(depTreebank.toString());
            }
            writer.close();
        }
    }

    public static Options createOptions() {
        Options options = new Options();
        
        // Options not specific to the model

        options.addOption("s", "seed", true, "Pseudo random number generator seed for everything else.");
        options.addOption("pm", "printModel", true, "File to which we should print the model.");
        options.addOption("ro", "relaxOnly", false, "Flag indicating that only a relaxation should be run");
        
        // Options for data
        options.addOption("tr", "train", true, "Training data.");
        options.addOption("tr", "synthetic", true, "Generate synthetic training data.");
        options.addOption("msl", "maxSentenceLength", true, "Max sentence length.");
        options.addOption("mns", "maxNumSentences", true, "Max number of sentences for training."); 
        options.addOption("vb", "mustContainVerb", false, "Filter down to sentences that contain certain verbs."); 
        options.addOption("rd", "reduceTags", true, "Tag reduction type [none, 45to17, {a file map}]."); 
        options.addOption("ps", "printSentences", true, "File to which we should print the sentences.");
        options.addOption("ss", "syntheticSeed", true, "Pseudo random number generator seed for synthetic data generation only.");
        
        // Options to restrict the initialization
        options.addOption("ib", "initBounds", true, "How to initialize the bounds: [viterbi-em, gold, random, uniform, none]");
        options.addOption("op", "offsetProb", true, "How much to offset the bounds in probability space from the initial bounds point");
        options.addOption("op", "numDoubledCms", true, "How many model parameters around which the bounds should be doubled");
        options.addOption("op", "probOfSkipCm", true, "The probability of not bounding a particular variable");
        
        TrainerFactory.addOptions(options);
        return options;
    }

    private static void configureLogging() {
        BasicConfigurator.configure();
    }
    
    public static void main(String[] args) throws IOException {
        configureLogging();
        
        String usage = "java " + PipelineRunner.class.getName() + " [OPTIONS]";
        CommandLineParser parser = new PosixParser();
        Options options = createOptions();
        String[] requiredOptions = new String[] { };

        CommandLine cmd = null;
        final HelpFormatter formatter = new HelpFormatter();
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e1) {
            formatter.printHelp(usage, options, true);
            System.exit(1);
        }
        for (String requiredOption : requiredOptions) {
            if (!cmd.hasOption(requiredOption)) {
                formatter.printHelp(usage, options, true);
                System.exit(1);
            }
        }
        
        Prng.seed(Command.getOptionValue(cmd, "seed", Prng.DEFAULT_SEED));
        
        PipelineRunner pipeline = new PipelineRunner();
        try {
            pipeline.run(cmd);
        } catch (ParseException e1) {
            formatter.printHelp(usage, options, true);
            System.exit(1);
        }
    }

}
