package software.crud;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;

import com.github.javaparser.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

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

    public JavaConversion(Assistant api, LanguageSettings settings) {
        this.api = api;
        this.settings = settings;
    }

    public String convertCode(String inputCode, String prompt, String originalContent) throws IOException {
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
            return fullConversion.toString();
        } catch (Exception e) {
            logger.error("Error during code conversion: " + e.getMessage(), e);
            throw e;
        }
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
            requestBuilder.append("Original C# code:\n").append(originalContent).append("\n");
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

    private final String targetLanguage;
    private final int maxTokens;
    private final String prompt;
    private final String inputExtension;
    private final String outputExtension;

    public LanguageSettings(String targetLanguage, int maxTokens, String prompt, String inputExtension,
            String outputExtension) {
        this.targetLanguage = targetLanguage;
        this.maxTokens = maxTokens;
        this.prompt = prompt;
        this.inputExtension = inputExtension;
        this.outputExtension = outputExtension;
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
        return inputExtension;
    }
}

public class Reprogrammer extends JFrame {
    private JTextArea convertedCodeTextArea;
    private JTextField inputDirectoryPathField;
    private JTextField outputDirectoryPathField;
    private JLabel statusLabel;
    private File inputFolder;
    private File outputFolder;
    private JProgressBar progressBar;
    private JLabel fileStatusLabel;
    private JButton convertButton;
    private JFileChooser fileChooser = new JFileChooser();
    private LanguageSettings settings;
    private Assistant api;
    private static final Logger logger = LoggerFactory.getLogger(Reprogrammer.class);

    private class FileProcessor extends SwingWorker<Void, Integer> {
        private final File directory;

        public FileProcessor(File directory) {
            this.directory = directory;
        }

        @Override
        protected Void doInBackground() {
            try {
                System.out.println("API call started.");
                if (api.testApiConnection()) {
                    System.out.println("API connection successful.");
                    processDirectory(directory);
                } else {
                    System.err.println("Failed to connect to the API.");
                    cancel(true);
                }
            } catch (IOException e) {
                System.err.println("IO Exception: " + e.getMessage());
                cancel(true);
            } catch (Exception e) {
                System.err.println("General Exception: " + e.getMessage());
                cancel(true);
            }
            return null;
        }

        @Override
        protected void done() {
            if (!isCancelled()) {
                try {
                    get();
                    System.out.println("All files processed.");
                    fileStatusLabel.setText("Processing Complete");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    System.err.println("Error during processing: " + cause.getMessage());
                    JOptionPane.showMessageDialog(null, "Error: " + cause.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Task interrupted.");
                }
            }
            convertButton.setEnabled(true);
        }

        @Override
        protected void process(List<Integer> chunks) {
            if (!chunks.isEmpty()) {
                int latestValue = chunks.get(chunks.size() - 1);
                progressBar.setValue(latestValue);
            }
        }

        private void processDirectory(File directory) throws IOException {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(settings.getInputExtension()));
            int totalFiles = countFiles(directory);
            int processedFiles = 0;

            if (files != null) {
                for (File file : files) {
                    if (isCancelled()) {
                        break;
                    }
                    if (file.isDirectory()) {
                        processDirectory(file);
                    } else {
                        processFile(file);
                        processedFiles++;
                        int progress = (int) ((processedFiles / (double) totalFiles) * 100);
                        publish(progress);
                    }
                }
            }
        }

        private int countFiles(File directory) {
            int count = 0;
            File[] files = directory.listFiles((dir, name) -> name.endsWith(settings.getInputExtension()));
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        count += countFiles(file);
                    } else {
                        count++;
                    }
                }
            }
            return count;
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

        setupTextArea();
        setupDirectoryPanel();
        setupActionPanel();

        pack();
        setSize(600, 400);
        setLocationRelativeTo(null);
    }

    private void addListeners() {
        convertButton.addActionListener(this::convertFiles);
    }

    private void setupTextArea() {
        convertedCodeTextArea = new JTextArea();
        convertedCodeTextArea.setEditable(false);
        convertedCodeTextArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(convertedCodeTextArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setPreferredSize(new Dimension(600, 400));

        add(scrollPane, BorderLayout.CENTER);
    }

    private void setupDirectoryPanel() {
        JPanel directoryPanel = new JPanel(new GridLayout(0, 3, 10, 10));
        directoryPanel.setBorder(BorderFactory.createTitledBorder("Directories"));

        inputDirectoryPathField = new JTextField();
        outputDirectoryPathField = new JTextField();
        JButton selectInputFolderButton = new JButton("Select Input Folder");
        JButton selectOutputFolderButton = new JButton("Select Output Folder");

        directoryPanel.add(new JLabel("Input Directory:"));
        directoryPanel.add(inputDirectoryPathField);
        directoryPanel.add(selectInputFolderButton);

        directoryPanel.add(new JLabel("Output Directory:"));
        directoryPanel.add(outputDirectoryPathField);
        directoryPanel.add(selectOutputFolderButton);

        selectInputFolderButton.addActionListener(this::selectInputFolder);
        selectOutputFolderButton.addActionListener(this::selectOutputFolder);

        add(directoryPanel, BorderLayout.NORTH);
    }

    private void setupActionPanel() {
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        convertButton = new JButton("Convert");
        statusLabel = new JLabel("Status: Idle");
        progressBar = new JProgressBar();
        fileStatusLabel = new JLabel("File Status: Idle");

        actionPanel.add(convertButton);
        actionPanel.add(statusLabel);
        actionPanel.add(progressBar);
        actionPanel.add(fileStatusLabel);

        add(actionPanel, BorderLayout.SOUTH);
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

    private void convertFiles(ActionEvent e) {
        if (fileStatusLabel != null) {
            fileStatusLabel.setText("Processing...");
        }
        String inputFolderPath = inputDirectoryPathField.getText();
        String outputFolderPath = outputDirectoryPathField.getText();

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

        convertButton.setEnabled(false);
        fileStatusLabel.setText("Processing...");
        progressBar.setValue(0);

        FileProcessor processor = new FileProcessor(inputFolder);
        processor.execute();
    }

    private boolean processFile(File file) {
        try {
            JavaConversion javaConversion = new JavaConversion(api, settings);
            String fileContent = readFileContent(file);
            if (fileContent.isEmpty()) {
                System.out.println("File content is empty, skipping conversion.");
                return false;
            }

            String convertedContent = javaConversion.convertCode(fileContent, settings.getPrompt(), "");
            if (convertedContent.trim().isEmpty()) {
                System.out.println("Initial conversion failed or resulted in empty content.");
                return false;
            }
            updateTextArea(convertedContent);
            saveConvertedFile(file, convertedContent);

            int maxAttempts = 5;
            boolean isConversionSuccessful = false;

            for (int attempt = 0; attempt < maxAttempts && !isConversionSuccessful; attempt++) {
                List<SyntaxError> errors = checkSyntax(convertedContent);
                if (errors.isEmpty()) {
                    saveConvertedFile(file, convertedContent);
                    isConversionSuccessful = true;
                } else {
                    System.out.println("Syntax errors found: " + errors);

                    String aiResponse = api.generateText("Do errors need fixing?", 100, false);
                    if (aiResponse.toLowerCase().contains("yes")) {
                        String errorMessages = errors.stream()
                                .map(error -> "Line " + error.getLineNumber() + ": " + error.getMessage())
                                .collect(Collectors.joining("\n"));

                        if (errorMessages.isEmpty()) {
                            continue;
                        }

                        String fixPrompt = "Please output the entire file from the start without omitting anything fixing the following errors:\n"
                                + errorMessages;

                        convertedContent = javaConversion.convertCode(convertedContent, fixPrompt, fileContent);
                        updateTextArea(convertedContent);
                        saveConvertedFile(file, convertedContent);
                    } else {
                        System.out.println("Based on AI response, no error fixing needed. Stopping attempts.");
                        break;
                    }
                }
            }

            if (!isConversionSuccessful) {
                System.out.println("Could not resolve all syntax errors after multiple attempts.");
            }
            return isConversionSuccessful;
        } catch (Exception e) {
            System.err.println("Error processing file: " + file.getAbsolutePath());
            e.printStackTrace();
            return false;
        }
    }

    private List<SyntaxError> checkSyntax(String javaCode) {
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> result = parser.parse(javaCode);
        List<SyntaxError> syntaxErrors = new ArrayList<>();
        result.getProblems().forEach(problem -> {
            String message = problem.getMessage();
            int lineNumber = problem.getLocation()
                    .flatMap(location -> location.getBegin().getRange())
                    .map(range -> range.begin.line)
                    .orElse(-1);
            syntaxErrors.add(new SyntaxError(message, lineNumber));
        });
        return syntaxErrors;
    }

    private void updateTextArea(String content) {
        convertedCodeTextArea.setText("");

        StringBuilder formattedText = new StringBuilder();
        String[] lines = content.split("\\r?\\n|\\r|\\n");
        for (String line : lines) {
            formattedText.append(line).append(System.lineSeparator());
        }

        convertedCodeTextArea.append(formattedText.toString());

        convertedCodeTextArea.setCaretPosition(convertedCodeTextArea.getDocument().getLength());
    }

    private void saveConvertedFile(File originalFile, String convertedContent) throws IOException {
        String outputExtension = settings.getOutputExtension();
    
        // Correctly construct the relative path
        Path relativePath = inputFolder.toPath().relativize(originalFile.toPath());
        Path outputPath = outputFolder.toPath().resolve(relativePath);
    
        // Ensure the directory structure exists
        Path parentDir = outputPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
            } catch (IOException e) {
                System.err.println("Failed to create directory: " + parentDir);
                return;
            }
        }
    
        // Change the extension of the output file
        String outputFileName = outputPath.getFileName().toString();
        int lastDotIndex = outputFileName.lastIndexOf('.');
        if (lastDotIndex != -1) {
            outputFileName = outputFileName.substring(0, lastDotIndex) + outputExtension;
        } else {
            outputFileName += outputExtension;
        }
    
        // Create the final output file path
        Path finalOutputPath = outputPath.resolveSibling(outputFileName);
        saveConvertedContent(finalOutputPath.toFile(), convertedContent);
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

    private void saveConvertedContent(File originalFile, String convertedContent) {
        String outputExtension = settings.getOutputExtension();
        
        // Construct the relative path
        Path relativePath = inputFolder.toPath().relativize(originalFile.toPath()).getParent();
        
        // Ensure the directory structure exists
        Path outputSubfolder = outputFolder.toPath().resolve(relativePath);
        File subfolder = outputSubfolder.toFile();
        if (!subfolder.exists() && !subfolder.mkdirs()) {
            System.err.println("Failed to create directory: " + subfolder);
            return;
        }
    
        // Extract the base file name without its extension
        String originalFileName = originalFile.getName();
        int lastDotIndex = originalFileName.lastIndexOf('.');
        String baseFileName = (lastDotIndex > 0) ? originalFileName.substring(0, lastDotIndex) : originalFileName;
        String newFileName = baseFileName + outputExtension;
    
        // Create the final output file path
        Path outputFilePath = outputSubfolder.resolve(newFileName);
        File outputFile = outputFilePath.toFile();
    
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(convertedContent);
            api.clearHistory();
        } catch (IOException e) {
            e.printStackTrace();
        }
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
            LanguageSettings languageSettings = new LanguageSettings(
                    (String) settings.get("target_language"),
                    (Integer) settings.get("max_tokens"),
                    (String) settings.get("prompt"),
                    (String) settings.get("input_extension"),
                    (String) settings.get("output_extension"));

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
            validateSetting(settings, "input_extension");
            validateSetting(settings, "output_extension");

            return settings;
        }
    }

    private static void validateSetting(Map<String, Object> settings, String key) throws IOException {
        if (!settings.containsKey(key)) {
            throw new IOException("Missing required setting: " + key);
        }
    }
}