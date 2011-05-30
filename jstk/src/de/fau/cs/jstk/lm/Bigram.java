/*
	Copyright (c) 2009-2011
		Speech Group at Informatik 5, Univ. Erlangen-Nuremberg, GERMANY
		Korbinian Riedhammer
		Tobias Bocklet
		Stephan Steidl
		Florian Hoenig

	This file is part of the Java Speech Toolkit (JSTK).

	The JSTK is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	The JSTK is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with the JSTK. If not, see <http://www.gnu.org/licenses/>.
*/

package de.fau.cs.jstk.lm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import de.fau.cs.jstk.arch.TokenHierarchy;
import de.fau.cs.jstk.arch.TokenTree;
import de.fau.cs.jstk.arch.Tokenization;
import de.fau.cs.jstk.arch.Tokenizer;
import de.fau.cs.jstk.arch.TreeNode;
import de.fau.cs.jstk.exceptions.OutOfVocabularyException;

/**
 * The Bigram class loads SRILM-style n-gram files to produce a full decoding
 * network.
 * 
 * @author sikoried
 *
 */
public class Bigram implements LanguageModel {
	/** logger instance */
	private static Logger logger = Logger.getLogger(Bigram.class);
			
	/** The default language model probability for OOV words */
	public static final double DEFAULT_OOV = 0.001;
	
	/** out-of-vocabulary probability */
	private double oovProb = DEFAULT_OOV;
	
	private Tokenizer tok;
	private TokenHierarchy th;
	private HashMap<Tokenization, Double> sils;
	private HashMap<N1gram, Double> p1 = new HashMap<N1gram, Double>();
	private HashMap<N2gram, Double> p2 = new HashMap<N2gram, Double>();
	
	private static class N1gram {
		Tokenization t;
		double p;
		double backoff;
		N1gram(Tokenization t, double p, double backoff) {
			this.t = t;
			this.p = p;
			this.backoff = backoff;
		}
		public boolean equals(Object o) {
			if (o instanceof N1gram) 
				return t.equals(((N1gram) o).t);
			else
				return false;	
		}
	}
	
	private static class N2gram {
		Tokenization ta, tb;
		double p;
		
		// up to now, we have no application for this one
		@SuppressWarnings("unused")
		double backoff;
		N2gram(Tokenization ta, Tokenization tb, double p, double backoff) {
			this.ta = ta;
			this.tb = tb;
			this.p = p;
			this.backoff = backoff;
		}
		public boolean equals(Object o ) {
			if (o instanceof N2gram) 
				return ta.equals(((N2gram) o).ta) && tb.equals(((N2gram) o).tb);
			else
				return false;
		}
	}
	
	/**
	 * Generate a new Zerogram for all words in the given Tokenizer. The words
	 * are uniformly weighted after subtraction of the silence probability mass.
	 * 
	 * @param tokenizer
	 * @param hierarchy
	 * @param sils
	 */
	public Bigram(Tokenizer tokenizer, TokenHierarchy hierarchy, HashMap<Tokenization, Double> sils) {
		this.tok = tokenizer;
		this.th = hierarchy;
		this.sils = sils;
	}
	
	/**
	 * Load uni-gram Probabilities froom SRILM-stype LM file
	 * @param file
	 * @throws IOException
	 */
	public void loadSrilm(File file) throws IOException, OutOfVocabularyException {
		BufferedReader br = new BufferedReader(new FileReader(file));
		String lin;
		
		// skip everything till \1-gram
		while ((lin = br.readLine()) != null) {
			if (lin.equals("\\1-grams:"))
				break;
		}
		
		// now read everything till next thing starts with a backslash
		while ((lin = br.readLine()) != null) {
			if (lin.startsWith("\\"))
				break;
			if (lin.trim().length() < 3)
				continue;
			String [] sp = lin.trim().split("\\s+");
			
			// ignore words not in the tokenizer
			if (!tok.validate(sp[1]))
				continue;
			
			// set the prob, mind the exponentiation!
			N1gram ng = new N1gram(
					tok.getWordTokenization(sp[1]), 
					Math.pow(10, Double.parseDouble(sp[0])), 
					sp.length > 2 ? Math.pow(10, Double.parseDouble(sp[2])) : 0.);
			
			p1.put(ng, ng.p);
		}
		
		logger.info("loaded " + p1.size() + " uni-grams");
		
		if (!lin.equals("\\2-grams:"))
			while ((lin = br.readLine()) != null && !lin.equals("\\2-grams:"))
				;
		
		// read bi -grams
		while ((lin = br.readLine()) != null) {
			if (lin.startsWith("\\"))
				break;
			if (lin.trim().length() < 3)
				continue;
			
			String [] sp = lin.trim().split("\\s+");
			
			// ignore words not in the tokenizer
			if (!tok.validate(sp[1]))
				continue;
			
			// set the prob, mind the exponentiation!
			N2gram ng = new N2gram(
					tok.getWordTokenization(sp[1]), 
					tok.getWordTokenization(sp[2]),
					Math.pow(10, Double.parseDouble(sp[0])), 
					sp.length > 3 ? Math.pow(10, Double.parseDouble(sp[3])) : 0.);
			
			p2.put(ng, ng.p);
		}
		
		logger.info("loaded " + p2.size() + " bi-grams");
	}
	
	public TreeNode generateNetwork() {
		int treeId = 0;
		
		// step 1: build an LST with uni-gram language model weights
		logger.info("building initial uni-gram tree");
		TokenTree unigram = new TokenTree(treeId++);
		
		// add silences
		for (Map.Entry<Tokenization, Double> e : sils.entrySet())
			unigram.addToTree(e.getKey(), th.tokenizeWord(e.getKey().sequence), e.getValue());
		
		// unigrams
		for (Tokenization t : tok.tokenizations) {
			Double p = p1.get(new N1gram(t, 0, 0));
			if (p == null)
				p = oovProb;
			
			unigram.addToTree(t, th.tokenizeWord(t.sequence), p);
		}
		
		unigram.factorLanguageModelWeights();
		
		// -%<------------------------------------------------------------------
		// actual network generation 
		// -%<------------------------------------------------------------------
			
		// bi-gram model with optional silences!
		logger.info("building bi-gram LSTs");
		
		// we will need as many TokenTrees as we have words in the Tokenizer
		TokenTree [] ttr = new TokenTree [tok.tokenizations.size()];
		HashMap<String, TokenTree> lut = new HashMap<String, TokenTree>();
		int i = 0;
		for (Tokenization t : tok.tokenizations) {
			ttr[i] = new TokenTree(treeId++);
			lut.put(t.word, ttr[i]);
			i++;
		}
		
		for (Tokenization t : tok.tokenizations) {
			// silences are handled separately
			if (sils.containsKey(t))
				continue;

			// build tree with bi-gram weights
			TokenTree tree = lut.get(t.word);
			for (Tokenization right : tok.tokenizations) {
				double prob;
				if (sils.containsKey(right)) {
					// fixed silence prob
					prob = sils.get(right);
				} else {
						// get counted or back-off bi-gram probability
						N2gram bigram = new N2gram(t, right, 0, 0);
						
						Double wt = p2.get(bigram);
						if (wt == null) {
							N1gram n1 = new N1gram(t, 0, 0);
							N1gram n2 = new N1gram(right, 0, 0);
						
							// locate the "real" N1gram
							boolean f1 = false;
							boolean f2 = false;
							for (N1gram ng : p1.keySet()) {
								if (n1.equals(ng)) {
									n1 = ng;
									f1 = true;
								} else if (n2.equals(ng)) {
									n2 = ng;
									f2 = true;
								}
								if (f1 && f2)
									break;
							}
							
							double n1bow = (n1.p == 0. ? oovProb : n1.backoff);
							double b2prob = (n2.p == 0. ? oovProb : n2.p);
						
							prob = n1bow * b2prob;
						} else
							prob = wt;
				}
				
				// add to tree
				tree.addToTree(right, th.tokenizeWord(right.sequence), prob);
			}
			
			// factor
			tree.factorLanguageModelWeights();
			
			// link LSTs
			for (TreeNode n : tree.leaves()) {
				if (sils.containsKey(n.word))
					n.setLst(tree.root);
				else
					n.setLst(lut.get(n.word.word).root);
			}
			
			if (i % 100 == 0) {
				System.gc();
				logger.info(i + " mem usage=" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1024/1024 + "M");
			}
		}

		return unigram.root;
	}

}
