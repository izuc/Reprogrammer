package software.crud;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
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

import org.apache.commons.lang3.StringUtils;
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
        String prompt = "Generate a summary of the method signatures and class definitions from the following code. " +
                "Do not include the method bodies. Only output the signatures and class definitions.";
        return api.generateText(prompt + "\n" + fileContent, settings.getMaxTokens(), false);
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
    private JCheckBox includeMetaCheckBox;
    private JCheckBox useAiFileNameCheckBox;
    private JFileChooser fileChooser = new JFileChooser();
    private LanguageSettings settings;
    private Assistant api;
    private boolean isPaused = false;
    private static final Logger logger = LoggerFactory.getLogger(Reprogrammer.class);

    private class FileProcessor extends SwingWorker<Void, Integer> {
        private final File directory;

        public FileProcessor(File directory) {
            this.directory = directory;
        }

        @Override
        protected Void doInBackground() {
            Map<String, String> convertedFilesMap = new HashMap<>();
            try {
                logToTextArea("API call started.");
                if (api.testApiConnection()) {
                    logToTextArea("API connection successful.");
                    processDirectory(directory, convertedFilesMap);
                    finalizeFileRenaming(convertedFilesMap);
                } else {
                    logToTextArea("Failed to connect to the API.");
                    cancel(true);
                }
            } catch (IOException e) {
                logToTextArea("IO Exception: " + e.getMessage());
                cancel(true);
            } catch (Exception e) {
                logToTextArea("General Exception: " + e.getMessage());
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
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logToTextArea("Task interrupted.");
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

        private String readFileContent(File file) {
            StringBuilder contentBuilder = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String currentLine;
                while ((currentLine = br.readLine()) != null) {
                    contentBuilder.append(currentLine).append("\n");
                }
            } catch (IOException e) {
                logger.error("Error reading file content: {}", file.getAbsolutePath(), e);
            }
            return contentBuilder.toString();
        }

        private void processDirectory(File directory, Map<String, String> convertedFilesMap) throws IOException {
            File[] files = directory.listFiles();
            if (files == null)
                return;

            int totalFiles = countFiles(directory);
            int processedFiles = 0;
            String directoryStructure = generateDirectoryStructure(directory, settings.getInputExtension());
            Map<String, ClassIndex> classIndex = indexClasses(directory);

            for (File file : files) {
                if (isCancelled()) {
                    break;
                }
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
                    processDirectory(file, convertedFilesMap);
                } else if (file.getName().endsWith(settings.getInputExtension())) {
                    clearTextArea();
                    String fileContent = readFileContent(file);
                    boolean isProcessed = processFile(file, directoryStructure, fileContent, classIndex,
                            convertedFilesMap);
                    processedFiles++;
                    int progress = (int) ((processedFiles / (double) totalFiles) * 100);
                    publish(progress);

                    if (isProcessed && includeMetaCheckBox.isSelected()) {
                        String metaContent = generateMetaContent(file.getParentFile(), file);
                        if (!metaContent.isEmpty()) {
                            String metaPrompt = settings.getPrompt() + "\n\n" +
                                    "The following is the meta content of other classes within the project to give more context. Please only use it as a reference for creating packages, imports and invoking other methods:\n"
                                    + metaContent;
                            JavaConversion javaConversion = new JavaConversion(api, settings);
                            javaConversion.convertCode(fileContent, metaPrompt, "");
                        }
                    }
                }
            }
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
            structureBuilder.append(indent).append(directory.getName()).append("/\n");
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        appendDirectoryStructure(file, extension, structureBuilder, indent + "  ");
                    } else if (file.getName().endsWith(extension)) {
                        structureBuilder.append(indent).append("  ").append(file.getName()).append("\n");
                    }
                }
            }
        }

        private boolean processFile(File file, String directoryStructure, String fileContent,
                Map<String, ClassIndex> classIndex, Map<String, String> convertedFilesMap) {
            try {
                JavaConversion javaConversion = new JavaConversion(api, settings);
                if (fileContent.isEmpty()) {
                    logToTextArea("File content is empty, skipping conversion.");
                    return false;
                }

                String fullPrompt = settings.getPrompt() + "\nProject structure:\n" + directoryStructure;
                String convertedContent = javaConversion.convertCode(fileContent, fullPrompt, "");
                if (convertedContent.trim().isEmpty()) {
                    logToTextArea("Initial conversion failed or resulted in empty content.");
                    return false;
                }

                clearTextArea();
                updateTextArea(convertedContent);

                // Update package and import statements
                convertedContent = updatePackageAndImports(file, convertedContent, classIndex);

                // Check and fix syntax errors
                convertedContent = checkAndFixSyntax(convertedContent);

                // Generate new file name using AI
                String newFileName = file.getName();
                if (useAiFileNameCheckBox.isSelected()) {
                    newFileName = generateNewFileName(file.getName().replace(settings.getInputExtension(), ""),
                            convertedContent); // Call with fileContent
                } else {
                    newFileName = toCamelCase(newFileName.replace(settings.getInputExtension(), ""));
                }

                // Rename the class inside the file to match the new filename
                String classNameWithoutExtension = file.getName().replace(settings.getInputExtension(), "");
                convertedContent = replaceClassName(convertedContent, classNameWithoutExtension,
                        newFileName.replace(settings.getOutputExtension(), ""));

                saveConvertedFile(file, convertedContent, newFileName);
                convertedFilesMap.put(file.getAbsolutePath(), newFileName);
                return true;
            } catch (Exception e) {
                logToTextArea("Error processing file: " + file.getAbsolutePath());
                e.printStackTrace();
                return false;
            }
        }

        static String toCamelCase(String input) {
            if (StringUtils.isBlank(input)) {
                return input;
            }

            // Check if the input is already in camel case
            if (input.matches("([a-z]+[A-Z]+\\w+)+")) {
                return input;
            }

            String[] parts = input.split("_");
            StringBuilder camelCaseString = new StringBuilder();

            for (String part : parts) {
                if (!part.isEmpty()) {
                    if (camelCaseString.length() == 0) {
                        camelCaseString.append(part);
                    } else {
                        camelCaseString.append(StringUtils.capitalize(part.toLowerCase()));
                    }
                }
            }

            return camelCaseString.toString();
        }

        private Map<String, ClassIndex> indexClasses(File directory) {
            Map<String, ClassIndex> classIndex = new HashMap<>();
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        classIndex.putAll(indexClasses(file));
                    } else if (file.getName().endsWith(settings.getInputExtension())) {
                        String fileContent = readFileContent(file);
                        JavaParser parser = new JavaParser();
                        ParseResult<CompilationUnit> parseResult = parser.parse(fileContent);
                        if (parseResult.isSuccessful() && parseResult.getResult().isPresent()) {
                            CompilationUnit cu = parseResult.getResult().get();
                            Optional<PackageDeclaration> packageDeclaration = cu.getPackageDeclaration();
                            String packageName = packageDeclaration.map(PackageDeclaration::getNameAsString).orElse("");
                            List<TypeDeclaration<?>> types = cu.getTypes();
                            for (TypeDeclaration<?> type : types) {
                                if (type instanceof ClassOrInterfaceDeclaration) {
                                    String originalClassName = type.getNameAsString();
                                    String newClassName = originalClassName;
                                    if (useAiFileNameCheckBox.isSelected()) {
                                        newClassName = generateNewFileName(originalClassName, fileContent);
                                    }
                                    String filePath = file.getAbsolutePath();
                                    classIndex.put(originalClassName,
                                            new ClassIndex(originalClassName, newClassName, packageName, filePath));
                                }
                            }
                        }
                    }
                }
            }
            return classIndex;
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

        private String generateNewFileName(String currentClassName, String fileContent) {
            String prompt = "Create a new name for the Java class '" + currentClassName
                    + "'. It must be in English. Respond with XML, containing the new filename only. File Content: "
                    + fileContent;
            String response = api.generateText(prompt, settings.getMaxTokens(), false);

            // Extracting the new file name from the AI response
            String newFileName = extractFromXml(response, "filename");
            if (newFileName == null || newFileName.isEmpty()) {
                newFileName = currentClassName; // Fallback to the current class name if AI fails to generate a new name
            }
            newFileName = sanitizeFileName(newFileName.trim());

            // Ensure the filename has the correct extension
            if (!newFileName.endsWith(settings.getOutputExtension())) {
                newFileName += settings.getOutputExtension();
            }

            // Remove the old extension if present
            if (newFileName.endsWith(settings.getInputExtension() + settings.getOutputExtension())) {
                newFileName = newFileName.replace(settings.getInputExtension(), "");
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
            String regex = "\\b" + Pattern.quote(oldName) + "\\b";
            return content.replaceAll(regex, newName);
        }

        private void saveConvertedFile(File originalFile, String convertedContent, String newFileName)
                throws IOException {
            // Extract the package name
            Pattern pattern = Pattern.compile(settings.getPackagePattern());
            Matcher matcher = pattern.matcher(convertedContent);
            String packageName = "";
            if (matcher.find()) {
                packageName = matcher.group(1).replace('.', File.separatorChar);
            }

            // Determine the relative path and create necessary directories
            Path relativePath = inputFolder.toPath().relativize(originalFile.toPath()).getParent();
            if (relativePath == null) {
                relativePath = Path.of("");
            }

            Path outputSubfolder = outputFolder.toPath().resolve(relativePath).resolve(packageName);
            File subfolder = outputSubfolder.toFile();
            if (!subfolder.exists() && !subfolder.mkdirs()) {
                logToTextArea("Failed to create directory: " + subfolder.getAbsolutePath());
                return;
            }

            // Save the converted file
            Path outputFilePath = outputSubfolder.resolve(newFileName);
            File outputFile = outputFilePath.toFile();

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                writer.write(convertedContent);
                api.clearHistory();
                logToTextArea("Converted: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                logToTextArea("Error writing to file: " + outputFile.getAbsolutePath());
                e.printStackTrace();
            }
        }

        private void finalizeFileRenaming(Map<String, String> convertedFilesMap) throws IOException {
            for (Map.Entry<String, String> entry : convertedFilesMap.entrySet()) {
                File originalFile = new File(entry.getKey());
                String newFileName = entry.getValue();
                Path relativePath = inputFolder.toPath().relativize(originalFile.toPath()).getParent();
                String newFilePath = outputFolder.toPath().resolve(relativePath).resolve(newFileName).toString(); // Use
                                                                                                                  // output
                                                                                                                  // directory
                                                                                                                  // and
                                                                                                                  // relative
                                                                                                                  // path

                String fileContent = readFileContent(new File(newFilePath));
                for (String oldFileName : convertedFilesMap.keySet()) {
                    String oldClassName = oldFileName.substring(oldFileName.lastIndexOf(File.separator) + 1)
                            .replace(settings.getInputExtension(), "");
                    String newClassName = convertedFilesMap.get(oldFileName).replace(settings.getOutputExtension(), "");
                    fileContent = replaceClassName(fileContent, oldClassName, newClassName);
                }

                // Ensure the package declaration is present
                String packageName = getPackageNameFromFilePath(newFilePath);
                if (!fileContent.startsWith("package ")) {
                    fileContent = "package " + packageName + ";\n\n" + fileContent;
                }

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(newFilePath))) {
                    writer.write(fileContent);
                    logToTextArea("Finalized: " + newFilePath);
                }
            }
        }

        private String getPackageNameFromFilePath(String filePath) {
            String relativePath = outputFolder.toPath().relativize(Path.of(filePath)).getParent().toString();
            return relativePath.replace(File.separatorChar, '.');
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
                        metaContent.append(fileMetaContent).append("\n\n");
                    }
                }
            }
            return metaContent.toString();
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
        includeMetaCheckBox = new JCheckBox("Include Meta Content");
        useAiFileNameCheckBox = new JCheckBox("Use AI for File Names");
        buttonPanel.add(startButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(includeMetaCheckBox);
        buttonPanel.add(useAiFileNameCheckBox);

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

        FileProcessor processor = new FileProcessor(inputFolder);
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

            return settings;
        }
    }

    private static void validateSetting(Map<String, Object> settings, String key) throws IOException {
        if (!settings.containsKey(key)) {
            throw new IOException("Missing required setting: " + key);
        }
    }
}
