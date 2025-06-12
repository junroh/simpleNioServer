# Java NIO/Threaded HTTP Server

This project implements a simple HTTP server in Java. It can operate in two modes:
- **NIO (Non-blocking I/O) Mode:** Utilizes Java NIO for scalable network communication, suitable for handling many concurrent connections with minimal threads.
- **Threaded Mode:** Uses a traditional thread-per-connection model.

This allows for a comparison and understanding of these two different approaches to server-side Java development.

## Server Types

The server can be started in one of two modes, controlled by the `server.type` Java system property.

### NIO (Non-blocking I/O) Server

- **Description:** This mode uses the Java NIO (New I/O) APIs to handle client connections. It's designed for high concurrency and efficiency, as it can manage multiple connections with a small number of threads. Incoming connections are accepted by an `Acceptor` thread, and I/O operations (reading requests, writing responses) are handled by one or more `IOReactor` threads.
- **To run:** Set the system property `-Dserver.type=nio` or do not set the property (it defaults to nio).

### Threaded Server

- **Description:** This mode follows a more traditional approach where each client connection is handled by a dedicated worker thread. While simpler to understand, it can be less scalable than the NIO model under very high loads due to the overhead of managing many threads.
- **To run:** Set the system property `-Dserver.type=threaded`.

## Configuration

The server's behavior can be configured through properties files and system properties.

### Properties Files

- **`src/main/resources/server.properties`**: This file can be used to store server-specific configurations. *(Note: Based on current codebase analysis, this file's usage isn't explicitly detailed in `ServerConfig.java` but is a common convention. The README will reflect its potential use).*
    - `server.port` (Example, actual property might differ or be in `ServerConfig.java`): Defines the port for the server.
    - `ssl.enabled` (Example): To enable/disable SSL.
- **`src/main/resources/log4j.properties`**: Configures the logging behavior for the application using Log4j.

### Key Configuration Options (from `com.jun.config.ServerConfig.java`)

The primary way to configure the server at runtime for fundamental settings like port numbers and SSL is via constants defined in `ServerConfig.java`, which are then used by the `Server.java` main class.

- **Server Type:**
    - System Property: `server.type`
    - Values: `nio` (default), `threaded`
    - Class Constant: `ServerConfig.SERVER_TYPE_PROPERTY_KEY`
- **NIO Server Port:**
    - Port: `8080`
    - Class Constant: `ServerConfig.NIO_SERVER_PORT`
- **Threaded Server Port:**
    - Port: `8081`
    - Class Constant: `ServerConfig.THREADED_SERVER_PORT`
- **NIO Server SSL Enabled:**
    - Value: `true` (can be changed in `ServerConfig.java`)
    - Class Constant: `ServerConfig.NIO_SERVER_SSL_ENABLED`
    - Keystore: `src/main/resources/server.jks` (password: `password`)
    - Truststore: `src/main/resources/trustedCerts.jks` (password: `password`)

To change these settings (e.g., port numbers, SSL enablement directly), you would typically modify the constants in `ServerConfig.java` and recompile the project.

## Building and Running

### Prerequisites

- Java Development Kit (JDK) 8 or higher
- Apache Maven

### Building

1.  **Clone the repository (if you haven't already):**
    ```bash
    git clone <repository-url>
    cd simpleNioServer
    ```
    *(Replace `<repository-url>` with the actual URL of this repository.)*

2.  **Compile the project and create the JAR:**
    Use Maven to build the project. Navigate to the root directory of the project (where `pom.xml` is located) and run:
    ```bash
    mvn clean package
    ```
    This command will compile the source code, run tests (if not excluded), and package the application into a JAR file in the `target/` directory (e.g., `target/simpleNioServer-0.1-SNAPSHOT.jar`).

### Running

Once the project is built, you can run the server from the command line.

1.  **Navigate to the project's root directory.**
2.  **Run the server using the `java -jar` command, specifying the server type with a system property.**

    **To run the NIO Server (default, on port 8080, SSL enabled by default):**
    ```bash
    java -Dserver.type=nio -jar target/simpleNioServer-0.1-SNAPSHOT.jar
    ```
    Or, since NIO is the default:
    ```bash
    java -jar target/simpleNioServer-0.1-SNAPSHOT.jar
    ```

    **To run the Threaded Server (on port 8081):**
    ```bash
    java -Dserver.type=threaded -jar target/simpleNioServer-0.1-SNAPSHOT.jar
    ```

    You should see log messages indicating that the server has started. You can then access it via a web browser or a tool like `curl` (e.g., `curl http://localhost:8081` for the threaded server or `curl https://localhost:8080` for the NIO server if SSL is enabled and correctly configured). Remember that the NIO server has SSL enabled by default, so you'll need to use `https://` and potentially accept a self-signed certificate.

## Project Structure

The project follows a standard Maven directory layout. Key components are organized into the following packages under `src/main/java/com/jun/`:

```
simpleNioServer/
├── pom.xml                   # Maven project configuration
├── README.md                 # This file
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── jun/
│   │   │           ├── Server.java           # Main entry point to start the server
│   │   │           ├── config/
│   │   │           │   └── ServerConfig.java # Server configuration constants (ports, SSL)
│   │   │           ├── http/
│   │   │           │   ├── HttpRequestHandler.java # Handles HTTP request parsing (likely)
│   │   │           │   └── NioMessageHandler.java  # Message handling for NIO
│   │   │           ├── nioServer/              # NIO (Non-blocking I/O) server implementation
│   │   │           │   ├── Acceptor.java       # Accepts incoming connections
│   │   │           │   ├── ConnectedSocket.java # Represents a connected client socket
│   │   │           │   ├── IOReactor.java      # Handles I/O operations for connected sockets
│   │   │           │   ├── NIOHttpService.java # Main class for the NIO HTTP service
│   │   │           │   ├── handler/            # Handlers for different I/O events (read, write)
│   │   │           │   ├── msg/                # Message queue and processing components
│   │   │           │   ├── ssl/                # SSL/TLS related utilities
│   │   │           │   └── utility/            # Utility classes for the NIO server
│   │   │           └── threadedServer/         # Threaded server implementation
│   │   │               ├── SimpleThreadedHttpRequestHandler.java # Request handler for threaded server
│   │   │               ├── ThreadedServer.java # Main class for the threaded server
│   │   │               └── Worker.java         # Worker thread for handling client connections
│   │   ├── resources/
│   │   │   ├── log4j.properties      # Log4j configuration
│   │   │   ├── server.jks            # Keystore for SSL (NIO server)
│   │   │   ├── server.properties     # General server properties (if used)
│   │   │   └── trustedCerts.jks      # Truststore for SSL
│   └── test/
│       ├── java/                     # Unit tests
│       └── resources/                # Test resources (e.g., test.properties)
└── target/                         # Compiled code and packaged JAR
```

-   **`com.jun.Server`**: The main class that launches either the NIO or Threaded server based on system properties.
-   **`com.jun.config`**: Contains `ServerConfig.java` which centralizes configuration constants like port numbers and SSL settings.
-   **`com.jun.http`**: Contains classes related to HTTP message handling. `HttpRequestHandler` likely deals with parsing and processing HTTP requests. `NioMessageHandler` seems specific to message processing within the NIO server.
-   **`com.jun.nioServer`**: This package and its sub-packages implement the Non-blocking I/O server.
    -   `Acceptor`: Listens for new client connections.
    -   `IOReactor`: Manages I/O operations (read/write) for established connections, distributing work likely to handlers.
    -   `NIOHttpService`: Orchestrates the NIO server setup.
    -   `handler`: Contains specific handlers for socket read and write events.
    -   `msg`: Appears to manage message queues and potentially message parsing/writing logic.
    -   `ssl`: SSL context and engine management for HTTPS.
-   **`com.jun.threadedServer`**: This package implements the traditional Threaded server.
    -   `ThreadedServer`: Manages the lifecycle of the threaded server.
    -   `Worker`: Represents a thread that handles a single client connection.
    -   `SimpleThreadedHttpRequestHandler`: Processes HTTP requests within a worker thread.
-   **`src/main/resources`**: Contains configuration files, SSL keystores, and truststores.

## Contributing

Contributions are welcome! If you find any issues or have suggestions for improvements, please feel free to:

1.  **Report an Issue:** Open an issue in the project's issue tracker, providing as much detail as possible.
2.  **Submit a Pull Request:**
    *   Fork the repository.
    *   Create a new branch for your feature or bug fix (`git checkout -b feature/your-feature-name` or `bugfix/issue-description`).
    *   Make your changes and commit them with clear messages.
    *   Push your changes to your fork (`git push origin feature/your-feature-name`).
    *   Open a pull request against the main repository, describing the changes you've made.

## License

This project is currently not licensed. Please refer to the repository owner for licensing information. *(Self-correction: Noticed no LICENSE file was listed by `ls()`, so adding a placeholder)*
