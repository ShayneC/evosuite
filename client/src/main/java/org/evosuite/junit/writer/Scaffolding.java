package org.evosuite.junit.writer;

import static org.evosuite.junit.writer.TestSuiteWriterUtils.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringEscapeUtils;
import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.instrumentation.BytecodeInstrumentation;
import org.evosuite.runtime.ClassStateSupport;
import org.evosuite.runtime.GuiSupport;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.agent.InstrumentingAgent;
import org.evosuite.runtime.reset.ClassResetter;
import org.evosuite.runtime.reset.ResetManager;
import org.evosuite.runtime.sandbox.Sandbox;
import org.evosuite.runtime.util.SystemInUtil;
import org.evosuite.testcase.ExecutionResult;

/**
 * Class used to generate all the scaffolding code that ends up in methods like @After/@Before
 * and that are used to setup the EvoSuite framework (eg mocking of classes, reset of static
 * state)
 * @author arcuri
 *
 */
public class Scaffolding {

	public static final String EXECUTOR_SERVICE = "executor";

	private static final String DEFAULT_PROPERTIES = "defaultProperties";


	/**
	 * Return full JUnit code for scaffolding file for the give test 
	 * 
	 * @param testName 
	 * @return
	 */
	public static String getScaffoldingFileContent(String testName, List<ExecutionResult> results, boolean wasSecurityException){

		String name = getFileName(testName);

		StringBuilder builder = new StringBuilder();

		builder.append(getHeader(name, results, wasSecurityException));		
		builder.append(new Scaffolding().getBeforeAndAfterMethods(name, wasSecurityException, results));		
		builder.append(getFooter());

		return builder.toString();
	}

	protected static String getFooter() {
		return "}\n";
	}

	protected static String getHeader(String name, List<ExecutionResult> results, boolean wasSecurityException) {
		StringBuilder builder = new StringBuilder();
		builder.append("/**\n");
		builder.append(" * Scaffolding file used to store all the setups needed to run \n");
		builder.append(" * tests automatically generated by EvoSuite\n");
		builder.append(" * "+new Date()+"\n");
		builder.append(" */\n\n");

		if (!Properties.CLASS_PREFIX.equals("")) {
			builder.append("package ");
			builder.append(Properties.CLASS_PREFIX);
			builder.append(";\n");
		}
		builder.append("\n");

		for (String imp : getScaffoldingImports(wasSecurityException, results)) {
			builder.append("import ");
			builder.append(imp);
			builder.append(";\n");
		}
		builder.append("\n");

		builder.append(TestSuiteWriterUtils.getAdapter().getClassDefinition(name));		
		builder.append(" {\n");

		return builder.toString();
	}

	public static String getFileName(String testName) throws IllegalArgumentException{
		if(testName==null || testName.isEmpty()){
			throw new IllegalArgumentException("Empty test name");
		}
		return testName + "_" + Properties.SCAFFOLDING_SUFFIX;
	}

	/**
	 * Return all classes for which we need an import statement
	 * 
	 * @param wasSecurityException
	 * @param results
	 * @return
	 */
	public static List<String> getScaffoldingImports(boolean wasSecurityException, List<ExecutionResult> results){
		List<String> list = new ArrayList<String>();

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
				|| Properties.RESET_STATIC_FIELDS || wasSecurityException
				|| SystemInUtil.getInstance().hasBeenUsed()) {
			list.add(org.junit.BeforeClass.class.getCanonicalName());
			list.add(org.junit.Before.class.getCanonicalName());
			list.add(org.junit.After.class.getCanonicalName());
		}

		if (wasSecurityException || TestSuiteWriterUtils.shouldResetProperties(results)) {
			list.add(org.junit.AfterClass.class.getCanonicalName());
		}


		if (wasSecurityException) {
			list.add(Sandbox.class.getCanonicalName());
			list.add(Sandbox.SandboxMode.class.getCanonicalName());
			list.add(java.util.concurrent.ExecutorService.class.getCanonicalName());
			list.add(java.util.concurrent.Executors.class.getCanonicalName());
			list.add(java.util.concurrent.Future.class.getCanonicalName());
			list.add(java.util.concurrent.TimeUnit.class.getCanonicalName());
		}

		return list;
	}

	/**
	 * Get the code of methods for @BeforeClass, @Before, @AfterClass and
	 * 
	 * @After.
	 * 
	 *         <p>
	 *         In those methods, the EvoSuite framework for running the
	 *         generated test cases is handled (e.g., use of customized
	 *         SecurityManager and runtime bytecode replacement)
	 * 
	 * @return
	 */
	public String getBeforeAndAfterMethods(String name, boolean wasSecurityException,
			List<ExecutionResult> results) {

		/*
		 * Usually, we need support methods (ie @BeforeClass,@Before,@After and @AfterClass)
		 * only if there was a security exception (and so we need EvoSuite security manager,
		 * and test runs on separated thread) or if we are doing bytecode replacement (and
		 * so we need to activate JavaAgent).
		 * 
		 * But there are cases that we might always want: eg, setup logging
		 */

		StringBuilder bd = new StringBuilder("");
		bd.append("\n");

		/*
		 * Because this method is perhaps called only once per SUT,
		 * not much of the point to try to optimize it 
		 */

		//TODO put it back once its side-effects on Sandbox are fixed
		//generateTimeoutRule(bd);

		generateFields(bd, wasSecurityException, results);

		generateBeforeClass(bd, wasSecurityException);

		generateAfterClass(bd, wasSecurityException, results);

		generateBefore(bd, wasSecurityException, results);

		generateAfter(bd, wasSecurityException);

		generateSetSystemProperties(bd, results);

		if (Properties.RESET_STATIC_FIELDS) {
			generateInitializeClasses(name, bd);
			generateResetClasses(bd);
		}

		return bd.toString();
	}

	/**
	 * Hanging tests have very, very high negative impact.
	 * They can mess up everything (eg when running "mvn test").
	 * As such, we should always have timeouts.
	 * Adding timeouts only in certain conditions is too risky
	 * 
	 * @param bd
	 */
	private void generateTimeoutRule(StringBuilder bd) {
		bd.append(METHOD_SPACE);
		bd.append("@org.junit.Rule \n");
		bd.append(METHOD_SPACE);
		int timeout = Properties.TIMEOUT + 1000;
		bd.append("public org.junit.rules.Timeout globalTimeout = new org.junit.rules.Timeout("+timeout+"); \n");
		bd.append("\n");
	}

	private void generateResetClasses(StringBuilder bd) {
		List<String> classesToReset = ResetManager.getInstance().getClassResetOrder();

		bd.append("\n");
		bd.append(METHOD_SPACE);
		bd.append("private static void resetClasses() {\n");

		if(classesToReset.size()!=0){			
			bd.append(BLOCK_SPACE);
			bd.append(ClassStateSupport.class.getName()+".resetClasses(");
						
			for (int i = 0; i < classesToReset.size(); i++) {
				String className = classesToReset.get(i);						
				bd.append("\n"+INNER_BLOCK_SPACE+"\""+className+"\"");
				if(i<classesToReset.size()-1){
					bd.append(",");
				}
			}
			
			bd.append("\n");
			bd.append(BLOCK_SPACE);
			bd.append(");\n");
		}
		
		bd.append(METHOD_SPACE);
		bd.append("}" + "\n");

		/*
		bd.append(BLOCK_SPACE);				
		bd.append("String[] classNames = new String[" + classesToReset.size() + "];\n");

		for (int i = 0; i < classesToReset.size(); i++) {
			String className = classesToReset.get(i);
			bd.append(BLOCK_SPACE);
			bd.append(String.format("classNames[%s] =\"%s\";\n", i, className));
		}

		bd.append(BLOCK_SPACE);
		bd.append("for (int i=0; i< classNames.length;i++) {\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("String classNameToReset = classNames[i];\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("try {" + "\n");

		bd.append(INNER_INNER_BLOCK_SPACE);
		bd.append(ClassResetter.class.getCanonicalName()
				+ ".getInstance().reset(classNameToReset); \n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("} catch (Throwable t) {" + "\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("}\n");

		bd.append(BLOCK_SPACE);
		bd.append("}\n");

		bd.append(METHOD_SPACE);
		bd.append("}" + "\n");
		*/
	}

	private void generateInitializeClasses(String testClassName, StringBuilder bd) {
		
		List<String> classesToBeReset = ResetManager.getInstance().getClassResetOrder(); 

		bd.append("\n");
		bd.append(METHOD_SPACE);
		bd.append("private static void initializeClasses() {\n");

		if(classesToBeReset.size()!=0){			
			bd.append(BLOCK_SPACE);
			bd.append(ClassStateSupport.class.getName()+".initializeClasses(");
			bd.append(testClassName+ ".class.getClassLoader() ");

			for (int i = 0; i < classesToBeReset.size(); i++) {
				String className = classesToBeReset.get(i);
				if (! BytecodeInstrumentation.checkIfCanInstrument(className)) {
					continue;
				}			
				bd.append(",\n"+INNER_BLOCK_SPACE+"\""+className+"\"");
			}
			bd.append("\n");
			bd.append(BLOCK_SPACE);
			bd.append(");\n");
		}

		
		bd.append("\n");

		List<String> allInstrumentedClasses = TestGenerationContext.getInstance().getClassLoaderForSUT().getViewOfInstrumentedClasses();
				
		//this have to be done AFTER the classes have been loaded in a specific order
		bd.append(BLOCK_SPACE);		
		bd.append(ClassStateSupport.class.getName()+".retransformIfNeeded(");
		bd.append(testClassName+ ".class.getClassLoader()");

		for(int i=0; i<allInstrumentedClasses.size(); i++){
			String s = allInstrumentedClasses.get(i);
			bd.append(",\n");
			bd.append(INNER_BLOCK_SPACE);
			bd.append("\""+s+"\"");
		}
		bd.append("\n");
		bd.append(BLOCK_SPACE);
		bd.append(");\n"); 
		
		bd.append(METHOD_SPACE);
		bd.append("} \n");


		/*
		bd.append(BLOCK_SPACE);
		bd.append("String[] classNames = new String[" + classesToBeReset.size() + "];\n");

		for (int i = 0; i < classesToBeReset.size(); i++) {
			String className = classesToBeReset.get(i);
			if (BytecodeInstrumentation.checkIfCanInstrument(className)) {
				bd.append(BLOCK_SPACE);
				bd.append(String.format("classNames[%s] =\"%s\";\n", i, className));
			}
		}

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
		        || Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append(InstrumentingAgent.class.getName()+".activate(); \n");
		}

		bd.append(BLOCK_SPACE);
		bd.append("for (int i=0; i< classNames.length;i++) {\n");

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
		        || Properties.RESET_STATIC_FIELDS) {
			bd.append(INNER_BLOCK_SPACE);
			bd.append(org.evosuite.runtime.Runtime.class.getName()+".getInstance().resetRuntime(); \n");
		}

		bd.append(INNER_BLOCK_SPACE);
		bd.append("String classNameToLoad = classNames[i];\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("ClassLoader classLoader = " + testClassName
		        + ".class.getClassLoader();\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("try {" + "\n");

		bd.append(INNER_INNER_BLOCK_SPACE);
		bd.append("Class.forName(classNameToLoad, true, classLoader);\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("} catch (ExceptionInInitializerError ex) {" + "\n");

		bd.append(INNER_INNER_BLOCK_SPACE);
		bd.append("java.lang.System.err.println(\"Could not initialize \" + classNameToLoad);\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("} catch (Throwable t) {" + "\n");

		bd.append(INNER_BLOCK_SPACE);
		bd.append("}\n");

		bd.append(BLOCK_SPACE);
		bd.append("}\n");

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
		        || Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append(InstrumentingAgent.class.getName()+".deactivate(); \n");
		}

		bd.append(METHOD_SPACE);
		bd.append("}" + "\n");
		 */
	}

	private void generateAfter(StringBuilder bd, boolean wasSecurityException) {

		if (!Properties.RESET_STANDARD_STREAMS && !wasSecurityException
				&& !Properties.REPLACE_CALLS && !Properties.VIRTUAL_FS
				&& !Properties.RESET_STATIC_FIELDS) {
			return;
		}

		bd.append(METHOD_SPACE);
		bd.append("@After \n");
		bd.append(METHOD_SPACE);
		bd.append("public void doneWithTestCase(){ \n");

		if (Properties.RESET_STANDARD_STREAMS) {
			bd.append(BLOCK_SPACE);
			bd.append("java.lang.System.setErr(systemErr); \n");

			bd.append(BLOCK_SPACE);
			bd.append("java.lang.System.setOut(systemOut); \n");

			bd.append(BLOCK_SPACE);
			bd.append("DebugGraphics.setLogStream(logStream); \n");
		}

		if (Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append("resetClasses(); \n");
		}

		if (wasSecurityException) {
			bd.append(BLOCK_SPACE);
			bd.append(Sandbox.class.getName()+".doneWithExecutingSUTCode(); \n");
		}

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
				|| Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append(InstrumentingAgent.class.getName()+".deactivate(); \n");
		}

		//TODO: see comment in @Before 
		bd.append(BLOCK_SPACE);
		bd.append(org.evosuite.runtime.GuiSupport.class.getName()+".restoreHeadlessMode(); \n");


		bd.append(METHOD_SPACE);
		bd.append("} \n");

		bd.append("\n");
	}

	private void generateBefore(StringBuilder bd, boolean wasSecurityException,
			List<ExecutionResult> results) {

		if (!Properties.RESET_STANDARD_STREAMS && !TestSuiteWriterUtils.shouldResetProperties(results)
				&& !wasSecurityException && !Properties.REPLACE_CALLS
				&& !Properties.VIRTUAL_FS && !Properties.RESET_STATIC_FIELDS
				&& !SystemInUtil.getInstance().hasBeenUsed()) {
			return;
		}

		bd.append(METHOD_SPACE);
		bd.append("@Before \n");
		bd.append(METHOD_SPACE);
		bd.append("public void initTestCase(){ \n");

		if (Properties.RESET_STANDARD_STREAMS) {
			bd.append(BLOCK_SPACE);
			bd.append("systemErr = java.lang.System.err;");
			bd.append(" \n");

			bd.append(BLOCK_SPACE);
			bd.append("systemOut = java.lang.System.out;");
			bd.append(" \n");

			bd.append(BLOCK_SPACE);
			bd.append("logStream = DebugGraphics.logStream();");
			bd.append(" \n");
		}

		if (TestSuiteWriterUtils.shouldResetProperties(results)) {
			bd.append(BLOCK_SPACE);
			bd.append("setSystemProperties();");
			bd.append(" \n");
		}

		/*
		 * We do not mock GUI yet, but still we need to make the JUnit tests to 
		 * run in headless mode. Checking if SUT needs headless is tricky: check
		 * for headless exception is brittle if those exceptions are caught before
		 * propagating to test.
		 * 
		 * TODO: These things would be handled once we mock GUI. For the time being
		 * we just always include a reset call if @Before/@After methods are
		 * generated
		 */
		bd.append(BLOCK_SPACE);
		bd.append(org.evosuite.runtime.GuiSupport.class.getName()+".setHeadless(); \n");


		if (wasSecurityException) {
			bd.append(BLOCK_SPACE);
			bd.append(Sandbox.class.getName()+".goingToExecuteSUTCode(); \n");
		}

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
				|| Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append(org.evosuite.runtime.Runtime.class.getName()+".getInstance().resetRuntime(); \n");
			bd.append(BLOCK_SPACE);
			bd.append(InstrumentingAgent.class.getName()+".activate(); \n");
		}

		if (SystemInUtil.getInstance().hasBeenUsed()) {
			bd.append(BLOCK_SPACE);
			bd.append(SystemInUtil.class.getName()+".getInstance().initForTestCase(); \n");
		}

		bd.append(METHOD_SPACE);
		bd.append("} \n");

		bd.append("\n");
	}



	private String getResetPropertiesCommand() {
		return "java.lang.System.setProperties((java.util.Properties)" + " "
				+ DEFAULT_PROPERTIES + ".clone());";
	}

	private void generateAfterClass(StringBuilder bd, boolean wasSecurityException,
			List<ExecutionResult> results) {

		if (wasSecurityException || TestSuiteWriterUtils.shouldResetProperties(results)) {
			bd.append(METHOD_SPACE);
			bd.append("@AfterClass \n");
			bd.append(METHOD_SPACE);
			bd.append("public static void clearEvoSuiteFramework(){ \n");

			if (wasSecurityException) {
				bd.append(BLOCK_SPACE);
				bd.append(EXECUTOR_SERVICE + ".shutdownNow(); \n");
				bd.append(BLOCK_SPACE);
				bd.append("Sandbox.resetDefaultSecurityManager(); \n");
			}

			if (TestSuiteWriterUtils.shouldResetProperties(results)) {
				bd.append(BLOCK_SPACE);
				bd.append(getResetPropertiesCommand());
				bd.append(" \n");
			}

			bd.append(METHOD_SPACE);
			bd.append("} \n");

			bd.append("\n");
		}

	}

	private void generateSetSystemProperties(StringBuilder bd,
			List<ExecutionResult> results) {

		if (!Properties.REPLACE_CALLS) {
			return;
		}

		bd.append(METHOD_SPACE);
		bd.append("public void setSystemProperties() {\n");
		bd.append(" \n");
		if (TestSuiteWriterUtils.shouldResetProperties(results)) {
			/*
			 * even if we set all the properties that were read, we still need
			 * to reset everything to handle the properties that were written 
			 */
			bd.append(BLOCK_SPACE);
			bd.append(getResetPropertiesCommand());
			bd.append(" \n");

			Set<String> readProperties = TestSuiteWriterUtils.mergeProperties(results);
			for (String prop : readProperties) {
				bd.append(BLOCK_SPACE);
				String currentValue = System.getProperty(prop);
				String escaped_prop = StringEscapeUtils.escapeJava(prop);
				if (currentValue != null) {
					String escaped_currentValue = StringEscapeUtils.escapeJava(currentValue);
					bd.append("java.lang.System.setProperty(\"" + escaped_prop + "\", \""
							+ escaped_currentValue + "\"); \n");
				} else {
					/*
					 * In theory, we do not need to clear properties, as that is done with the reset to default.
					 * Avoiding doing the clear is not only good for readability (ie, less commands) but also
					 * to avoid crashes when properties are set based on SUT inputs. Eg, in classes like
					 *  SassToCssBuilder in 108_liferay we ended up with hundreds of thousands set properties... 
					 */
					//bd.append("java.lang.System.clearProperty(\"" + escaped_prop + "\"); \n");
				}
			}
		} else {
			bd.append(BLOCK_SPACE + "/*No java.lang.System property to set*/\n");
		}

		bd.append(METHOD_SPACE);
		bd.append("}\n");

	}

	private void generateBeforeClass(StringBuilder bd, boolean wasSecurityException) {

		if (!wasSecurityException && !Properties.REPLACE_CALLS && !Properties.VIRTUAL_FS
				&& !Properties.RESET_STATIC_FIELDS) {
			return;
		}

		bd.append(METHOD_SPACE);
		bd.append("@BeforeClass \n");

		bd.append(METHOD_SPACE);
		bd.append("public static void initEvoSuiteFramework() { \n");

		// FIXME: This is just commented out for experiments
		//bd.append("org.evosuite.utils.LoggingUtils.setLoggingForJUnit(); \n");

		bd.append(BLOCK_SPACE);
		bd.append(""+GuiSupport.class.getName()+".initialize(); \n");

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
				|| Properties.RESET_STATIC_FIELDS) {
			//need to setup REPLACE_CALLS and instrumentator

			if (Properties.REPLACE_CALLS) {
				bd.append(BLOCK_SPACE);
				bd.append(RuntimeSettings.class.getName()+".mockJVMNonDeterminism = true; \n");
			}

			if (Properties.VIRTUAL_FS) {
				bd.append(BLOCK_SPACE);
				bd.append(RuntimeSettings.class.getName()+".useVFS = true; \n");
			}

			if (Properties.REPLACE_SYSTEM_IN) {
				bd.append(BLOCK_SPACE);
				bd.append(RuntimeSettings.class.getName()+".mockSystemIn = true; \n");
			}

			if (Properties.RESET_STATIC_FIELDS) {
				bd.append(BLOCK_SPACE);
				bd.append(RuntimeSettings.class.getName()+".resetStaticState = true; \n");
			}

			bd.append(BLOCK_SPACE);
			bd.append(InstrumentingAgent.class.getName()+".initialize(); \n");

		}

		if (wasSecurityException) {
			//need to setup the Sandbox mode
			bd.append(BLOCK_SPACE);
			bd.append(RuntimeSettings.class.getName()+".sandboxMode = "+
					Sandbox.SandboxMode.class.getCanonicalName() + "." + Properties.SANDBOX_MODE + "; \n");

			bd.append(BLOCK_SPACE);
			bd.append(Sandbox.class.getName()+".initializeSecurityManagerForSUT(); \n");

			bd.append(BLOCK_SPACE);
			bd.append(EXECUTOR_SERVICE + " = Executors.newCachedThreadPool(); \n");
		}

		if (Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append("initializeClasses();" + "\n");
		}

		if (Properties.REPLACE_CALLS || Properties.VIRTUAL_FS
				|| Properties.RESET_STATIC_FIELDS) {
			bd.append(BLOCK_SPACE);
			bd.append(org.evosuite.runtime.Runtime.class.getName()+".getInstance().resetRuntime(); \n");
		}


		bd.append(METHOD_SPACE);
		bd.append("} \n");

		bd.append("\n");
	}

	private void generateFields(StringBuilder bd, boolean wasSecurityException,
			List<ExecutionResult> results) {

		if (Properties.RESET_STANDARD_STREAMS) {
			bd.append(METHOD_SPACE);
			bd.append("private PrintStream systemOut = null;" + '\n');

			bd.append(METHOD_SPACE);
			bd.append("private PrintStream systemErr = null;" + '\n');

			bd.append(METHOD_SPACE);
			bd.append("private PrintStream logStream = null;" + '\n');
		}

		if (wasSecurityException) {
			bd.append(METHOD_SPACE);
			bd.append("protected static ExecutorService " + EXECUTOR_SERVICE + "; \n");

			bd.append("\n");
		}

		if (TestSuiteWriterUtils.shouldResetProperties(results)) {
			/*
			 * some System properties were read/written. so, let's be sure we ll have the same
			 * properties in the generated JUnit file, regardless of where it will be executed
			 * (eg on a remote CI server). This is essential, as generated assertions might
			 * depend on those properties
			 */
			bd.append(METHOD_SPACE);
			bd.append("private static final java.util.Properties " + DEFAULT_PROPERTIES);
			bd.append(" = (java.util.Properties) java.lang.System.getProperties().clone(); \n");

			bd.append("\n");
		}
	}
}
