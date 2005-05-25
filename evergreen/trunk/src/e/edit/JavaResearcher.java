package e.edit;

import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import e.ptextarea.*;
import e.util.*;

public class JavaResearcher implements WorkspaceResearcher {
    private static final String NEWLINE = "<br>&nbsp;&nbsp;&nbsp;&nbsp;";
    private static final String COMMA = ",&nbsp;";
    
    /** A regular expression matching 'new' expressions. */
    public static final Pattern NEW_PATTERN = Pattern.compile("(?x) .* \\b new \\s+ (\\S+) \\s* \\($");
    
    private static String[] javaDocSummary;
    private static TreeSet<String> uniqueIdentifiers;
    private static TreeSet<String> uniqueWords;
    
    private static JavaResearcher INSTANCE = new JavaResearcher();
    
    private JavaResearcher() {
    }
    
    public synchronized static JavaResearcher getSharedInstance() {
        if (javaDocSummary == null) {
            readJavaDocSummary();
        }
        return INSTANCE;
    }
    
    private synchronized static void readJavaDocSummary() {
        long start = System.currentTimeMillis();
        Log.warn("Reading JavaDoc summary...");
        
        javaDocSummary = StringUtilities.readLinesFromFile(Edit.getResourceFilename("javadoc-summary.txt"));
        
        Pattern identifierPattern = Pattern.compile("^[MCFEA]:(\\S+?)(\\(|\t).*$");
        uniqueIdentifiers = new TreeSet<String>();
        
        int classCount = 0;
        for (String line : javaDocSummary) {
            if (line.startsWith("Class:")) {
                classCount++;
                // Some classes don't have accessible constructors, so add
                // the class name anyway (SwingUtilities is an example).
                String className = line.substring(line.lastIndexOf('.') + 1);
                uniqueIdentifiers.add(className);
            } else {
                // Is it a constructor, method or field definition?
                Matcher matcher = identifierPattern.matcher(line);
                if (matcher.find()) {
                    uniqueIdentifiers.add(matcher.group(1));
                }
            }
        }
        
        initUniqueWords();
        
        long timeTaken = System.currentTimeMillis() - start;
        Log.warn("Read summarized JavaDoc for " + classCount + " classes (" + javaDocSummary.length + " lines, " + uniqueIdentifiers.size() + " unique identifiers) in " + timeTaken + "ms.");
    }
    
    /**
     * Extracts all the unique words from the identifiers in the JDK.
     */
    private synchronized static void initUniqueWords() {
        uniqueWords = new TreeSet<String>();
        for (String identifier : uniqueIdentifiers) {
            String[] words = identifier.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase().split(" ");
            for (String word : words) {
                uniqueWords.add(word);
            }
        }
    }
    
    /**
     * Adds all the unique words from the identifiers in the JDK to the given set.
     * This might be useful for spelling checking or word completion purposes.
     */
    public synchronized static void addJavaWordsTo(Set<String> set) {
        set.addAll(uniqueWords);
    }
    
    public synchronized List<String> listIdentifiersStartingWith(String prefix) {
        ArrayList<String> result = new ArrayList<String>();
        final int prefixLength = prefix.length();
        for (String identifier : uniqueIdentifiers) {
            if (identifier.startsWith(prefix)) {
                result.add(identifier);
            }
        }
        return result;
    }
    
    /**
    * Builds HTML containing useful information
    * about the text around the caret.
    */
    public String makeResult(String text) {
        // Does it look like the user wants help with a constructor?
        boolean listConstructors = false;
        Matcher newMatcher = NEW_PATTERN.matcher(text);
        if (newMatcher.find()) {
            listConstructors = true;
            text = newMatcher.group(1);
        }
        
        // How about a class method or class field?
        boolean listMembers = false;
        Pattern accessPattern = Pattern.compile("(?x) (\\S+) \\.$");
        Matcher accessMatcher = accessPattern.matcher(text);
        if (accessMatcher.find()) {
            listMembers = true;
            text = accessMatcher.group(1);
        }
        
        boolean listClasses = (listConstructors == false) && text.matches("^.*\\b[A-Z]\\S*$");
        
        if (listConstructors == false && listMembers == false && listClasses == false) {
            return makeInstanceMethodOrFieldResult(text);
        }
        
        // At this point, 'text' should be a class name.
        Class[] classes = JavaDoc.getClasses(text);
        if (classes.length == 0) {
            return "";
        }

        StringBuffer result = new StringBuffer();
        for (Class klass : classes) {
            if (result.length() > 0) {
                result.append("<br><br>");
            }
            if (listClasses) {
                result.append(listClass(klass));
            }
            if (listConstructors) {
                result.append(listConstructors(klass));
            }
            if (listMembers) {
                result.append(listMembers(klass));
            }
        }
        return result.toString();
    }
    
    public String makePackageResult(String text) {
        Pattern importPattern = Pattern.compile("import (.+)\\..+;");
        Matcher matcher = importPattern.matcher(text);
        if (matcher.find()) {
            String packageName = matcher.group(1);
            return listPackage(packageName);
        } else {
            return "";
        }
    }
    
    public String makeInstanceMethodOrFieldResult(String text) {
        // FIXME: ideally, we'd find the name of the most recent method with unclosed parentheses.
        // So, in "doSomething(getThing(x), " we'd know that "doSomething" is interesting and "getThing" isn't.
        Pattern namePattern = Pattern.compile("(\\S+)\\s*(\\(?).*");
        Matcher matcher = namePattern.matcher(text);
        if (matcher.find()) {
            String methodOrFieldName = matcher.group(1);
            return listMethodsOrFields(methodOrFieldName);
        } else {
            return "";
        }
    }
    
    /**
     * Returns HTML linking to all the classes in the given package.
     */
    public synchronized String listPackage(String packageName) {
        StringBuffer result = new StringBuffer(packageName + " contains:\n");
        String searchTerm = "Class:" + packageName + ".";
        String htmlFile = "";
        for (String line : javaDocSummary) {
            if (line.startsWith("File:")) {
                htmlFile = line.substring(5);
            } else if (line.startsWith(searchTerm)) {
                String className = line.substring(searchTerm.length());
                /* Just check it is actually a class in this package, and not a class in a sub-package. */
                if (Character.isUpperCase(className.charAt(0))) {
                    result.append("<br><a href=\"" + urlFromHtmlFile(htmlFile) + "\">" + className + "</a>\n");
                }
            }
        }
        return result.toString();
    }
    
    private String urlFromHtmlFile(String htmlFile) {
        if (FileUtilities.exists(htmlFile)) {
            // The file exists locally, so we can use it.
            return "file://" + htmlFile;
        } else {
            // The file doesn't exist locally, so we'll have to get it direct from Sun.
            return Parameters.getParameter("java.advisor.doc") + htmlFile.replaceAll("^.*/api/", "/");
        }
    }
    
    public synchronized String listMethodsOrFields(String name) {
        StringBuffer result = new StringBuffer();
        Pattern pattern = Pattern.compile("^[MF]:(" + StringUtilities.regularExpressionFromLiteral(name) + "[^(\t]*)(\\([^\t]+)\t");
        Matcher matcher;
        String htmlFile = "";
        String className = "";
        for (String line : javaDocSummary) {
            if (line.startsWith("File:")) {
                htmlFile = line.substring(5);
            } else if (line.startsWith("Class:")) {
                className = line.substring(6);
            } else if ((matcher = pattern.matcher(line)).find()) {
                String url = urlFromHtmlFile(htmlFile);
                result.append("<br><a href=\"" + url + "#" + matcher.group(1) + matcher.group(2) + "\">" + matcher.group(1) + "</a> in <a href=\"" + url + "\">" + className + "</a>\n");
            }
        }
        if (result.length() == 0) {
            return "";
        }
        result.insert(0, name + " could be:\n");
        return result.toString();
    }
    
    /** Formats class names as a list of links if there's javadoc for them. */
    public String makeClassLinks(Class[] classes, boolean pkg, String conjunct, boolean showSourceLink) {
        StringBuffer s = new StringBuffer();
        for (Class klass : classes) {
            if (s.length() > 0) {
                s.append(conjunct);
            }
            s.append(makeClassLink(klass, pkg, showSourceLink));
        }
        return s.toString();
    }
    
    /** Formats a class name as a link if there's javadoc for it. */
    public String makeClassLink(Class c, boolean showPkg, boolean showSourceLink) {
        String className = c.getName();
        String pkg = "";
        int dot = className.lastIndexOf(".");
        if (dot != -1) {
            pkg = className.substring(0, dot);
            className = className.substring(dot + 1);
        }
        if (c.isArray()) {
            return makeClassLink(c.getComponentType(), showPkg, showSourceLink) + "[]";
        }
        if (c.isPrimitive()) {
            return showPkg ? c.getName() : className;
        }
        return makeClassLink(className, pkg, showPkg, showSourceLink);
    }
    
    /** Formats a class name as a link if there's javadoc for it. */
    public String makeClassLink(String className, String pkg, boolean showPkg, boolean showSourceLink) {
        StringBuffer s = new StringBuffer();
        if (className.contains(".")) {
            s.append(className);
            return s.toString();
        }
        s.append(JavaDoc.getDocLink(className, pkg, showPkg));
        if (showSourceLink) {
        	String sourceFile = JavaDoc.getSourceLink(className, pkg);
        	if (sourceFile != null) {
        		s.append("&nbsp;");
        		s.append(sourceFile);
        	}
        }
        return s.toString();
    }
    
    public String makeMethodLinks(Method[] methods) {
        ArrayList<String> list = new ArrayList<String>();
        for (Method method : methods) {
            list.add(makeMethodLink(method));
        }
        return joinAfterSorting(list);
    }
    
    public static String joinAfterSorting(List<String> list) {
        Collections.sort(list);
        StringBuffer s = new StringBuffer();
        for (int i = 0; i < list.size(); i++) {
            s.append(list.get(i));
        }
        return s.toString();
    }
    
    public String makeFieldLink(Field f) {
        return "<br>" + makeClassLink(f.getType(), false, false) + " " + f.getName();
    }
    
    public String makeMethodLink(Method m) {
        String parameters = makeClassLinks(m.getParameterTypes(), false, COMMA, false);
        String returnType = makeClassLink(m.getReturnType(), false, false);
        String result = "<br>" + m.getName() + "(" + parameters + ") => " + returnType;
        Class[] exceptions = m.getExceptionTypes();
        if (exceptions.length > 0) {
            result += " throws " + makeClassLinks(exceptions, true, NEWLINE, false);
        }
        return result;
    }
    
    /**
    * Lists the fully-qualified class name and all the modifiers,
    * the superclass name and all implemented interfaces.
    */
    public String listClass(Class c) {
        StringBuffer s = new StringBuffer();
        int modifiers = c.getModifiers();
        if (c.isInterface()) {
            modifiers &= ~Modifier.ABSTRACT;
        }
        s.append(Modifier.toString(modifiers));
        if (c.isInterface() == false) {
            s.append(" class");
        }
        s.append("<br>");
        s.append(makeClassLink(c, true, true));
        if (c.isInterface() == false && c != Object.class && c.getSuperclass() != Object.class) {
            s.append("<br>extends ");
            s.append(makeClassLink(c.getSuperclass(), false, true));
        }
        Class[] interfaces = c.getInterfaces();
        if (interfaces.length > 0) {
            s.append("<br>implements ");
            s.append(NEWLINE);
            s.append(makeClassLinks(interfaces, false, NEWLINE, true));
        }
        if (c.isInterface()) {
            s.append(makeMethodLinks(c.getDeclaredMethods()));
        }
        return s.toString();
    }
    
    /** Lists the class name and all the constructors. */
    public String listConstructors(Class c) {
        StringBuffer s = new StringBuffer();
        String classLink = makeClassLink(c, false, false);
        s.append(makeClassLink(c, true, true));
        s.append("<br>");
        Constructor[] constructors = c.getConstructors();
        for (Constructor constructor : constructors) {
            s.append("<br>");
            s.append(classLink);
            s.append("(");
            s.append(makeClassLinks(constructor.getParameterTypes(), false, COMMA, false));
            s.append(")");
            Class[] exceptions = constructor.getExceptionTypes();
            if (exceptions.length > 0) {
                s.append(NEWLINE);
                s.append(" throws ");
                s.append(makeClassLinks(exceptions, false, NEWLINE, false));
            }
        }
        if (c.isInterface()) {
            s.append(makeMethodLinks(c.getDeclaredMethods()));
        }
        return s.toString();
    }
    
    /**
     * Lists all non-private fields and non-private static methods of a class.
     */
    public String listMembers(Class c) {
        StringBuffer s = new StringBuffer("<br>");
        appendAfterSorting(s, collectFields(c));
        s.append("<br>");
        appendAfterSorting(s, collectMethods(c));
        return s.toString();
    }
    
    /** Sorts the strings in the given List, then appends them to the StringBuffer. */
    private void appendAfterSorting(StringBuffer stringBuffer, List<String> items) {
        Collections.sort(items);
        for (int i = 0; i < items.size(); i++) {
            stringBuffer.append(items.get(i));
        }
    }
    
    private List<String> collectFields(Class c) {
        ArrayList<String> result = new ArrayList<String>();
        for (Field field : c.getFields()) {
            if (Modifier.isPrivate(field.getModifiers()) == false) {
                result.add(makeFieldLink(field));
            }
        }
        return result;
    }
    
    private List<String> collectMethods(Class c) {
        ArrayList<String> result = new ArrayList<String>();
        for (Method method : c.getMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isPrivate(modifiers) == false && Modifier.isStatic(modifiers)) {
                result.add(makeMethodLink(method));
            }
        }
        return result;
    }

//
// WorkspaceResearcher interface.
//
    
    /** Returns true for Java files. */
    public boolean isSuitable(ETextWindow textWindow) {
        return textWindow.isJava();
    }
    
    public String research(PTextArea textArea, String string) {
        if (string.startsWith("import ")) {
            return makePackageResult(string);
        } else {
            return makeResult(string);
        }
    }
}
