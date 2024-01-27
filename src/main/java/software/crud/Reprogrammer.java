package software.crud;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.net.URL;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import com.theokanning.openai.completion.chat.ChatCompletionChoice;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;
import java.time.Duration;

class API {
    private OpenAiService service;

    public API(String token) {
        Duration timeoutDuration = Duration.ofSeconds(30);
        this.service = new OpenAiService(token, timeoutDuration);
    }

    public boolean testApiConnection() {
        // Create a test message without specifying 'name'
        ChatMessage testMessage = new ChatMessage("system", "Connection test message", "message_1");
    
        // Create a ChatCompletionRequest with the test message
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model("gpt-4-turbo-preview")
                .messages(Arrays.asList(testMessage))
                .n(1)
                .maxTokens(100)
                .logitBias(new HashMap<>()) // Ensure this is correctly utilized as per your use case
                .build();
    
        // Execute the API call synchronously
        ChatCompletionResult result = service.createChatCompletion(chatCompletionRequest);
        List<ChatCompletionChoice> response = result.getChoices();

        // Check if the response is successful
        if (!response.isEmpty()) {
            System.out.println("API connection test successful.");
            return true;
        } else {
            System.err.println("API connection test failed");
            return false;
        }
    }    
    
    public String convertUsingAPI(String promptText, String fileContent) {
        String combinedQuestion = promptText + ": " + fileContent;
    
        ChatMessage userMessage = new ChatMessage("user", combinedQuestion, "message_1");
        List<ChatMessage> messages = Arrays.asList(userMessage);
    
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model("gpt-4-turbo-preview")
                .messages(messages)
                .n(1)
                .maxTokens(4096)
                .logitBias(new HashMap<>()) // Ensure this is correctly utilized as per your use case
                .build();
    
        ChatCompletionResult result = service.createChatCompletion(chatCompletionRequest);

        List<ChatCompletionChoice> response = result.getChoices();

        if (!response.isEmpty()) {
            // Extract the ChatMessage from the last ChatCompletionChoice
            ChatMessage lastMessage = response.get(response.size() - 1).getMessage(); // Ensure getMessage() returns a ChatMessage
            return lastMessage.getContent(); // Assuming getContent() returns the text content of the ChatMessage
        } else {
            System.err.println("Request failed");
        }
        return "";
    }         
}

class CodeChunker {

    private final API api;
    private final LanguageSettings settings;
    private String promptText;
    private String code;
    private List<String> chunks;

    public CodeChunker(API api, LanguageSettings settings, String promptText, String code) {
        this.api = api;
        this.settings = settings;
        this.promptText = promptText;
        this.code = code;
        this.chunks = convertChunks();
    }

    private List<String> convertChunks() {
        ArrayList<String> chunks = splitFileContent();

        for (int i = 0; i < chunks.size(); i++) {
            try {
                String chunk = chunks.get(i);
                if (isClassStructure(chunk)) {
                    String convertedStructure = convertClassStructure(chunk);
                    chunks.set(i, convertedStructure);
                } else {
                    String convertedChunk = convertLargeChunk(promptText, chunk);
                    chunks.set(i, convertedChunk);
                }
            } catch (Exception e) {
                // Handle the exception appropriately
                System.out.println("Error converting chunk: " + e.getMessage());
                chunks.set(i, ""); // Or handle it in a different way
            }
        }

        return chunks;
    }

    private String convertClassStructure(String chunk) {
        String classStructure = getClassStructure(chunk);
        String convertedStructure = api.convertUsingAPI(promptText, classStructure);
        String methods = getMethods(chunk);
        if (!methods.isEmpty()) {
            String convertedMethods = convertMethod(methods);
            return convertedStructure + convertedMethods;
        }
        return convertedStructure;
    }

    private String convertMethod(String method) {
        if (method.length() > settings.getMaxChunkSize()) {
            return convertLargeMethod(method);
        } else {
            return api.convertUsingAPI(promptText, method);
        }
    }

    private String convertLargeMethod(String method) {
        StringBuilder convertedMethod = new StringBuilder();
        String[] lines = method.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        int braceCount = 0;

        for (String line : lines) {
            currentChunk.append(line).append("\n");
            braceCount = updateBraceCount(line, braceCount);

            if (shouldProcessCurrentChunk(currentChunk, braceCount)) {
                appendConvertedChunk(convertedMethod, currentChunk);
                currentChunk = new StringBuilder();
            }
        }

        appendRemainingChunkIfAny(convertedMethod, currentChunk);
        return convertedMethod.toString();
    }

    private int updateBraceCount(String line, int braceCount) {
        if (line.contains("{")) {
            braceCount++;
        }
        if (line.contains("}")) {
            braceCount--;
        }
        return braceCount;
    }

    private boolean shouldProcessCurrentChunk(StringBuilder currentChunk, int braceCount) {
        return currentChunk.length() > settings.getMaxChunkSize() && braceCount == 0;
    }

    private void appendConvertedChunk(StringBuilder convertedMethod, StringBuilder currentChunk) {
        String convertedChunk = api.convertUsingAPI(promptText, currentChunk.toString());
        convertedMethod.append(convertedChunk);
    }

    private void appendRemainingChunkIfAny(StringBuilder convertedMethod, StringBuilder currentChunk) {
        if (currentChunk.length() > 0) {
            String convertedChunk = api.convertUsingAPI(promptText, currentChunk.toString());
            convertedMethod.append(convertedChunk);
        }
    }

    private boolean isClassStructure(String chunk) {
        return chunk.trim().startsWith("class ");
    }

    private String getClassStructure(String chunk) {
        StringBuilder classStructure = new StringBuilder();
        String[] lines = chunk.split("\n");
        boolean insideMethod = false;

        for (String line : lines) {
            if (line.trim().endsWith("{")) {
                insideMethod = true;
            } else if (line.trim().endsWith("}")) {
                insideMethod = false;
            }

            if (!insideMethod) {
                classStructure.append(line).append("\n");
            }
        }

        return classStructure.toString();
    }

    private String getMethods(String chunk) {
        StringBuilder methods = new StringBuilder();
        String[] lines = chunk.split("\n");
        boolean insideMethod = false;

        for (String line : lines) {
            if (line.trim().endsWith("{")) {
                insideMethod = true;
                methods.append(line).append("\n");
            } else if (line.trim().endsWith("}")) {
                insideMethod = false;
                methods.append(line).append("\n");
            } else if (insideMethod) {
                methods.append(line).append("\n");
            }
        }

        return methods.toString();
    }

    private ArrayList<String> splitFileContent() {
        ArrayList<String> chunks = new ArrayList<>();
        StringBuilder chunkBuilder = new StringBuilder();
        int currentSize = 0;
        String[] lines = code.split("\n");

        for (String line : lines) {
            int lineLength = line.length() + 1; // Adding 1 for the line break

            // Check if adding the current line would exceed the maximum chunk size
            if (currentSize + lineLength > settings.getMaxChunkSize() && currentSize > 0) {
                // Add the current chunk to the list and reset the chunk builder
                chunks.add(chunkBuilder.toString());
                chunkBuilder.setLength(0); // Efficiently clears the builder
                currentSize = 0;
            }

            // Append the current line to the chunk
            chunkBuilder.append(line).append("\n");
            currentSize += lineLength;
        }

        // Add the last chunk if there is any remaining content
        if (chunkBuilder.length() > 0) {
            chunks.add(chunkBuilder.toString());
        }

        return chunks;
    }
	
    private String convertLargeChunk(String prompt, String chunk) {
        StringBuilder convertedContent = new StringBuilder();
        // Iteratively split the chunk into smaller parts based on language-specific patterns
        String extractedPart;
        while (chunk.length() > settings.getMaxChunkSize()) {
            extractedPart = extractConvertiblePart(convertedContent, chunk);
            convertedContent.append(processChunk(api.convertUsingAPI(prompt, extractedPart)));
            chunk = chunk.substring(extractedPart.length());
        }
        extractedPart = chunk; // Process remaining chunk
        convertedContent.append(processChunk(api.convertUsingAPI(prompt, extractedPart)));
        return convertedContent.toString();
    }

    private String extractConvertiblePart(StringBuilder convertedContent, String chunk) {
        StringBuilder extractedPart = new StringBuilder();
        boolean insideBlock = false;
        int braceCount = 0;

        for (String line : chunk.split("\n")) {
            // Check if line indicates a block definition
            if (!insideBlock && line.trim().matches(settings.getBlockStartKeyword()) && line.trim().endsWith(settings.getBlockEndKeyword())) {
                insideBlock = true;
                extractedPart.append(line).append("\n");
            } else if (insideBlock) {
                if (line.contains(settings.getBlockOpenSymbol()) && line.contains(settings.getBlockCloseSymbol())) {
                    // Extract single-line block
                    extractedPart.append(line).append("\n");
                } else {
                    extractedPart.append(line).append("\n");
					braceCount += StringUtils.countMatches(line, settings.getBlockOpenSymbol()) - StringUtils.countMatches(line, settings.getBlockCloseSymbol());
                }
                if (braceCount == 0) {
                    insideBlock = false;
                    break;
                }
            } else {
                // Only extract non-empty single-line statements if not inside a block
                if (!line.trim().isEmpty()) {
                    extractedPart.append(line).append("\n");
                }
            }
        }

        // Extract class structure if the extracted part exceeds a certain size threshold
        if (extractedPart.length() > settings.getClassStructureThreshold()) {
            String classStructure = api.convertUsingAPI(promptText, extractedPart.toString());
            convertedContent.append(classStructure);
        }

        return extractedPart.toString();
    }

    private String processChunk(String chunk) {
        String openingTag = settings.getOpeningTag();
        String closingTag = settings.getClosingTag();

        // Extract content within tags (if any)
        String content = chunk;
        if (!openingTag.isEmpty() && !closingTag.isEmpty()) {
            int openingTagIndex = chunk.indexOf(openingTag);
            int closingTagIndex = chunk.indexOf(closingTag);
            if (openingTagIndex >= 0 && closingTagIndex > openingTagIndex) {
                content = chunk.substring(openingTagIndex + openingTag.length(), closingTagIndex);
            }
        }

        return content;
    }

    @Override
    public String toString() {
        StringBuilder combinedContent = new StringBuilder();
        for (String chunk : chunks) {
            combinedContent.append(chunk);
        }
        return combinedContent.toString();
    }
}

class LanguageSettings {

    // Target language for conversion
    private final String targetLanguage;

    private int classStructureThreshold;
    // Maximum chunk size for API interaction
    private final int maxChunkSize;

    // Opening and closing tags for code blocks (optional)
    private final String openingTag;
    private final String closingTag;

    // Keywords and symbols for block identification
    private final String blockStartKeyword;
    private final String blockEndKeyword;
    private final String blockOpenSymbol;
    private final String blockCloseSymbol;
    private final String variableDeclarationPattern;
    private final String variableConversionFormat;
    private final String prompt;
    private final String inputExtension;
    private final String outputExtension;

    public LanguageSettings(String targetLanguage, int classStructureThreshold, int maxChunkSize, String openingTag, String closingTag,
                        String blockStartKeyword, String blockEndKeyword, String blockOpenSymbol, String blockCloseSymbol,
                        String variableDeclarationPattern, String variableConversionFormat, String prompt, String inputExtension, String outputExtension) {
        this.targetLanguage = targetLanguage;
        this.maxChunkSize = maxChunkSize;
        this.openingTag = openingTag;
        this.closingTag = closingTag;
        this.blockStartKeyword = blockStartKeyword;
        this.blockEndKeyword = blockEndKeyword;
        this.blockOpenSymbol = blockOpenSymbol;
        this.blockCloseSymbol = blockCloseSymbol;
        this.variableDeclarationPattern = variableDeclarationPattern;
        this.variableConversionFormat = variableConversionFormat;
        this.prompt = prompt;
        this.inputExtension = inputExtension;
        this.outputExtension = outputExtension;
    }

    public int getClassStructureThreshold() {
        return classStructureThreshold;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public String getOpeningTag() {
        return openingTag;
    }

    public String getClosingTag() {
        return closingTag;
    }

    public String getBlockStartKeyword() {
        return blockStartKeyword;
    }

    public String getBlockEndKeyword() {
        return blockEndKeyword;
    }

    public String getBlockOpenSymbol() {
        return blockOpenSymbol;
    }

    public String getBlockCloseSymbol() {
        return blockCloseSymbol;
    }

    public String getOutputExtension() {
        return outputExtension;
    }

    public String getVariableDeclarationPattern() {
        return variableDeclarationPattern;
    }

    public String getVariableConversionFormat() {
        return variableConversionFormat;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getInputExtension() {
        return inputExtension;
    }
}

public class Reprogrammer extends JFrame {
    private JTextField openingTagField;
    private JTextField closingTagField;
    private JTextField inputDirectoryPathField;
    private JTextField outputDirectoryPathField;
    private JTextField openAIKeyField;
    private JTextField promptField;
    private JTextField inputExtensionField;
    private JTextField outputExtensionField;
    private JTextField targetLanguageField;
    private JLabel statusLabel;
    private File inputFolder;
    private File outputFolder;
    private JProgressBar progressBar;
    private JLabel fileStatusLabel;
    private JButton convertButton;
    private JFileChooser fileChooser = new JFileChooser(); // Single file chooser for both input and output
    private LanguageSettings settings;
    private API api;

    private class FileProcessor extends SwingWorker<Void, String> {
        private final File directory;

        public FileProcessor(File directory) {
            this.directory = directory;
        }

        @Override
        protected Void doInBackground() throws Exception {
            processDirectory(directory);
            return null;
        }

        @Override
        protected void process(List<String> chunks) {
            // Update the status label with the latest file name
            fileStatusLabel.setText(chunks.get(chunks.size() - 1));
        }

        @Override
        protected void done() {
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(0);
                fileStatusLabel.setText("Processing Complete");
                convertButton.setEnabled(true);
            });
        }

        private void processDirectory(File directory) throws IOException {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(inputExtensionField.getText()));
            if (files != null) {
                progressBar.setMaximum(files.length);
                progressBar.setValue(0);

                for (File file : files) {
                    if (file.isDirectory()) {
                        processDirectory(file);
                    } else {
                        processFile(file);
                        progressBar.setValue(progressBar.getValue() + 1);
                        publish("Processing: " + file.getName());
                    }
                }
            }
        }
    }

    private class DirectoryFieldListener implements DocumentListener {
        private boolean isInputField;

        public DirectoryFieldListener(boolean isInputField) {
            this.isInputField = isInputField;
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            updateDirectory();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            updateDirectory();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            updateDirectory();
        }

        private void updateDirectory() {
            SwingUtilities.invokeLater(() -> {
                String path = isInputField ? inputDirectoryPathField.getText() : outputDirectoryPathField.getText();
                File file = new File(path);
                if (file.exists() && file.isDirectory()) {
                    fileChooser.setCurrentDirectory(file);
                }
            });
        }
    }


    public Reprogrammer(LanguageSettings settings) {
        this.settings = settings;
        inputDirectoryPathField = new JTextField();
        outputDirectoryPathField = new JTextField();
        inputDirectoryPathField.getDocument().addDocumentListener(new DirectoryFieldListener(true));
        outputDirectoryPathField.getDocument().addDocumentListener(new DirectoryFieldListener(false));
        openAIKeyField = new JTextField();
        targetLanguageField = new JTextField(settings.getTargetLanguage()); // Default target language
        promptField = new JTextField(settings.getPrompt()); // Complete prompt text
        inputExtensionField = new JTextField(settings.getInputExtension()); // Default input extension
        outputExtensionField = new JTextField(settings.getOutputExtension()); // Default output extension
        openingTagField = new JTextField(settings.getOpeningTag());
        closingTagField = new JTextField(settings.getClosingTag());
        convertButton = new JButton("Convert");
        statusLabel = new JLabel("Status: Idle");
        progressBar = new JProgressBar();
        fileStatusLabel = new JLabel("File Status: Idle");
        
        setTitle("Reprogrammer - Created by www.lance.name");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10)); // Use BorderLayout for the main layout
    
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS)); // Vertical box layout for the main panel
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Add padding around the main panel
    
        // Group for directory selection
        JPanel directoryPanel = new JPanel();
        directoryPanel.setLayout(new GridLayout(0, 3, 10, 10)); // Grid layout for the directory section
        directoryPanel.setBorder(BorderFactory.createTitledBorder("Directories"));
    
        JButton selectInputFolderButton = new JButton("Select Input Folder");
        JButton selectOutputFolderButton = new JButton("Select Output Folder");
    
        directoryPanel.add(new JLabel("Input Directory:"));
        directoryPanel.add(inputDirectoryPathField);
        directoryPanel.add(selectInputFolderButton);
    
        directoryPanel.add(new JLabel("Output Directory:"));
        directoryPanel.add(outputDirectoryPathField);
        directoryPanel.add(selectOutputFolderButton);
    
        mainPanel.add(directoryPanel);
    
        // Group for other settings
        JPanel settingsPanel = new JPanel(new GridLayout(0, 2, 10, 10)); // Grid layout for settings
        settingsPanel.setBorder(BorderFactory.createTitledBorder("Settings"));
    
        settingsPanel.add(new JLabel("Target Language:"));
        settingsPanel.add(targetLanguageField);
    
        settingsPanel.add(new JLabel("OPENAI API Key:"));
        settingsPanel.add(openAIKeyField);
    
        settingsPanel.add(new JLabel("Prompt:"));
        settingsPanel.add(promptField);
    
        settingsPanel.add(new JLabel("Input File Extension:"));
        settingsPanel.add(inputExtensionField);
    
        settingsPanel.add(new JLabel("Output File Extension:"));
        settingsPanel.add(outputExtensionField);

        settingsPanel.add(new JLabel("Opening Tag:"));
        settingsPanel.add(openingTagField);
    
        settingsPanel.add(new JLabel("Closing Tag:"));
        settingsPanel.add(closingTagField);
    
        mainPanel.add(settingsPanel);
    
        // Group for status and action buttons
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actionPanel.add(convertButton);
        actionPanel.add(statusLabel);
        actionPanel.add(progressBar);
    
        mainPanel.add(actionPanel);
    
        add(mainPanel, BorderLayout.CENTER); // Add the main panel to the center
    
        selectInputFolderButton.addActionListener(this::selectInputFolder);
        selectOutputFolderButton.addActionListener(this::selectOutputFolder);
        convertButton.addActionListener(this::convertFiles);

        setIconImage(loadIconImage("/icons/app.png"));
    
        pack();
        setSize(600, 400); // Set the size after packing
        setLocationRelativeTo(null); // Center the window
    }

    private Image loadIconImage(String path) {
        URL iconURL = getClass().getResource(path);
        if (iconURL != null) {
            return new ImageIcon(iconURL).getImage();
        } else {
            System.err.println("Icon file not found at path: " + path);
            return null;
        }
    }
	
    private void selectInputFolder(ActionEvent e) {
        File selectedFolder = selectFolder();
        if (selectedFolder != null) {
            inputFolder = selectedFolder;
            SwingUtilities.invokeLater(() -> {
                inputDirectoryPathField.setText(inputFolder.getAbsolutePath());
                statusLabel.setText("Selected Input Folder: " + inputFolder.getAbsolutePath());
            });
        }
    }    

    private void selectOutputFolder(ActionEvent e) {
        File selectedFolder = selectFolder();
        if (selectedFolder != null) {
            outputFolder = selectedFolder;
            SwingUtilities.invokeLater(() -> {
                outputDirectoryPathField.setText(outputFolder.getAbsolutePath());
                statusLabel.setText("Selected Output Folder: " + outputFolder.getAbsolutePath());
            });
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
        // Parse the directory paths from the text fields

        if (openAIKeyField.getText().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter your OpenAI API key.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        api = new API(openAIKeyField.getText());

        String inputFolderPath = inputDirectoryPathField.getText();
        String outputFolderPath = outputDirectoryPathField.getText();
    
        if (inputFolderPath.isEmpty() || outputFolderPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter both input and output folder paths.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
    
        // Convert the string paths to File objects
        inputFolder = new File(inputFolderPath);
        outputFolder = new File(outputFolderPath);
    
        // Check if the directories are valid
        if (!inputFolder.isDirectory() || !outputFolder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Invalid input or output folder path.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
    
        convertButton.setEnabled(false); // Disable the convert button
        fileStatusLabel.setText("Processing..."); // Update file status label
    
        if (api.testApiConnection()) {
            FileProcessor fileProcessor = new FileProcessor(inputFolder);
            fileProcessor.execute();
        } else {
            JOptionPane.showMessageDialog(this, "Failed to connect to the API. Please check your network or API key.", "API Connection Error", JOptionPane.ERROR_MESSAGE);
        }
    }    

    private void processFile(File file) throws IOException {
        String promptText = promptField.getText();

        if (promptText.isEmpty()) {
            promptText = settings.getPrompt();
        }

        String fileContent = readFileContent(file);

        CodeChunker chunker = new CodeChunker(api, settings, promptText, fileContent);

        String outputExtension = settings.getOutputExtension();
        String relativePath = inputFolder.toURI().relativize(file.getParentFile().toURI()).getPath();
        File subfolder = new File(outputFolder, relativePath);

        if (!subfolder.exists()) {
            subfolder.mkdirs();
        }

        String originalFileName = file.getName();
        String newFileName = originalFileName.substring(0, originalFileName.lastIndexOf('.')) + outputExtension;
        File outputFile = new File(subfolder, newFileName);

        saveConvertedContent(outputFile, chunker.toString());
    }

    private String readFileContent(File file) {
        StringBuilder contentBuilder = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String currentLine;
            while ((currentLine = br.readLine()) != null) {
                contentBuilder.append(currentLine).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return contentBuilder.toString();
    }
    
    private void saveConvertedContent(File originalFile, String convertedContent) {
        // Retrieving user-specified tags
        String openingTag = openingTagField.getText();
        String closingTag = closingTagField.getText();

        String extractedContent = convertedContent;
        if (!openingTag.isEmpty() && !closingTag.isEmpty()) {
            int openingTagIndex = extractedContent.indexOf(openingTag);
            int closingTagIndex = extractedContent.indexOf(closingTag);
            if (openingTagIndex >= 0 && closingTagIndex > openingTagIndex) {
                extractedContent = extractedContent.substring(openingTagIndex + openingTag.length(), closingTagIndex);
            }
        }
    
        // Determine output file extension and relative path
        String outputExtension = outputExtensionField.getText();
        String relativePath = inputFolder.toURI().relativize(originalFile.getParentFile().toURI()).getPath();
        File subfolder = new File(outputFolder, relativePath);
    
        // Create subfolder if it does not exist
        if (!subfolder.exists()) {
            subfolder.mkdirs();
        }
    
        // Construct the new file name and path
        String originalFileName = originalFile.getName();
        
        int lastDotIndex = originalFileName.lastIndexOf('.');
        String baseFileName = (lastDotIndex > 0) ? originalFileName.substring(0, lastDotIndex) : originalFileName;
        String newFileName = baseFileName + outputExtension;

        File outputFile = new File(originalFile.getParent(), newFileName); // Use original file's parent directory
    
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            // Write the extracted content with new lines after opening and before closing tags
            if (!extractedContent.isEmpty()) {
                writer.write(openingTag + "\n" + extractedContent + "\n" + closingTag);
            } else {
                writer.write(convertedContent);
            }
        } catch (IOException e) {
            e.printStackTrace();
            // Handle the exception appropriately
        }
    }    

    public static void main(String[] args) {
        String settingsFilePath = "settings.yaml"; // Default settings file path
        Map<String, Object> settings;
    
        if (args.length > 0) {
            settingsFilePath = args[0]; // Override default with first argument
        }
    
        try {
            settings = readSettings(settingsFilePath);
            // Extract language settings
            LanguageSettings languageSettings = new LanguageSettings(
                    (String) settings.get("target_language"),
                    (Integer) settings.get("class_structure_threshold"),
                    (Integer) settings.get("max_chunk_size"),
                    (String) settings.get("opening_tag"),
                    (String) settings.get("closing_tag"),
                    (String) settings.get("block_start_keyword"),
                    (String) settings.get("block_end_keyword"),
                    (String) settings.get("block_open_symbol"),
                    (String) settings.get("block_close_symbol"),
                    (String) settings.get("variable_declaration_pattern"),
                    (String) settings.get("variable_conversion_format"),
                    (String) settings.get("prompt"),
                    (String) settings.get("input_extension"),
                    (String) settings.get("output_extension")
            );
    
            SwingUtilities.invokeLater(() -> {
                Reprogrammer gui = new Reprogrammer(languageSettings);
                gui.setVisible(true);
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Map<String, Object> readSettings(String filename) throws IOException {
        // Load the YAML file
        try (InputStream inputStream = new FileInputStream(filename)) {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    
            // Use TypeReference to specify the type of Map
            Map<String, Object> settings = mapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {});
    
            // Validate required settings
            validateSetting(settings, "target_language");
            validateSetting(settings, "class_structure_threshold");
            validateSetting(settings, "max_chunk_size");
            validateSetting(settings, "block_start_keyword");
            validateSetting(settings, "block_end_keyword");
            validateSetting(settings, "block_open_symbol");
            validateSetting(settings, "block_close_symbol");
            validateSetting(settings, "variable_declaration_pattern");
            validateSetting(settings, "variable_conversion_format");
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