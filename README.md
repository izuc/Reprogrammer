# Code Reprogrammer

Code Reprogrammer is a Java-based desktop application designed to facilitate the conversion of code between different programming languages. Leveraging OpenAI's powerful GPT models, this tool provides an intuitive graphical interface that allows users to effortlessly translate code, ensuring syntactic and semantic accuracy. It's particularly useful for developers working with legacy codebases, transitioning between technologies, or learning new programming languages.

## Features

- **API Integration**: Utilizes OpenAI's GPT models for intelligent code translation.
- **Graphical User Interface**: Easy-to-use interface for setting up and managing code conversion tasks.
- **Customizable Settings**: Allows users to define language settings, API keys, and more for a tailored conversion process.
- **Batch Processing**: Supports the processing of multiple files within a directory for efficient bulk conversion.

## Prerequisites

Before you begin, ensure you have the following installed:
- Java Runtime Environment (JRE) 8 or above.
- An active OpenAI API key to access GPT models.

## Installation

1. Download the latest release of Code Reprogrammer from the GitHub repository.
2. Extract the downloaded ZIP file to your desired location.
3. Ensure that Java is properly installed on your system by running `java -version` in your command line or terminal.

## Usage

To use Code Reprogrammer, follow these steps:

1. **Launch the Application**: Run `java -jar CodeReprogrammer.jar` from your command line or terminal, replacing `CodeReprogrammer.jar` with the actual JAR file name.
2. **Configure Settings**: Enter your OpenAI API key, specify the input and output directories, and set your desired language settings through the GUI.
3. **Select Files**: Use the GUI to select the code files you wish to convert.
4. **Start Conversion**: Click the 'Convert' button to begin the translation process. The status of the conversion will be displayed within the application.

## Configuration

The application can be configured using a `settings.yaml` file, where you can specify target languages, chunk sizes, and other conversion parameters. An example configuration is as follows:

target_language: 'PHP 8.1'
class_structure_threshold: 512
max_chunk_size: 512
...

Refer to the provided settings.yaml file for a full list of configurable options.

## Dependencies
OpenAI Java SDK: For interacting with OpenAI's GPT models.
Jackson: For YAML configuration parsing.
Apache Commons Lang3: For utility functions.
Ensure these dependencies are included in your project setup or build path.

## Contributing
We welcome contributions to Code Reprogrammer! If you have suggestions, bug reports, or contributions, please open an issue or pull request on our GitHub repository.

## License
Code Reprogrammer is open-sourced software licensed under the MIT license.
