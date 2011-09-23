package br.ufpe.cin.emergo.core;

import static dk.au.cs.java.compiler.Util.showPhaseProgress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jgrapht.DirectedGraph;

import br.ufpe.cin.emergo.graph.DependencyNode;
import br.ufpe.cin.emergo.graph.IntermediateDependencyGraphBuilder;
import br.ufpe.cin.emergo.graph.ValueContainerEdge;
import dk.au.cs.java.compiler.ErrorType;
import dk.au.cs.java.compiler.Errors;
import dk.au.cs.java.compiler.Flags;
import dk.au.cs.java.compiler.Main;
import dk.au.cs.java.compiler.SourceError;
import dk.au.cs.java.compiler.analysis.DepthFirstAdapter;
import dk.au.cs.java.compiler.cfg.ControlFlowGraph;
import dk.au.cs.java.compiler.cfg.analysis.PointVisitor;
import dk.au.cs.java.compiler.cfg.gen.CFGGenerator;
import dk.au.cs.java.compiler.cfg.point.Point;
import dk.au.cs.java.compiler.check.DisambiguationCheck;
import dk.au.cs.java.compiler.check.EnvironmentsCheck;
import dk.au.cs.java.compiler.check.HierarchyCheck;
import dk.au.cs.java.compiler.check.TypeCheckingCheck;
import dk.au.cs.java.compiler.check.TypeLinkingCheck;
import dk.au.cs.java.compiler.check.WeedingCheck;
import dk.au.cs.java.compiler.ifdef.IfDefBDDAssigner;
import dk.au.cs.java.compiler.ifdef.IfDefUtil;
import dk.au.cs.java.compiler.ifdef.IfDefVarSet;
import dk.au.cs.java.compiler.ifdef.SharedSimultaneousAnalysis;
import dk.au.cs.java.compiler.lexer.Lexer;
import dk.au.cs.java.compiler.lexer.LexerException;
import dk.au.cs.java.compiler.node.ACompilationUnit;
import dk.au.cs.java.compiler.node.AIfdefStm;
import dk.au.cs.java.compiler.node.AMethodDecl;
import dk.au.cs.java.compiler.node.AProgram;
import dk.au.cs.java.compiler.node.Start;
import dk.au.cs.java.compiler.node.TEndif;
import dk.au.cs.java.compiler.node.TIdentifier;
import dk.au.cs.java.compiler.node.Token;
import dk.au.cs.java.compiler.parser.Parser;
import dk.au.cs.java.compiler.parser.ParserException;
import dk.au.cs.java.compiler.phases.Disambiguation;
import dk.au.cs.java.compiler.phases.Environments;
import dk.au.cs.java.compiler.phases.Hierarchy;
import dk.au.cs.java.compiler.phases.Reachability;
import dk.au.cs.java.compiler.phases.Resources;
import dk.au.cs.java.compiler.phases.TargetResolver;
import dk.au.cs.java.compiler.phases.TypeChecking;
import dk.au.cs.java.compiler.phases.TypeLinking;
import dk.au.cs.java.compiler.phases.Weeding;
import dk.au.cs.java.compiler.phases.XACTDesugaring;
import dk.au.cs.java.compiler.type.environment.ClassEnvironment;
import dk.au.cs.java.compiler.type.members.Method;
import dk.brics.util.file.WildcardExpander;

/**
 * This class uses the Experimental Java Compiler, by Johnni Winther, as the flow analysis framework.
 * 
 * @author T�rsis
 * 
 */
public class JWCompilerDependencyFinder {

	/**
	 * Holds the dependency graph.
	 */
	private static DirectedGraph<DependencyNode, ValueContainerEdge<ConfigSet>> useDefWeb;
	private static AProgram rootNode;

	/**
	 * Returns a mapping of ifdef's ConfigSet to a Collection o lines inside a file.
	 * 
	 * @param file
	 * @return
	 */
	public static Map<ConfigSet, Collection<Integer>> ifDefBlocks(File file) {
		if (rootNode == null) {
			throw new IllegalStateException("Program has not been parsed yet");
		}

		// The mapping to be returned later.
		final Map<ConfigSet, Collection<Integer>> configSetMapping = new HashMap<ConfigSet, Collection<Integer>>();

		// A stack for stacking up nested ifdef statements.
		final Deque<IfDefVarSet> stack = new ArrayDeque<IfDefVarSet>();

		final String filePath = file.getPath();
		rootNode.apply(new DepthFirstAdapter() {

			@Override
			public void caseACompilationUnit(ACompilationUnit compilationUnit) {
				String file = compilationUnit.getFile().getPath();

				// Iterate over all compilation units looking for the one we are interested (see file argument).
				if (file.equals(filePath)) {

					// If there is a match, iterate over the nodes in this compilation unit.
					compilationUnit.apply(new DepthFirstAdapter() {

						public void caseAIfdefStm(AIfdefStm node) {
							IfDefVarSet varSet = node.getOnTrueSet();
							stack.push(varSet);

							IfDefVarSet configAccumulator = IfDefVarSet.getModel();
							for (IfDefVarSet each : stack) {
								configAccumulator = configAccumulator.and(each);
							}

							int lineNumber = node.getToken().getLine();
							ConfigSet configSet = new JWCompilerConfigSet(configAccumulator);
							Collection<Integer> lineCollection = configSetMapping.get(configSet);
							if (lineCollection == null) {
								lineCollection = new HashSet<Integer>();
								lineCollection.add(lineNumber);
								configSetMapping.put(configSet, lineCollection);
							} else {
								lineCollection.add(lineNumber);
							}

							node.getThenBody().apply(this);
							stack.pop();
						}
					});
				}
			}
		});

		return configSetMapping;

	}

	public static DirectedGraph<DependencyNode, ValueContainerEdge<ConfigSet>> generateDependencyGraph(final SelectionPosition selectionPosition, Map<Object, Object> options) throws EmergoException {
		// Resets compiler status.
		Main.resetCompiler();
		useDefWeb = null;
		rootNode = null;

		// The file in which the selection resides.
		File selectionFile;
		selectionFile = new File(selectionPosition.getFilePath());
		if (!selectionFile.exists()) {
			throw new EmergoException("File " + selectionPosition.getFilePath() + " not found.");
		}

		String rootpath = (String) options.get("rootpath");
		File ifdefSpecFile = new File(rootpath + File.separator + "ifdef.txt");
		if (!ifdefSpecFile.exists()) {
			throw new RuntimeException("The ifdef.txt of the project was not found at " + rootpath);
		}

		// Holds a the list of Files to be parsed by the compiler.
		List<File> files = new ArrayList<File>();

		/*
		 * Builds the classpath in the format needed on Johnni Winther's compiler. Paths should be separated by a
		 * whitespace and may contain wildcards like ** and *.
		 * 
		 * For example:
		 * 
		 * ./src/**|/*.java ./tst/my/folder/*.java
		 * 
		 * (Ignore the '|' above)
		 */
		@SuppressWarnings("unchecked")
		List<File> classpath = (List<File>) options.get("classpath");

		for (File file : classpath) {
			if (file.isDirectory()) {
				String filepath = file.getPath() + File.separator + "**" + File.separator + "*.java";
				List<File> expandWildcards = WildcardExpander.expandWildcards(filepath);
				files.addAll(expandWildcards);

			} else if (file.isFile() && file.exists()) {
				// XXX also include .jar files.
			}
		}

		/*
		 * XXX find out classpath
		 * 
		 * XXX WARNING! This static method causes the Feature Model, among other things, to be RESETED. It SHOULD NOT be
		 * called AFTER the parsing of the ifdef specification file in any circustance.
		 */
		ClassEnvironment.init(System.getenv("CLASSPATH"), true);

		EnumSet<Flags> flags = (EnumSet<Flags>) Main.FLAGS;
		flags.add(Flags.IFDEF);
		SharedSimultaneousAnalysis.useSharedSetStrategy(true);
		IfDefUtil.parseIfDefSpecification(ifdefSpecFile);

		IfDefVarSet.getIfDefBDDFactory();

		// The root point of the parsed program.
		rootNode = parseProgram(files);

		// XXX: Surprisingly, this is necessary to prevent the compiler from throwing a NPE.
		Main.program = rootNode;

		// XXX I don't know what this is for yet...
		rootNode.setOptionalInvariant(true);

		/*
		 * Apply compiler phases to the root node.
		 * 
		 * TODO: Check if some of these can be removed to speed things up.
		 */
		try {
			Errors.check();
			rootNode.apply(new Weeding());
			Errors.check();
			rootNode.apply(new WeedingCheck());
			Errors.check();
			rootNode.apply(new IfDefBDDAssigner());
			Errors.check();
			rootNode.apply(new Environments());
			Errors.check();
			rootNode.apply(new EnvironmentsCheck());
			Errors.check();
			rootNode.apply(new TypeLinking());
			Errors.check();
			rootNode.apply(new TypeLinkingCheck());
			Errors.check();
			rootNode.apply(new Hierarchy());
			Errors.check();
			rootNode.apply(new HierarchyCheck());
			Errors.check();
			rootNode.apply(new Disambiguation());
			Errors.check();
			rootNode.apply(new DisambiguationCheck());
			Errors.check();
			rootNode.apply(new TargetResolver());
			Errors.check();
			rootNode.apply(new Reachability());
			Errors.check();

			// Un/Comment line below to en/disable constant folding optimization.
			// node.apply(new ConstantFolding());
			// Errors.check();

			rootNode.apply(new TypeChecking());
			Errors.check();
			rootNode.apply(new TypeCheckingCheck());
			Errors.check();
			rootNode.apply(new CFGGenerator());
			Errors.check();

			// FIXME: something goes wrong in the PromotionInference.
			// node.apply(new PromotionInference());
			// Errors.check();

			rootNode.apply(new XACTDesugaring());
			Errors.check();
			rootNode.apply(new Resources());
			Errors.check();
		} catch (SourceError ex) {
			ex.printStackTrace();
			throw new EmergoException("Compilation error: " + ex.getMessage());
		}

		// Information about the user selection.
		/*
		 * XXX the +1 heres indicate that there is some coupling, for this class knows how the information is passed
		 * from the client
		 */
		final int selectionStartLine = selectionPosition.getStartLine() + 1;
		final int selecionEndLine = selectionPosition.getEndLine() + 1;

		final Set<Point> pointsInUserSelection = new HashSet<Point>();

		/*
		 * Finds out in which method the user selection happened based on the information inside the SelectionPosition
		 * instance.
		 */
		final AMethodDecl[] methodBox = new AMethodDecl[1];
		final String filePath = selectionPosition.getFilePath();
		rootNode.apply(new DepthFirstAdapter() {
			private boolean found = false;

			@Override
			public void caseACompilationUnit(ACompilationUnit cUnit) {
				String file = cUnit.getFile().getPath();
				if (!found && file.equals(filePath)) {

					final Token[] tokenBox = new Token[1];

					cUnit.apply(new DepthFirstAdapter() {
						@Override
						public void defaultToken(Token defaultToken) {
							if (tokenBox[0] != null)
								return;
							int line = defaultToken.getLine();
							if (line >= selectionStartLine && line <= selecionEndLine) {
								tokenBox[0] = defaultToken;
							}
						}
					});

					methodBox[0] = tokenBox[0].getAncestor(AMethodDecl.class);
					found = true;
				}
			}
		});

		AMethodDecl methodDecl = methodBox[0];
		if (methodDecl == null) {
			throw new IllegalArgumentException("Could not find enclosing method for the selection");
		}
		Method method = methodDecl.getMethod();

		ControlFlowGraph cfg = method.getControlFlowGraph();

		/*
		 * Find which Nodes are within the boundaries of the user selection.
		 * 
		 * TODO: use the column information for a more precise set.
		 */
		cfg.apply(new PointVisitor<Object, Object>() {
			@Override
			protected Object defaultPoint(Point point, Object question) {
				Token token = point.getToken();
				int line = token.getLine();
				if (line >= selectionStartLine && line <= selecionEndLine) {
					pointsInUserSelection.add(point);
				}
				return null;
			}
		});

		/*
		 * Now that there is enough information about the selection and the CFGs have been generated, create the
		 * intermediate dependency graph.
		 */
		boolean interprocedural = false;
		if (interprocedural) {
			useDefWeb = IntermediateDependencyGraphBuilder.buildInterproceduralGraph(rootNode, cfg, pointsInUserSelection);
		} else {
			useDefWeb = IntermediateDependencyGraphBuilder.buildIntraproceduralGraph(rootNode, cfg, pointsInUserSelection, methodDecl);
		}

		return useDefWeb;
	}

	// XXX this method was copied from dk...compiler.Main. Go through it again.
	private static AProgram parseProgram(List<File> sourceFiles) {
		final List<ACompilationUnit> sources = new /* CopyOnWrite */ArrayList<ACompilationUnit>();

		// LinkedList<Thread> threads = new LinkedList<Thread>();
		for (final File file : sourceFiles) {
			/*
			 * Thread thread = new Thread() { public void run() {
			 */
			try {
				showPhaseProgress();
				FileInputStream fis = new FileInputStream(file);
				Parser parser = new Parser(new Lexer(fis));
				Start startsym = parser.parse();
				fis.close();
				ACompilationUnit cu = startsym.getCompilationUnit();
				cu.setToken(new TIdentifier(file.getPath(), 0, 0));
				cu.setFile(file);
				sources.add(cu);
			} catch (FileNotFoundException e) {
				Errors.errorMessage(ErrorType.FILE_OPEN_ERROR, "File " + file.getPath() + " not found");
				Errors.check(); // no use in parsing of not all files can be found
			} catch (LexerException e) {
				Errors.error(ErrorType.LEXER_EXCEPTION, file, e.getLine(), e.getPos(), "Syntax error at " + file.getPath() + " " + e.getMessage(), false);
			} catch (ParserException e) {
				Errors.error(ErrorType.PARSER_EXCEPTION, file, e.getToken().getLine(), e.getToken().getPos(), "Syntax error at " + file.getPath() + " " + e.getMessage(), false);
			} catch (IOException e) {
				Errors.errorMessage(ErrorType.IO_ERROR, "Error reading file " + file.getPath() + ": " + e.getMessage());
				Errors.check(); // no use in parsing of not all files can be read
			}
		}
		/*
		 * }; threads.add(thread); thread.start(); } for (Thread thread : threads) { try { thread.join(); } catch
		 * (InterruptedException e) { } }
		 */
		AProgram node = new AProgram(new TIdentifier("AProgram", 0, 0), sources);
		node.setOptionalInvariant(true); // enables the runtime tree
											// invariant
		return node;
	}
}