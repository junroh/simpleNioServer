# Java NIO/Threaded HTTP Server

This project implements a simple HTTP server in Java, offering two operational modes:
- **NIO (Non-blocking I/O) Mode:** Leverages Java NIO for scalable network communication, ideal for managing numerous concurrent connections with a minimal number of threads.
- **Threaded Mode:** Employs a classic thread-per-connection model.

This project allows for a comparison and understanding of these two distinct approaches to server-side Java development.

## Server Types

The server operates in one of two modes, selectable via the `server.type` Java system property:

### NIO (Non-blocking I/O) Server

- **Description:** This mode utilizes the Java NIO (New I/O) APIs for handling client connections. It is designed for high concurrency and efficiency, capable of managing multiple connections with a limited number of threads. An `Acceptor` thread accepts incoming connections, while I/O operations (reading requests, writing responses) are managed by one or more `IOReactor` threads.
- **To run:** Set the system property `-Dserver.type=nio`. This is the default mode if the property is not specified.

### Threaded Server

- **Description:** This mode adopts a traditional model where each client connection is processed by a dedicated worker thread. Although conceptually simpler, it may be less scalable than the NIO model under extremely high loads due to the overhead associated with managing a large number of threads.
- **To run:** Set the system property `-Dserver.type=threaded`.

## Configuration

The server's behavior is configurable via properties files and Java system properties.

### Properties Files

- **`src/main/resources/server.properties`**: `ServerConfig.java` attempts to load configurations from this file first. If the file is not found, or if certain properties are missing, it falls back to default values defined within `ServerConfig.java`.
- **`src/main/resources/log4j.properties`**: Configures the logging behavior for the application using Log4j.

### Key Configuration Options (from `com.jun.config.ServerConfig.java`)

The following are the *default* values, as defined in `ServerConfig.java`, used if not overridden by settings in `src/main/resources/server.properties`.

- **Server Type:**
    - System Property: `server.type`
    - Values: `nio` (default), `threaded`
    - Default Value: `nio` (controlled by `ServerConfig.DEFAULT_SERVER_TYPE`)
- **NIO Server Port:**
    - Default Port: `8080`
    - Config Property: `nio.server.port`
    - Class Constant: `ServerConfig.NIO_SERVER_PORT`
- **Threaded Server Port:**
    - Default Port: `8080`
    - Config Property: `threaded.server.port`
    - Class Constant: `ServerConfig.THREADED_SERVER_PORT`
- **NIO Server SSL Enabled:**
    - Default Value: `false`
    - Config Property: `nio.server.ssl.enabled`
    - Class Constant: `ServerConfig.NIO_SERVER_SSL_ENABLED`
    - Keystore (if SSL enabled): `./src/main/resources/server.jks` (password is defined in `ServerConfig.java` or can be overridden in `server.properties`)
    - Truststore (if SSL enabled): `./src/main/resources/trustedCerts.jks` (password is defined in `ServerConfig.java` or can be overridden in `server.properties`)

To customize these settings:
1.  **Recommended:** Modify or create `src/main/resources/server.properties` with the desired key-value pairs (e.g., `nio.server.port=8081`, `nio.server.ssl.enabled=true`).
2.  **For development/testing:** Alter the default values directly in `ServerConfig.java` and recompile the project.

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
    Use Maven to build the project. Navigate to the project's root directory (containing `pom.xml`) and execute:
    ```bash
    mvn clean package
    ```
    This command compiles the source code, runs tests (unless skipped), and packages the application into a JAR file. The JAR is typically found in the `target/` directory (e.g., `target/simpleNioServer-0.1-SNAPSHOT.jar`).

### Running

Once the project is built, you can run the server from the command line.

1.  **Navigate to the project's root directory.**
2.  **Run the server using `java -jar`, optionally specifying the server type.**

    **To run the NIO Server (default mode, HTTP on port 8080 by default):**
    ```bash
    java -jar target/simpleNioServer-0.1-SNAPSHOT.jar
    ```
    *(This uses the default `server.type=nio`, `nio.server.port=8080`, and `nio.server.ssl.enabled=false` unless overridden in `server.properties` or `ServerConfig.java`.)*

    **To run the NIO Server with SSL enabled (HTTPS):**
    Ensure `nio.server.ssl.enabled=true` is set (e.g., in `server.properties`). The port might also be configured to a different value (e.g., 8443).
    ```bash
    java -jar target/simpleNioServer-0.1-SNAPSHOT.jar
    ```

    **To run the Threaded Server (HTTP on port 8080 by default):**
    ```bash
    java -Dserver.type=threaded -jar target/simpleNioServer-0.1-SNAPSHOT.jar
    ```
    *(This uses `threaded.server.port=8080` unless overridden in `server.properties` or `ServerConfig.java`.)*

    After starting, you'll see log messages. Access the server using a browser or `curl`:
    - Default NIO (HTTP): `curl http://localhost:8080`
    - Default Threaded (HTTP): `curl http://localhost:8080`
    - NIO with SSL (HTTPS, e.g., on port 8443 if configured): `curl https://localhost:8443`
      *(For self-signed certificates, `curl` may require the `-k` or `--insecure` option.)*

## Project Structure

The project follows a standard Maven directory layout. Key components under `src/main/java/com/jun/` include:

```
simpleNioServer/
├── pom.xml                   # Maven project configuration
├── README.md                 # This file
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── jun/
│   │   │           ├── Server.java           # Main entry point
│   │   │           ├── config/
│   │   │           │   └── ServerConfig.java # Server configuration loader and defaults
│   │   │           ├── http/                 # HTTP specific handlers
│   │   │           ├── nioServer/            # NIO server implementation
│   │   │           └── threadedServer/       # Threaded server implementation
│   │   ├── resources/
│   │   │   ├── log4j.properties      # Log4j configuration
│   │   │   ├── server.jks            # Keystore for SSL
│   │   │   ├── server.properties     # Optional server properties override
│   │   │   └── trustedCerts.jks      # Truststore for SSL
│   └── test/
└── target/                         # Compiled code and packaged JAR
```

-   **`com.jun.Server`**: Main entry point; launches NIO or Threaded server based on the `server.type` system property.
-   **`com.jun.config.ServerConfig`**: Centralizes configuration defaults (ports, SSL) and loads from `server.properties`.
-   **`com.jun.http`**: Classes for HTTP message handling.
-   **`com.jun.nioServer`**: Implements the Non-blocking I/O server, including `Acceptor`, `IOReactor`, and SSL handling.
-   **`com.jun.threadedServer`**: Implements the traditional Threaded server with `Worker` threads.
-   **`src/main/resources`**: Contains configuration files, SSL keystores, and truststores.

## Contributing

Contributions are welcome! If you find any issues or have suggestions for improvements, please feel free to:

1.  **Report an Issue:** Open an issue in the project's issue tracker, providing as much detail as possible.
2.  **Submit a Pull Request:**
    *   Fork the repository.
    *   Create a new branch for your feature or bug fix.
    *   Make your changes and commit them with clear messages.
    *   Push your changes to your fork.
    *   Open a pull request against the main repository, describing your changes.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

The MIT License is a permissive free software license originating at the Massachusetts Institute of Technology (MIT). It puts very limited restriction on reuse and has, therefore, high license compatibility.
