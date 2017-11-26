# InfiniShare

InfiniShare is a project that facilitates concurrent file sharing between multiple users, through the server. Both, server and client, are built using Java NIO, meaning that they follow event driven programming paradigm.

## How to build?

To build both server and client, run: make all.

## Server Program

Server can handle an arbitrary number of concurrent connections and file exchanges, only limited by system configuration or memory. The server is started without any parameters and creates a TCP socket at an OS-assigned port. It prints out the assigned port number and store it in a local file port, which is used when starting clients. The server listens on its main socket and accepts client connections as they arrive. Clients perform an upload or download operation, or instruct the server to terminate.

Both upload and download operations specify a key that is used to match clients with each other, i.e., a client asking for downloading with a specific key receives the file that another client uploads using that key. Files are not stored at the server, but instead clients wait for a match and then data is forwarded directly from the uploader to the downloader. Multiple clients might specify the same key and operation. The server always match a pair of uploader and downloader with the same key, but does not serve clients with the same key and operation in any particular order. The server supports concurrent file exchanges.

When the server receives the termination command from a client, it closes all waiting connections from unmatched clients and not accept any further connections. It completes ongoing file exchanges and terminate only after all file exchanges are finished.

Communication
The data stream sent from the client to the server adheres to the following format:

    command 1 		ASCII character: G (get = download), P (put = upload), or F (finish = termination)
    
    key     8 		ASCII characters (padded at the end by '\0'-characters, if necessary)

In case of an upload, the above 9-byte control information is immediately followed by the binary data stream of the file. In case of download, the server responds with the binary data stream of the file. When a client has completed a file upload, it closes the connection. Then the server closes the download connection to the other client.

## Client Program

The client takes up to 6 parameters and can be invoked in 3 different ways:

    1. terminate server: client <host> <port> F
    
    2. download: client <host> <port> G<key> <file name> <recv size>
    
    3. upload: client <host> <port> P<key> <file name> <send size> <wait time>

The client creates a TCP socket and connects to the server at <host> and <port>. It then transmits the command string given in the 3rd shell parameter to the server as described above, i.e., with padding. When transmitting an 'F' command, the client sends an empty key, i.e., 8 '\0'-characters. When requesting an upload or download, it reads data from or stores data to, respectively, the file specified in the 4th parameter. When uploading and the 4th parameter is given as an integer number, the number is taken as the virtual file size in bytes. In this case, the sender application does not transmit the contents of an actual file, but empty/random data equivalent to the virtual file size.

The 5th parameter specifies the size of the buffer that is transmitted during each individual write/send or read/recv system call during the file transfer - except for the final data chunk that might be smaller. When uploading a file, the 6th parameter specifies a wait time in milliseconds between subsequent write/send system calls.

This parameters allows for a simple form of rate control that is important to test the concurrency of the server.

## Design
Both client and server of my application are built using Java NIO.

The idea of the server, is that we startup and create a single server socket channel (essentially server socket). At this point a server listens for all the incoming connection requests (there is NO busy looping, instead we use Java NIO selector which triggers an event when there is a connection request). When a client starts a connection with the server, server socket channel creates a new socket for this connection, and register that socket with a selector, so that when the packets arrive to this new socket an event is triggered.

When the new socket X, created for a client, receives a first packet it first checks whether this is download/upload request and then checks to see if there upload/download request with the same key already present on the server. If there is one then the server 'pairs' the two clients (this is done by having a hash map which maps uploaders socket reference to downloaders socket reference). This way, whenever there is packet received by the server from the uploader client the server 'forwards' that packet to the downloader client. On the other hand, if there is no other client on the server that has the same key and is of the 'opposite' type (downloader -> uploader and uploader -> downloader), then the serves puts the current client in the pendingUploaders/pendingDownloaders map, which has the following signature Map<String, List<SelectionKey>>, that is it maps key to the list of selection keys (where SelectionKey can be thought of as the channel).

Since Java NIO is event driven API, the server is able to actively and fairly switch between the multiple on-going file transfers, using just a single thread.

When the server receives a packet which is of type 'F', the server close its server socket, that is stops accepting new connection requests from clients. It also tears down connections with the clients that are not paired, and then waits for the competition of the remaining file transfers. After the above is done the server terminates.

The idea behind the client is pretty simple. It first creates a socket and sends the first packet ot the server indicating its intention ('P', 'G' or 'F'). If it is downloader then it just blocks until there is something to read from the socket. If it is uploader it sends as much as possible until TCP flow control prevents from doing so. When every packet is sent/received the client terminates. When the client wants to terminate the server it just sends the special packet type and then terminates.
