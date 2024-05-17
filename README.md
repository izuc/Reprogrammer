# Code Reprogrammer

Code Reprogrammer is a specialized Java-based desktop application designed to convert code from any language to Java efficiently. Featuring a robust Syntax Checker, the tool ensures that the translated code adheres to Java syntax norms, providing developers a reliable utility for migrating or learning across these popular programming languages.

## Features

- **Flexible AI Model Integration**: Supports using various AI models for code translation, including OpenAI, custom local models, and Claude, ensuring flexibility and adaptability to specific needs.
- **Syntax Validation and Correction**: Incorporates a Syntax Checker that detects and attempts to fix any syntax errors in the converted Java code automatically.
- **Graphical User Interface**: Offers an easy-to-use interface that simplifies the process of setting up and managing code conversion tasks.
- **Batch Processing**: Capable of processing multiple files within a directory, making it ideal for large-scale codebase conversions.

## Prerequisites

Before using Code Reprogrammer, ensure the following components are installed:
- Java Runtime Environment (JRE).
- Appropriate API keys and access configurations for the chosen AI service.

## Installation

1. Download the latest version of Code Reprogrammer from the GitHub repository.
2. Unzip the downloaded file to your desired location.

## Usage

Follow these steps to start using Code Reprogrammer:

1. **Launch the Application**: Execute `java -jar CodeReprogrammer.jar` from the command line, replacing `CodeReprogrammer.jar` with your jar file's name.
2. **Configuration**: Please ensure that the `settings.yaml` file is in the same directory as the jar, setting your API keys and configuration.
3. **File Selection**: Choose the files you wish to convert.
4. **Start the Conversion**: Initiate the conversion by clicking the 'Convert' button. The application will display real-time status updates.

## Configuration

Modify the `settings.yaml` file to customize settings such as the AI model, target language, token limits, and more. Here's an example of what the settings might include:

```yaml
ai_service: "openai"  # Options: "openai", "custom", "claude"
openai_api_key: 'your_openai_api_key'
openai_api_url: 'https://api.openai.com/v1/chat/completions'
openai_model: 'gpt-4o'
custom_text_generation_api_url: 'http://127.0.0.1:5000/v1/chat/completions'
custom_text_generation_model: 'Llama-3-8B-Instruct-Coder-Q6_K'
claude_api_url: 'https://api.anthropic.com/v1/messages'
claude_api_key: 'your_claude_api_key'
claude_api_version: '2023-06-01'
claude_model: 'claude-3-sonnet-20240229'
target_language: 'Java'
max_tokens: 4096
prompt: 'Please convert the following code to Java. Here are the guidelines: 1. Preserve the original structure and logic of the code. 2. Convert syntax to the equivalent Java syntax. 3. Handle necessary imports or package statements. 4. Use appropriate Java equivalents for language-specific libraries or functions. 5. Maintain proper indentation and code formatting. Please provide the converted Java code in your <response>, enclosed within <code> tags. If you have any additional thoughts or suggestions, include them within <thoughts> tags. Thank you!'
output_extension: '.java'
language_extensions:
  C#: '.cs'
  PHP: '.php'
  Go: '.go'
  JavaScript: '.js'

