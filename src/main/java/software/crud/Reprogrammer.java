package software.crud;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import java.nio.file.Files;

import java.nio.file.Paths;

import org.apache.commons.text.StringEscapeUtils;

class Assistant {
    private API api;
    private List<API.Message> history;
    private static final int MAX_HISTORY_SIZE = 4;

    public Assistant() {
        this.api = new API();
        this.history = new ArrayList<>();
        this.history.add(new API.Message("system", "You are a code conversion assistant."));
    }

    public boolean testApiConnection() {
        try {
            String testPrompt = "Test API connection";
            int testMaxTokens = 10;
            String response = api.generateText(testPrompt, history, testMaxTokens);
            return !response.isEmpty();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public String convertUsingAPI(String promptText, String fileContent, boolean addToHistory) {
        try {
            String combinedQuestion = promptText + ": " + fileContent;
            int maxTokens = 4096;
            String response = api.generateText(combinedQuestion, history, maxTokens);
            if (addToHistory) {
                addToHistory("user", combinedQuestion);
                addToHistory("assistant", response);
            }
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return "Error occurred during code conversion.";
        }
    }

    public String generateText(String aiQuery, int maxTokens, boolean addToHistory) {
        try {
            String response = api.generateText(aiQuery, history, maxTokens);
            if (addToHistory) {
                addToHistory("user", aiQuery);
                addToHistory("assistant", response);
            }
            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return "Error occurred during code conversion.";
        }
    }

    private void addToHistory(String role, String content) {
        if (history.size() >= MAX_HISTORY_SIZE) {
            history.remove(1);
        }
        history.add(new API.Message(role, content));
    }

    public void clearHistory() {
        history.clear();
        history.add(new API.Message("system", "You are a code conversion assistant."));
    }
}

class JavaConversion {
    private static final Logger logger = LoggerFactory.getLogger(JavaConversion.class);
    private final Assistant api;
    private final LanguageSettings settings;
    private boolean redoEntireCode = false;
    private static final int MAX_RETRIES = 3; // Define a max retry limit

    public JavaConversion(Assistant api, LanguageSettings settings) {
        this.api = api;
        this.settings = settings;
    }

    public String convertCode(String inputCode, String prompt, String originalContent) throws IOException {
        int retryCount = 0;
        while (retryCount < MAX_RETRIES) {
            try {
                logger.info("Starting " + prompt);
                String requestBody = buildXMLRequestBody(
                        prompt + " Respond only in XML format, outputting any code within <code></code>, and please include any thoughts in <thoughts></thoughts>.",
                        inputCode, originalContent);
                String response = api.generateText(requestBody, settings.getMaxTokens(), false);
                logger.info("API response received: " + response);

                StringBuilder fullConversion = new StringBuilder();
                if (isResponseIncomplete(response)) {
                    while (isResponseIncomplete(response)) {
                        String code = extractCode(response);
                        if (!code.isEmpty()) {
                            fullConversion.append(code);
                            logger.info("Response incomplete. Requesting continuation...");
                            requestBody = buildContinuationRequestBody(fullConversion.toString());
                            logger.info("Requesting continuation with: " + requestBody);
                            response = api.generateText(requestBody, settings.getMaxTokens(), false);
                            code = extractCode(response);
                            logger.info("Continuation response: " + code);
                            fullConversion.append(code);
                        } else {
                            break;
                        }
                    }
                } else {
                    String code = extractCode(response);
                    if (!code.isEmpty()) {
                        fullConversion.append(code);
                    }
                }

                if (redoEntireCode) {
                    logger.info("Redoing the entire code conversion...");
                    retryCount++;
                    prompt = "Please redo the entire code conversion. " + prompt;
                    continue;
                }

                return fullConversion.toString();
            } catch (Exception e) {
                logger.error("Error during code conversion: " + e.getMessage(), e);
                throw e;
            }
        }
        throw new IOException("Failed to convert code after " + MAX_RETRIES + " attempts.");
    }

    public void setRedoEntireCode(boolean redoEntireCode) {
        this.redoEntireCode = redoEntireCode;
    }

    private boolean isResponseIncomplete(String response) {
        return !response.contains("</code>");
    }

    private String buildContinuationRequestBody(String lastResponse) {
        int lastClosingBraceIndex = lastResponse.trim().lastIndexOf("}");
        if (lastClosingBraceIndex != -1) {
            lastResponse = lastResponse.substring(0, lastClosingBraceIndex + 1);
        }
        return buildXMLRequestBody(
                "Continue this code:\n\n" +
                        lastResponse +
                        "\n\n" +
                        "Please continue from the position immediately after the last closing brace ('}') shown above. Remember to close } and output </code> when finished.",
                "", "");
    }

    private String buildXMLRequestBody(String userPrompt, String code, String originalContent) {
        StringBuilder requestBuilder = new StringBuilder();

        requestBuilder.append("system\n");
        requestBuilder.append("You are a code conversion assistant.\n");

        requestBuilder.append("user\n");
        requestBuilder.append(userPrompt).append("\n");

        if (!originalContent.isEmpty()) {
            requestBuilder.append("Original code:\n").append(originalContent).append("\n");
        }

        if (!code.isEmpty()) {
            requestBuilder.append("Code:\n").append(code).append("\n");
        }

        requestBuilder.append("format: XML\n");
        requestBuilder.append(
                "structure: <response><code>{converted_code}</code><thoughts>{thoughts}</thoughts><objectives>{objectives}</objectives></response>\n");

        requestBuilder.append("settings\n");
        requestBuilder.append("maxTokens: ").append(settings.getMaxTokens()).append("\n");

        requestBuilder.append("assistant\n");

        return requestBuilder.toString().trim();
    }

    private String extractCode(String response) {
        Pattern codePattern = Pattern.compile("<code[^>]*>(.*?)</code>|<code[^>]*>(.*)", Pattern.DOTALL);
        Matcher codeMatcher = codePattern.matcher(response);

        if (codeMatcher.find()) {
            String extractedCode;
            if (codeMatcher.group(1) != null) {
                extractedCode = codeMatcher.group(1).trim();
            } else {
                extractedCode = codeMatcher.group(2).trim();
            }
            extractedCode = removeCData(extractedCode);
            return StringEscapeUtils.unescapeHtml4(extractedCode);
        } else {
            logger.warn("No <code> tags found in the response");
        }

        return "";
    }

    private String removeCData(String content) {
        Pattern cdataPattern = Pattern.compile("<!\\[CDATA\\[(.*?)\\]\\]>", Pattern.DOTALL);
        Matcher cdataMatcher = cdataPattern.matcher(content);
        if (cdataMatcher.find()) {
            return cdataMatcher.group(1).trim();
        }

        Pattern unclosedCdataPattern = Pattern.compile("<!\\[CDATA\\[(.*)", Pattern.DOTALL);
        Matcher unclosedCdataMatcher = unclosedCdataPattern.matcher(content);
        if (unclosedCdataMatcher.find()) {
            content = unclosedCdataMatcher.group(1);
        }

        content = content.replaceAll("<!\\[CDATA\\[", "");
        content = content.replaceAll("\\]\\]>", "");

        return content.trim();
    }

    public String generateMetaContent(String fileContent) throws IOException {
        String prompt = "Generate a short summary of the method signatures and class definitions from the following code. " +
                "Do not include the method bodies. Only output the signatures and class definitions.";
        return api.generateText(prompt + "\n" + fileContent, settings.getMaxTokens(), false);
    }
}

class ProgressState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final File directory;
    private final Map<String, String> convertedFilesMap;
    private final int processedFiles;
    private final int progress;
    private final StringBuilder metaContent;
    private final StringBuilder combinedSmallFilesContent;

    public ProgressState(File directory, Map<String, String> convertedFilesMap, int processedFiles, int progress,
            StringBuilder metaContent, StringBuilder combinedSmallFilesContent) {
        this.directory = directory;
        this.convertedFilesMap = convertedFilesMap;
        this.processedFiles = processedFiles;
        this.progress = progress;
        this.metaContent = metaContent;
        this.combinedSmallFilesContent = combinedSmallFilesContent;
    }

    public File getDirectory() {
        return directory;
    }

    public Map<String, String> getConvertedFilesMap() {
        return convertedFilesMap;
    }

    public int getProcessedFiles() {
        return processedFiles;
    }

    public int getProgress() {
        return progress;
    }

    public StringBuilder getMetaContent() {
        return metaContent;
    }

    public StringBuilder getCombinedSmallFilesContent() {
        return combinedSmallFilesContent;
    }
}

class ConversionResponse {
    private final String convertedCode;
    private final boolean isIncomplete;
    private final boolean redoSuggested;

    public ConversionResponse(String convertedCode, boolean isIncomplete, boolean redoSuggested) {
        this.convertedCode = convertedCode;
        this.isIncomplete = isIncomplete;
        this.redoSuggested = redoSuggested;
    }

    public String getConvertedCode() {
        return convertedCode;
    }

    public boolean isIncomplete() {
        return isIncomplete;
    }

    public boolean isRedoSuggested() {
        return redoSuggested;
    }
}

class SyntaxChecker {
    private static final Logger logger = LoggerFactory.getLogger(SyntaxChecker.class);
    private static final int MAX_RECURSION_DEPTH = 3;
    private final Assistant api;
    private final LanguageSettings settings;

    public SyntaxChecker(Assistant api, LanguageSettings settings) {
        this.api = api;
        this.settings = settings;
    }

    public String checkAndFixSyntax(CompilationUnit cu) {
        LexicalPreservingPrinter.setup(cu);
        GeneralVisitor visitor = new GeneralVisitor();
        cu.accept(visitor, null);
        return LexicalPreservingPrinter.print(cu);
    }

    private class GeneralVisitor extends VoidVisitorAdapter<Void> {
        @Override
        public void visit(MethodDeclaration n, Void arg) {
            processNode(n);
            super.visit(n, arg);
        }

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            processNode(n);
            super.visit(n, arg);
        }

        @Override
        public void visit(ConstructorDeclaration n, Void arg) {
            processNode(n);
            super.visit(n, arg);
        }

        private void processNode(BodyDeclaration<?> n) {
            String nodeAsString = n.toString();
            List<SyntaxError> errors = checkSyntax(nodeAsString);
            if (!errors.isEmpty()) {
                try {
                    String fixedCode = fixSyntaxRecursively(nodeAsString, errors, 0);
                    LexicalPreservingPrinter.setup(n);
                    LexicalPreservingPrinter.print(n);
                    JavaParser parser = new JavaParser();
                    ParseResult<BodyDeclaration<?>> parseResult = parser.parseBodyDeclaration(fixedCode);
                    if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                        n.replace(parseResult.getResult().get());
                    } else {
                        logger.error("Failed to parse the fixed code: " + fixedCode);
                    }
                } catch (IOException e) {
                    logger.error("Failed to fix syntax: " + e.getMessage(), e);
                }
            }
        }
    }

    private List<SyntaxError> checkSyntax(String code) {
        JavaParser parser = new JavaParser();
        ParseResult<BodyDeclaration<?>> result = parser.parseBodyDeclaration(code);
        List<SyntaxError> syntaxErrors = new ArrayList<>();
        result.getProblems().forEach(problem -> {
            String message = problem.getMessage();
            int lineNumber = problem.getLocation().flatMap(location -> location.getBegin().getRange())
                    .map(range -> range.begin.line).orElse(-1);
            syntaxErrors.add(new SyntaxError(message, lineNumber));
        });
        return syntaxErrors;
    }

    private String generateAIFixPrompt(String code, SyntaxError error) {
        return String.format("Error detected at line %d with message: '%s'. Code snippet: %s", error.getLineNumber(),
                error.getMessage(), code);
    }

    private String fixSyntaxRecursively(String code, List<SyntaxError> errors, int depth) throws IOException {
        if (depth >= MAX_RECURSION_DEPTH) {
            logger.error("Maximum recursion depth reached for code snippet: {}",
                    code.substring(0, Math.min(code.length(), 200)));
            return code;
        }
        String currentCode = code;
        for (SyntaxError error : errors) {
            JavaConversion javaConversion = new JavaConversion(api, settings);
            String suggestion = javaConversion.convertCode(currentCode, generateAIFixPrompt(currentCode, error), "");
            currentCode = applyFix(currentCode, error.getLineNumber(), suggestion);
            List<SyntaxError> newErrors = checkSyntax(currentCode);
            if (newErrors.isEmpty()) {
                break;
            }
            currentCode = fixSyntaxRecursively(currentCode, newErrors, depth + 1);
        }
        return currentCode;
    }

    private String applyFix(String code, int lineNumber, String fix) {
        String[] lines = code.split("\\r?\\n");
        if (lineNumber <= lines.length) {
            lines[lineNumber - 1] = fix;
        }
        return String.join("\n", lines);
    }
}

class SyntaxError {
    private String message;
    private int lineNumber;

    public SyntaxError(String message, int lineNumber) {
        this.message = message;
        this.lineNumber = lineNumber;
    }

    public String getMessage() {
        return message;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public String toString() {
        return "SyntaxError{" +
                "message='" + message + '\'' +
                ", lineNumber=" + lineNumber +
                '}';
    }
}

class LanguageSettings {
    private String targetLanguage;
    private int maxTokens;
    private String prompt;
    private String outputExtension;
    final Map<String, String> languageExtensions;
    final Map<String, String> packagePatterns;

    public LanguageSettings(String targetLanguage, int maxTokens, String prompt, String outputExtension,
            Map<String, String> languageExtensions) {
        this.targetLanguage = targetLanguage;
        this.maxTokens = maxTokens;
        this.prompt = prompt;
        this.outputExtension = outputExtension;
        this.languageExtensions = languageExtensions;
        this.packagePatterns = new HashMap<>();
        this.packagePatterns.put("Java", "package\\s+([a-zA-Z0-9_.]+);");
        this.packagePatterns.put("C#", "namespace\\s+([a-zA-Z0-9_.]+)");
        this.packagePatterns.put("PHP", "namespace\\s+([a-zA-Z0-9_\\\\]+);");
        this.packagePatterns.put("Go", "package\\s+([a-zA-Z0-9_]+)");
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getOutputExtension() {
        return outputExtension;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getInputExtension() {
        return languageExtensions.get(targetLanguage);
    }

    public String getPackagePattern() {
        return packagePatterns.get(targetLanguage);
    }

    public void setTargetLanguage(String targetLanguage) {
        this.targetLanguage = targetLanguage;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public void setOutputExtension(String outputExtension) {
        this.outputExtension = outputExtension;
    }
}

class ClassIndex {
    private String originalClassName;
    private String newClassName;
    private String packageName;
    private String filePath;

    public ClassIndex(String originalClassName, String newClassName, String packageName, String filePath) {
        this.originalClassName = originalClassName;
        this.newClassName = newClassName;
        this.packageName = packageName;
        this.filePath = filePath;
    }

    public String getOriginalClassName() {
        return originalClassName;
    }

    public String getNewClassName() {
        return newClassName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getFilePath() {
        return filePath;
    }
}

public class Reprogrammer extends JFrame {
    private JTextArea codeTextArea;
    private JTextArea logTextArea;
    private JTextField inputDirectoryPathField;
    private JTextField outputDirectoryPathField;
    private JTextField promptTextField;
    private JComboBox<String> languageComboBox;
    private JLabel statusLabel;
    private File inputFolder;
    private File outputFolder;
    private JProgressBar progressBar;
    private JLabel fileStatusLabel;
    private JButton startButton;
    private JButton pauseButton;
    private JButton saveProgressButton;
    private JButton loadProgressButton;
    private JCheckBox includeMetaCheckBox;
    private JCheckBox useAiFileNameCheckBox;
    private JCheckBox combineSmallFilesCheckBox; // New checkbox
    private JFileChooser fileChooser = new JFileChooser();
    private LanguageSettings settings;
    private Assistant api;
    private boolean isPaused = false;
    private Map<String, String> convertedFilesMap;
    private int processedFiles;
    private StringBuilder metaContent;
    private StringBuilder combinedSmallFilesContent;

    private class FileProcessor extends SwingWorker<Void, Integer> {
        private final File directory;
        private final Map<String, String> convertedFilesMap;
        private int processedFiles;
        private StringBuilder metaContent;
        private StringBuilder combinedSmallFilesContent;

        public FileProcessor(File directory, Map<String, String> convertedFilesMap, int processedFiles,
                StringBuilder metaContent, StringBuilder combinedSmallFilesContent) {
            this.directory = directory;
            this.convertedFilesMap = convertedFilesMap != null ? convertedFilesMap : new HashMap<>();
            this.processedFiles = processedFiles;
            this.metaContent = metaContent;
            this.combinedSmallFilesContent = combinedSmallFilesContent;
        }

        @Override
        protected Void doInBackground() {
            try {
                logToTextArea("API call started.");
                if (api.testApiConnection()) {
                    logToTextArea("API connection successful.");
                    Map<String, ClassIndex> classIndex = indexClasses(directory);
                    processDirectory(directory, classIndex);
                    replaceClassNamesAcrossAllFiles(directory, classIndex);

                    File outputDirectory = new File(outputDirectoryPathField.getText());
                    if (outputDirectory.exists()) {
                        replaceClassNamesAcrossAllFiles(outputDirectory, classIndex);
                    }

                    // Save combined small files
                    if (combineSmallFilesCheckBox.isSelected()) {
                        saveCombinedSmallFiles(classIndex);
                    }
                } else {
                    logToTextArea("Failed to connect to the API.");
                    cancel(true);
                }
            } catch (IOException e) {
                logToTextArea("IO Exception: " + e.getMessage());
                e.printStackTrace();
                cancel(true);
            } catch (Exception e) {
                logToTextArea("General Exception: " + e.getMessage());
                e.printStackTrace();
                cancel(true);
            }
            return null;
        }

        @Override
        protected void done() {
            if (!isCancelled()) {
                try {
                    get();
                    logToTextArea("All files processed.");
                    fileStatusLabel.setText("Processing Complete");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    logToTextArea("Error during processing: " + cause.getMessage());
                    JOptionPane.showMessageDialog(null, "Error: " + cause.getMessage());
                    cause.printStackTrace();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logToTextArea("Task interrupted.");
                    e.printStackTrace();
                }
            }
            startButton.setEnabled(true);
            pauseButton.setEnabled(false);
        }

        @Override
        protected void process(List<Integer> chunks) {
            if (!chunks.isEmpty()) {
                int latestValue = chunks.get(chunks.size() - 1);
                progressBar.setValue(latestValue);
            }
        }

        private Map<String, ClassIndex> indexClasses(File directory) throws IOException {
            Map<String, ClassIndex> classIndex = new HashMap<>();
            for (File file : directory.listFiles()) {
                if (file.isDirectory()) {
                    logToTextArea("Indexing classes in directory: " + file.getAbsolutePath());
                    classIndex.putAll(indexClasses(file));
                } else if (file.getName().endsWith(settings.getInputExtension())) {
                    logToTextArea("Indexing classes in file: " + file.getAbsolutePath());
                    String fileContent = readFileContent(file);
                    String fileName = file.getName();
                    String originalClassName = fileName.substring(0, fileName.lastIndexOf('.'));
                    String newClassName = originalClassName;
                    if (useAiFileNameCheckBox.isSelected()) {
                        newClassName = generateNewFileName(originalClassName, fileContent);
                    }
                    String packageName = extractPackageName(fileContent, settings.getPackagePattern());
                    String filePath = file.getAbsolutePath();
                    ClassIndex classIndexEntry = new ClassIndex(originalClassName, newClassName, packageName, filePath);
                    classIndex.put(originalClassName, classIndexEntry);
                    logToTextArea("Added to classIndex: " + originalClassName + " -> " + newClassName + " in package "
                            + packageName);
                }
            }
            return classIndex;
        }

        private String extractPackageName(String content, String packagePattern) {
            Pattern pattern = Pattern.compile(packagePattern);
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "";
        }

        private boolean processFile(File file, String fileContent, Map<String, ClassIndex> classIndex, String originalFileName) {
            try {
                logToTextArea("Processing file: " + file.getAbsolutePath());
                JavaConversion javaConversion = new JavaConversion(api, settings);
                if (fileContent.isEmpty()) {
                    logToTextArea("File content is empty, skipping conversion.");
                    return false;
                }
        
                // Generate directory structure and meta content
                String directoryStructure = generateDirectoryStructure(file.getParentFile(), settings.getInputExtension());
                String fileMetaContent = generateMetaContent(file.getParentFile(), file);
        
                String fullPrompt = settings.getPrompt() + "\nProject structure:\n" + directoryStructure
                        + "The following is the meta content of other classes within the project to give more context. Please only use it as a reference for creating packages, imports and invoking other methods:\n"
                        + fileMetaContent;
                String convertedContent = javaConversion.convertCode(fileContent, fullPrompt, "");
                if (convertedContent.trim().isEmpty()) {
                    logToTextArea("Initial conversion failed or resulted in empty content.");
                    return false;
                }
        
                clearTextArea();
                updateTextArea(convertedContent);
        
                // Update package and import statements
                logToTextArea("Updating package and import statements...");
                convertedContent = updatePackageAndImports(file, convertedContent, classIndex);
        
                // Extract the original class/struct name or fallback to file name
                logToTextArea("Extracting original class name...");
                String originalClassName = extractOriginalClassName(fileContent, originalFileName);
                logToTextArea("Extracted original class name: " + originalClassName);
        
                // Always use the original class name unless AI file naming is enabled
                logToTextArea("Retrieving new class name from classIndex...");
                String newClassName = originalClassName; // Default to the original class name
                if (useAiFileNameCheckBox.isSelected()) {
                    newClassName = classIndex.containsKey(originalClassName)
                            ? classIndex.get(originalClassName).getNewClassName()
                            : generateNewFileName(originalClassName, fileContent); // Use AI to generate new name only if option is selected
                }
                logToTextArea("Final class name to use: " + newClassName);
        
                // Replace class names in content only if AI file naming is enabled
                logToTextArea("Replacing class names in content...");
                if (useAiFileNameCheckBox.isSelected()) {
                    convertedContent = replaceClassName(convertedContent, originalClassName, newClassName);
                }
                logToTextArea("Class names replaced in content.");
        
                // Check and fix syntax errors
                logToTextArea("Checking and fixing syntax errors...");
                convertedContent = checkAndFixSyntax(convertedContent);
        
                // Ensure the package declaration is present and correct
                logToTextArea("Extracting package name from file path...");
                String packageName = getPackageNameFromFilePath(file.getAbsolutePath());
                logToTextArea("Package name: " + packageName);
                Pattern packagePattern = Pattern.compile("^\\s*package\\s+.+;", Pattern.MULTILINE);
                Matcher packageMatcher = packagePattern.matcher(convertedContent);
                if (!packageMatcher.find() && !packageName.isEmpty()) {
                    convertedContent = "package " + packageName + ";\n\n" + convertedContent;
                }
        
                // Generate the final file name with the correct extension
                logToTextArea("Generating final file name...");
                String newFileName = newClassName + settings.getOutputExtension();
                logToTextArea("Generated new file name: " + newFileName);
        
                saveConvertedFile(file, convertedContent, newFileName);
                convertedFilesMap.put(file.getAbsolutePath(), newFileName);
                return true;
            } catch (Exception e) {
                logToTextArea("Error processing file: " + file.getAbsolutePath() + " - " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }                               

        private String getPackageNameFromFilePath(String filePath) {
            Path inputDirPath = Paths.get(inputFolder.getAbsolutePath());
            Path fullPath = Paths.get(filePath).normalize();
            Path relativePath = inputDirPath.relativize(fullPath.getParent()).normalize();
            logToTextArea("Normalized relative path: " + relativePath.toString());

            // Convert the relative path to package name format (excluding the file name)
            String packageName = relativePath.toString().replace(File.separatorChar, '.');

            // Remove leading, trailing, and consecutive dots
            packageName = packageName.replaceAll("^\\.|\\.$", "");
            packageName = packageName.replaceAll("\\.{2,}", ".");

            return packageName.isEmpty() ? "" : packageName;
        }

        private String checkAndFixSyntax(String fileContent) {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> parseResult = parser.parse(fileContent);
            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit cu = parseResult.getResult().get();
                SyntaxChecker syntaxChecker = new SyntaxChecker(api, settings);
                return syntaxChecker.checkAndFixSyntax(cu);
            }
            return fileContent;
        }

        private String updatePackageAndImports(File file, String fileContent, Map<String, ClassIndex> classIndex) {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> parseResult = parser.parse(fileContent);
            if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                CompilationUnit cu = parseResult.getResult().get();

                // Ensure the package declaration is present
                ClassIndex currentClassIndex = getCurrentClassIndex(file.getName(), classIndex);
                if (currentClassIndex != null && !cu.getPackageDeclaration().isPresent()) {
                    cu.setPackageDeclaration(new PackageDeclaration(new Name(currentClassIndex.getPackageName())));
                }

                cu.accept(new PackageAndImportVisitor(file, classIndex), null);
                return cu.toString();
            }
            return fileContent;
        }

        private ClassIndex getCurrentClassIndex(String currentFileName, Map<String, ClassIndex> classIndex) {
            String currentClassName = currentFileName.substring(0, currentFileName.lastIndexOf("."));
            return classIndex.get(currentClassName);
        }

        private String generateNewFileName(String currentClassName, String fileContent) {
            String prompt = "Create a new name for the Java class '" + currentClassName
                    + "'. It must be in English. Respond with XML, containing the new <filename>{filename}</filename> only. File Content: "
                    + fileContent;
            String response = api.generateText(prompt, settings.getMaxTokens(), false);

            // Extracting the new file name from the AI response
            String newFileName = extractFromXml(response, "filename");
            if (newFileName == null || newFileName.isEmpty()) {
                newFileName = currentClassName; // Fallback to the current class name if AI fails to generate a new name
            }
            newFileName = sanitizeFileName(newFileName.trim());

            // Remove any trailing extension if present
            int extensionIndex = newFileName.lastIndexOf('.');
            if (extensionIndex != -1) {
                newFileName = newFileName.substring(0, extensionIndex);
            }

            return newFileName;
        }

        private String extractFromXml(String xml, String tagName) {
            String regex = "<" + tagName + ">(.+?)</" + tagName + ">";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(xml);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "";
        }

        private String sanitizeFileName(String fileName) {
            // Remove invalid characters for a Windows file path
            return fileName.replaceAll("[<>:\"/\\|?*]", "").trim();
        }

        private String replaceClassName(String content, String oldName, String newName) {
            logToTextArea("Replacing class name: " + oldName + " with " + newName);
            String regex = "\\b" + Pattern.quote(oldName) + "\\b";
            String updatedContent = content.replaceAll(regex, newName);
            logToTextArea("Class name replacement complete.");
            return updatedContent;
        }

        private void saveConvertedFile(File originalFile, String convertedContent, String newFileName)
                throws IOException {
            logToTextArea("Saving converted file: " + newFileName);

            // Extract the package name
            String packageName = getPackageNameFromFilePath(originalFile.getPath());
            logToTextArea("Extracted package name: " + packageName);

            // Create the appropriate folder structure
            Path outputDirPath = outputFolder.toPath();
            if (!packageName.isEmpty()) {
                Path packagePath = Paths.get(packageName.replace('.', File.separatorChar));
                outputDirPath = outputDirPath.resolve(packagePath);
            }
            File outputDir = outputDirPath.toFile();
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                logToTextArea("Failed to create output directory: " + outputDir.getAbsolutePath());
                return;
            }

            // Save the converted file
            Path outputFilePath = outputDirPath.resolve(newFileName);
            if (!newFileName.endsWith(".java")) {
                outputFilePath = Path.of(outputFilePath.toString() + ".java");
            }
            File outputFile = outputFilePath.toFile();

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                writer.write(convertedContent);
                api.clearHistory();
                logToTextArea("Converted and saved: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                logToTextArea("Error writing to file: " + outputFile.getAbsolutePath());
                e.printStackTrace();
            }
        }

        private void processDirectory(File directory, Map<String, ClassIndex> classIndex) throws IOException {
            if (!directory.toPath().startsWith(inputFolder.toPath())) {
                return;
            }

            File[] files = directory.listFiles();
            if (files == null)
                return;

            int totalFiles = countFiles(directory);

            for (File file : files) {
                if (isCancelled())
                    break;
                if (isPaused) {
                    synchronized (this) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (file.isDirectory()) {
                    processDirectory(file, classIndex);
                } else if (file.getName().endsWith(settings.getInputExtension())
                        && !file.getName().contains("Operations")) {
                    clearTextArea();
                    String fileContent = readFileContent(file);
                    if (file.length() <= 10 * 1024 && combineSmallFilesCheckBox.isSelected()) {
                        combineSmallFile(fileContent, file.getName(), classIndex);
                    } else {
                        boolean isProcessed = processFile(file, fileContent, classIndex, file.getName());
                        processedFiles++;
                        int progress = (int) ((processedFiles / (double) totalFiles) * 100);
                        publish(progress);

                        if (isProcessed && includeMetaCheckBox.isSelected()) {
                            metaContent.append(generateMetaContent(file.getParentFile(), file));
                        }
                    }
                }
            }

            logToTextArea("Class index before replaceClassNamesAcrossAllFiles:");
            for (Map.Entry<String, ClassIndex> entry : classIndex.entrySet()) {
                String className = entry.getKey();
                ClassIndex classIndexValue = entry.getValue();
                logToTextArea("- Class: " + className);
                logToTextArea("  Original Name: " + classIndexValue.getOriginalClassName());
                logToTextArea("  New Name: " + classIndexValue.getNewClassName());
                logToTextArea("  Package: " + classIndexValue.getPackageName());
                logToTextArea("  File Path: " + classIndexValue.getFilePath());
            }
            replaceClassNamesAcrossAllFiles(directory, classIndex);
        }

        private void replaceClassNamesAcrossAllFiles(File directory, Map<String, ClassIndex> classIndex) throws IOException {
            boolean changesMade;
            int iterationCount = 0;
            File outputDirectory = new File(outputDirectoryPathField.getText());
            if (outputDirectory.exists()) {
                do {
                    changesMade = false;
                    iterationCount++;
                    logToTextArea("Starting iteration " + iterationCount + " for class name replacements in output directory.");
        
                    changesMade = processDirectoryForReplacements(outputDirectory, classIndex) || changesMade;
        
                    logToTextArea("Completed iteration " + iterationCount + " for class name replacements in output directory.");
                } while (changesMade);
        
                logToTextArea("Completed class name replacements after " + iterationCount + " iterations in output directory.");
            }
        }
        
        private boolean processDirectoryForReplacements(File directory, Map<String, ClassIndex> classIndex) throws IOException {
            boolean changesMade = false;
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        changesMade = processDirectoryForReplacements(file, classIndex) || changesMade;
                    } else if (file.getName().endsWith(settings.getOutputExtension())) {
                        changesMade = replaceClassNamesInFile(file, classIndex) || changesMade;
                    }
                }
            }
            return changesMade;
        }
        
        private boolean replaceClassNamesInFile(File file, Map<String, ClassIndex> classIndex) throws IOException {
            String fileContent = readFileContent(file);
            String updatedContent = fileContent;
            boolean modified = false;
        
            for (ClassIndex ci : classIndex.values()) {
                String oldClassName = ci.getOriginalClassName();
                String newClassName = ci.getNewClassName();
                String regex = "\\b" + Pattern.quote(oldClassName) + "\\b";
                String tempContent = updatedContent.replaceAll(regex, newClassName);
                if (!tempContent.equals(updatedContent)) {
                    logToTextArea("Replacing " + oldClassName + " with " + newClassName + " in " + file.getName());
                    updatedContent = tempContent;
                    modified = true;
                }
            }
        
            if (modified) {
                saveFile(file, updatedContent);
                logToTextArea("Updated file saved: " + file.getAbsolutePath());
            }
        
            return modified;
        }

        private void combineSmallFile(String content, String fileName, Map<String, ClassIndex> classIndex) {
            try {
                logToTextArea("Combining small file: " + fileName);
                JavaConversion javaConversion = new JavaConversion(api, settings);
                String convertedContent = javaConversion.convertCode(content, settings.getPrompt(), "");
                
                // Replace class names in the combined content
                for (ClassIndex ci : classIndex.values()) {
                    String oldClassName = ci.getOriginalClassName();
                    String newClassName = ci.getNewClassName();
                    String regex = "\\b" + Pattern.quote(oldClassName) + "\\b";
                    convertedContent = convertedContent.replaceAll(regex, newClassName);
                }
                
                combinedSmallFilesContent.append("// File: ").append(fileName).append("\n");
                combinedSmallFilesContent.append(convertedContent).append("\n\n");
            } catch (IOException e) {
                logToTextArea("Error combining small file: " + fileName + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        private String extractPrimaryClassName() {
            // Assuming the primary class name is the first class name in the combined
            // content
            Pattern pattern = Pattern.compile("\\b(class|enum|interface)\\s+(\\w+)");
            Matcher matcher = pattern.matcher(combinedSmallFilesContent);
            if (matcher.find()) {
                return matcher.group(2); // Group 2 contains the class name
            }
            return "CombinedSmallFiles"; // Default name if no class name is found
        }

        private void saveCombinedSmallFiles(Map<String, ClassIndex> classIndex) throws IOException {
            if (combinedSmallFilesContent.length() > 0) {
                // Use classIndex to extract the package name
                String packageName = "";
                if (!classIndex.isEmpty()) {
                    ClassIndex firstClassIndex = classIndex.values().iterator().next();
                    packageName = firstClassIndex.getPackageName();
                }
                String primaryClassName = extractPrimaryClassName();
                String combinedFileName = primaryClassName + ".java";
        
                // Create the appropriate folder structure
                Path outputDirPath = outputFolder.toPath();
                if (!packageName.isEmpty()) {
                    Path packagePath = Paths.get(packageName.replace('.', File.separatorChar));
                    outputDirPath = outputDirPath.resolve(packagePath);
                }
                File outputDir = outputDirPath.toFile();
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    logToTextArea("Failed to create output directory: " + outputDir.getAbsolutePath());
                    return;
                }
        
                // Ensure the package declaration is included
                String combinedContent = combinedSmallFilesContent.toString();
                if (!packageName.isEmpty() && !combinedContent.contains("package " + packageName)) {
                    combinedContent = "package " + packageName + ";\n\n" + combinedContent;
                }
        
                // Convert the combined content
                logToTextArea("Converting combined small files content...");
                JavaConversion javaConversion = new JavaConversion(api, settings);
                String convertedCombinedContent = javaConversion.convertCode(combinedContent,
                        "Merge this Java code together, outputting a public class with everything embedded.", "");
        
                // Replace class names in the combined content
                for (ClassIndex ci : classIndex.values()) {
                    String oldClassName = ci.getOriginalClassName();
                    String newClassName = ci.getNewClassName();
                    String regex = "\\b" + Pattern.quote(oldClassName) + "\\b";
                    convertedCombinedContent = convertedCombinedContent.replaceAll(regex, newClassName);
                }
        
                // Save the combined content to the output directory with the new name
                File combinedFile = new File(outputDir, combinedFileName);
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(combinedFile))) {
                    writer.write(convertedCombinedContent);
                }
                logToTextArea("Combined small files saved to: " + combinedFile.getAbsolutePath());
            }
        }

        private String generateMetaContent(File parentDirectory, File currentFile) throws IOException {
            StringBuilder metaContent = new StringBuilder();
            File[] files = parentDirectory.listFiles((dir, name) -> name.endsWith(settings.getInputExtension()));
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && !file.equals(currentFile)) {
                        String otherFileContent = readFileContent(file);
                        JavaConversion javaConversion = new JavaConversion(api, settings);
                        String fileMetaContent = javaConversion.generateMetaContent(otherFileContent);
                        metaContent.append("File: ").append(file.getName()).append("\n");
                        if (!useAiFileNameCheckBox.isSelected()) {
                            fileMetaContent = replaceClassName(fileMetaContent, extractOriginalClassName(otherFileContent, file.getName()), file.getName());
                        }
                        metaContent.append(fileMetaContent).append("\n\n");
                    }
                }
            }
            return metaContent.toString();
        }

        private int countFiles(File directory) {
            int count = 0;
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        count += countFiles(file);
                    } else if (file.getName().endsWith(settings.getInputExtension())) {
                        count++;
                    }
                }
            }
            return count;
        }

        private String generateDirectoryStructure(File directory, String extension) {
            StringBuilder structureBuilder = new StringBuilder();
            appendDirectoryStructure(directory, extension, structureBuilder, "");
            return structureBuilder.toString();
        }

        private void appendDirectoryStructure(File directory, String extension, StringBuilder structureBuilder,
                String indent) {
            if (!directory.toPath().startsWith(inputFolder.toPath())) {
                return;
            }

            structureBuilder.append(indent).append(directory.getName()).append("/\n");
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        appendDirectoryStructure(file, extension, structureBuilder, indent + "  ");
                    } else if (file.getName().endsWith(extension) && !file.isHidden()
                            && !file.getName().contains("Operations")) {
                        structureBuilder.append(indent).append("  ").append(file.getName()).append("\n");
                    }
                }
            }
        }

        private String extractOriginalClassName(String fileContent, String fileName) {
            Pattern pattern = Pattern.compile("\\b(class|struct)\\s+(\\w+)");
            Matcher matcher = pattern.matcher(fileContent);
            if (matcher.find()) {
                return matcher.group(2); // Group 2 contains the class/struct name
            } else {
                // Fallback to using the file name without extension, converted to CamelCase
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                return toCamelCase(baseName);
            }
        }

        private String toCamelCase(String input) {
            if (input == null || input.isEmpty()) {
                return input;
            }
            StringBuilder camelCase = new StringBuilder();
            boolean nextUpperCase = false;
            for (char c : input.toCharArray()) {
                if (c == '_' || c == '-' || c == ' ') {
                    nextUpperCase = true;
                } else {
                    if (nextUpperCase) {
                        camelCase.append(Character.toUpperCase(c));
                        nextUpperCase = false;
                    } else {
                        camelCase.append(c);
                    }
                }
            }
            return camelCase.toString();
        }

        private String readFileContent(File file) throws IOException {
            return new String(Files.readAllBytes(file.toPath()));
        }

        private void saveFile(File file, String content) throws IOException {
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(content);
            }
        }
    }

    private static class PackageAndImportVisitor extends VoidVisitorAdapter<Void> {
        private final File currentFile;
        private final Map<String, ClassIndex> classIndex;

        public PackageAndImportVisitor(File currentFile, Map<String, ClassIndex> classIndex) {
            this.currentFile = currentFile;
            this.classIndex = classIndex;
        }

        @Override
        public void visit(PackageDeclaration n, Void arg) {
            ClassIndex currentClassIndex = getCurrentClassIndex();
            if (currentClassIndex != null) {
                n.setName(new Name(currentClassIndex.getPackageName()));
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(ImportDeclaration n, Void arg) {
            String importedType = n.getNameAsString();
            ClassIndex importedClassIndex = classIndex.get(getSimpleName(importedType));
            if (importedClassIndex != null) {
                n.setName(new Name(importedClassIndex.getPackageName() + "." + importedClassIndex.getNewClassName()));
            }
            super.visit(n, arg);
        }

        private ClassIndex getCurrentClassIndex() {
            String currentFileName = currentFile.getName();
            String currentClassName = currentFileName.substring(0, currentFileName.lastIndexOf("."));
            return classIndex.get(currentClassName);
        }

        private String getSimpleName(String fullName) {
            int lastDotIndex = fullName.lastIndexOf(".");
            if (lastDotIndex != -1) {
                return fullName.substring(lastDotIndex + 1);
            }
            return fullName;
        }
    }

    public Reprogrammer(LanguageSettings settings) {
        this.settings = settings;
        this.api = new Assistant();
        this.convertedFilesMap = new HashMap<>();
        this.processedFiles = 0;
        this.metaContent = new StringBuilder();
        this.combinedSmallFilesContent = new StringBuilder();
        initComponents();
        addListeners();
    }

    private void initComponents() {
        setTitle("Reprogrammer - Created by www.lance.name");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        setIconImage(Toolkit.getDefaultToolkit().getImage(getClass().getResource("/icons/app.png")));

        setupTopPanel();
        setupCenterPanel();
        setupBottomPanel();

        pack();
        setSize(1200, 800);
        setLocationRelativeTo(null);
    }

    private void addListeners() {
        startButton.addActionListener(this::startConversion);
        pauseButton.addActionListener(this::togglePause);
        saveProgressButton.addActionListener(this::saveProgress);
        loadProgressButton.addActionListener(this::loadProgress);
    }

    private void setupTopPanel() {
        JPanel topPanel = new JPanel(new GridLayout(2, 1, 10, 10));

        JPanel fileSelectionPanel = new JPanel(new GridLayout(1, 4, 10, 10));
        fileSelectionPanel.setBorder(BorderFactory.createTitledBorder("Directories"));

        inputDirectoryPathField = new JTextField();
        JButton selectInputFolderButton = new JButton("Select Input Folder");
        selectInputFolderButton.addActionListener(this::selectInputFolder);
        fileSelectionPanel.add(new JLabel("Input Directory:"));
        fileSelectionPanel.add(inputDirectoryPathField);
        fileSelectionPanel.add(selectInputFolderButton);

        outputDirectoryPathField = new JTextField();
        JButton selectOutputFolderButton = new JButton("Select Output Folder");
        selectOutputFolderButton.addActionListener(this::selectOutputFolder);
        fileSelectionPanel.add(new JLabel("Output Directory:"));
        fileSelectionPanel.add(outputDirectoryPathField);
        fileSelectionPanel.add(selectOutputFolderButton);

        topPanel.add(fileSelectionPanel);

        JPanel languageSettingsPanel = new JPanel(new GridLayout(1, 4, 10, 10));
        languageSettingsPanel.setBorder(BorderFactory.createTitledBorder("Language Settings"));

        languageComboBox = new JComboBox<>(settings.languageExtensions.keySet().toArray(new String[0]));
        languageComboBox.setSelectedItem(settings.getTargetLanguage());
        languageSettingsPanel.add(new JLabel("Language:"));
        languageSettingsPanel.add(languageComboBox);

        promptTextField = new JTextField(settings.getPrompt());
        languageSettingsPanel.add(new JLabel("Prompt:"));
        languageSettingsPanel.add(promptTextField);

        topPanel.add(languageSettingsPanel);

        add(topPanel, BorderLayout.NORTH);
    }

    private void setupCenterPanel() {
        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 10, 10));

        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBorder(BorderFactory.createTitledBorder("Code"));
        codeTextArea = new JTextArea();
        codeTextArea.setEditable(false);
        codeTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane codeScrollPane = new JScrollPane(codeTextArea);
        codePanel.add(codeScrollPane, BorderLayout.CENTER);

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Log"));
        logTextArea = new JTextArea();
        logTextArea.setEditable(false);
        logTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane logScrollPane = new JScrollPane(logTextArea);
        logPanel.add(logScrollPane, BorderLayout.CENTER);

        centerPanel.add(codePanel);
        centerPanel.add(logPanel);

        add(centerPanel, BorderLayout.CENTER);
    }

    private void setupBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));

        JPanel progressPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Status: Idle");
        progressBar = new JProgressBar();
        fileStatusLabel = new JLabel("File Status: Idle");
        progressPanel.add(statusLabel, BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(fileStatusLabel, BorderLayout.SOUTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        startButton = new JButton("Start");
        pauseButton = new JButton("Pause");
        pauseButton.setEnabled(false);
        saveProgressButton = new JButton("Save Progress");
        loadProgressButton = new JButton("Load Progress");
        includeMetaCheckBox = new JCheckBox("Include Meta Content");
        useAiFileNameCheckBox = new JCheckBox("Use AI for File Names");
        combineSmallFilesCheckBox = new JCheckBox("Combine Small Files");

        combineSmallFilesCheckBox.addActionListener(e -> {
            if (combineSmallFilesCheckBox.isSelected() && !useAiFileNameCheckBox.isSelected()) {
                useAiFileNameCheckBox.setSelected(true);
                useAiFileNameCheckBox.setEnabled(false);
            } else {
                useAiFileNameCheckBox.setEnabled(true);
            }
        });

        buttonPanel.add(startButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(saveProgressButton);
        buttonPanel.add(loadProgressButton);
        buttonPanel.add(includeMetaCheckBox);
        buttonPanel.add(useAiFileNameCheckBox);
        buttonPanel.add(combineSmallFilesCheckBox);

        bottomPanel.add(buttonPanel, BorderLayout.WEST);
        bottomPanel.add(progressPanel, BorderLayout.CENTER);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void selectInputFolder(ActionEvent e) {
        File selectedFolder = selectFolder();
        if (selectedFolder != null) {
            inputFolder = selectedFolder;
            inputDirectoryPathField.setText(inputFolder.getAbsolutePath());
        }
    }

    private void selectOutputFolder(ActionEvent e) {
        File selectedFolder = selectFolder();
        if (selectedFolder != null) {
            outputFolder = selectedFolder;
            outputDirectoryPathField.setText(outputFolder.getAbsolutePath());
        }
    }

    private File selectFolder() {
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile();
        }
        return null;
    }

    private void startConversion(ActionEvent e) {
        if (fileStatusLabel != null) {
            fileStatusLabel.setText("Processing...");
        }
        String inputFolderPath = inputDirectoryPathField.getText();
        String outputFolderPath = outputDirectoryPathField.getText();
        String selectedLanguage = (String) languageComboBox.getSelectedItem();
        String customPrompt = promptTextField.getText();

        convertedFilesMap.clear();
        processedFiles = 0;
        metaContent = new StringBuilder();
        combinedSmallFilesContent = new StringBuilder();

        if (inputFolderPath.isEmpty() || outputFolderPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter both input and output folder paths.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        inputFolder = new File(inputFolderPath);
        outputFolder = new File(outputFolderPath);

        if (!inputFolder.isDirectory() || !outputFolder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Invalid input or output folder path.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        settings.setTargetLanguage(selectedLanguage);
        settings.setPrompt(customPrompt);

        startButton.setEnabled(false);
        pauseButton.setEnabled(true);
        fileStatusLabel.setText("Processing...");
        progressBar.setValue(0);

        FileProcessor processor = new FileProcessor(inputFolder, convertedFilesMap, processedFiles, metaContent,
                combinedSmallFilesContent);
        processor.execute();
    }

    private void togglePause(ActionEvent e) {
        isPaused = !isPaused;
        if (!isPaused) {
            synchronized (this) {
                notifyAll();
            }
        }
        pauseButton.setText(isPaused ? "Resume" : "Pause");
    }

    private void saveProgress(ActionEvent e) {
        if (outputFolder == null) {
            JOptionPane.showMessageDialog(this, "Output directory not selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File progressFile = new File(outputFolder, "progress.ser");
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(progressFile))) {
            ProgressState state = new ProgressState(inputFolder, convertedFilesMap, processedFiles,
                    progressBar.getValue(), metaContent, combinedSmallFilesContent);
            out.writeObject(state);
            logToTextArea("Progress saved.");
        } catch (IOException ex) {
            logToTextArea("Error saving progress: " + ex.getMessage());
        }
    }

    private void loadProgress(ActionEvent e) {
        if (outputFolder == null) {
            JOptionPane.showMessageDialog(this, "Output directory not selected.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File progressFile = new File(outputFolder, "progress.ser");
        if (!progressFile.exists()) {
            logToTextArea("No progress file found in the output directory.");
            return;
        }
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(progressFile))) {
            ProgressState state = (ProgressState) in.readObject();
            convertedFilesMap = state.getConvertedFilesMap();
            processedFiles = state.getProcessedFiles();
            int progress = state.getProgress();
            metaContent = state.getMetaContent();
            combinedSmallFilesContent = state.getCombinedSmallFilesContent();

            progressBar.setValue(progress);
            if (progress < 100) {
                FileProcessor processor = new FileProcessor(inputFolder, convertedFilesMap, processedFiles, metaContent,
                        combinedSmallFilesContent);
                processor.execute();
                logToTextArea("Progress loaded and processing resumed.");
            } else {
                logToTextArea("Progress loaded. Processing already completed.");
            }
        } catch (IOException | ClassNotFoundException ex) {
            logToTextArea("Error loading progress: " + ex.getMessage());
        }
    }

    private void clearTextArea() {
        codeTextArea.setText("");
    }

    private void updateTextArea(String content) {
        codeTextArea.append(content);
        codeTextArea.append(System.lineSeparator());
        codeTextArea.setCaretPosition(codeTextArea.getDocument().getLength());
    }

    private void logToTextArea(String message) {
        logTextArea.append(message);
        logTextArea.append(System.lineSeparator());
        logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        String settingsFilePath = "settings.yaml";
        Map<String, Object> settings;

        if (args.length > 0) {
            settingsFilePath = args[0];
        }

        try {
            settings = readSettings(settingsFilePath);
            System.out.println("Loaded settings:");
            for (Map.Entry<String, Object> entry : settings.entrySet()) {
                System.out.println(entry.getKey() + ": " + entry.getValue());
            }

            // Validate the API key for the selected AI service
            validateApiKey(settings);

            @SuppressWarnings("unchecked")
            Map<String, String> languageExtensions = (Map<String, String>) settings.get("language_extensions");
            LanguageSettings languageSettings = new LanguageSettings(
                    (String) settings.get("target_language"),
                    (Integer) settings.get("max_tokens"),
                    (String) settings.get("prompt"),
                    (String) settings.get("output_extension"),
                    languageExtensions);

            SwingUtilities.invokeLater(() -> {
                Reprogrammer gui = new Reprogrammer(languageSettings);
                gui.setVisible(true);
            });
        } catch (IOException e) {
            e.printStackTrace();
            showErrorDialog(e.getMessage());
        }
    }

    public static Map<String, Object> readSettings(String filename) throws IOException {
        try (InputStream inputStream = new FileInputStream(filename)) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            Map<String, Object> settings = mapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {
            });

            validateSetting(settings, "target_language");
            validateSetting(settings, "max_tokens");
            validateSetting(settings, "prompt");
            validateSetting(settings, "output_extension");
            validateSetting(settings, "language_extensions");
            validateSetting(settings, "ai_service");

            return settings;
        }
    }

    private static void validateSetting(Map<String, Object> settings, String key) throws IOException {
        if (!settings.containsKey(key)) {
            throw new IOException("Missing required setting: " + key);
        }
    }

    private static void validateApiKey(Map<String, Object> settings) throws IOException {
        String aiService = (String) settings.get("ai_service");

        switch (aiService) {
            case "openai":
                String openaiApiKey = (String) settings.get("openai_api_key");
                if (openaiApiKey == null || !openaiApiKey.startsWith("sk-")) {
                    throw new IOException("Invalid OpenAI API key. It must be set and start with 'sk-'.");
                }
                break;
            case "custom":
                // Add custom API key validation if needed
                break;
            case "claude":
                String claudeApiKey = (String) settings.get("claude_api_key");
                if (claudeApiKey == null || !claudeApiKey.startsWith("sk-")) {
                    throw new IOException("Invalid Claude API key. It must be set and start with 'sk-'.");
                }
                break;
            default:
                throw new IOException("Unsupported AI service: " + aiService);
        }
    }

    private static void showErrorDialog(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
        });
    }
}