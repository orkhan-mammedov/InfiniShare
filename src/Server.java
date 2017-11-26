import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

public class Server {

    public static void main(String[] args) {
        try {
            Map<SelectionKey, Boolean> clientRequests = new HashMap<>();
            Map<SelectionKey, SocketChannel> ongoingDownloads = new HashMap<>();
            Map<String, List<SelectionKey>> pendingDownloads = new HashMap<>();
            Map<String, List<SelectionKey>> pendingUploads = new HashMap<>();
            ByteBuffer communicationBuffer = ByteBuffer.allocate(CommunicationMessage.MESSAGE_SIZE);
            ByteBuffer applicationBuffer = ByteBuffer.allocate(64 * 1024);
            Selector selector = Selector.open();
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.socket().bind(new InetSocketAddress(0));
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            saveServerPort(serverSocketChannel.socket());

            while (serverSocketChannel.isOpen() || !ongoingDownloads.isEmpty()) {
                int readyChannels = selector.select();
                if (readyChannels == 0) {
                    continue;
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey selectionKey = keyIterator.next();

                    if (selectionKey.isAcceptable()) { // New client request
                        SocketChannel clientSocketChannel = serverSocketChannel.accept();
                        clientSocketChannel.configureBlocking(false);
                        SelectionKey clientSelectionKey = clientSocketChannel.register(selector, SelectionKey.OP_READ);
                        clientRequests.put(clientSelectionKey, true);
                    } else if (selectionKey.isReadable()) {
                        if (clientRequests.containsKey(selectionKey)) { // Request from client
                            processClientRequest(selectionKey, serverSocketChannel, selector, communicationBuffer, applicationBuffer,
                                                 ongoingDownloads, pendingDownloads, pendingUploads, clientRequests);
                            clientRequests.remove(selectionKey);
                        } else {
                            forwardPackets(ongoingDownloads, applicationBuffer, selectionKey);
                        }
                    }/* else if(selectionKey.isWritable()){
                        Socket is almost always writable, but in case socket buffer is full, we might need to become interested in OP_WRITE
                    }*/

                    keyIterator.remove();
                }
            }
            serverSocketChannel.close();
            selector.close();
        } catch (IOException e) {
            System.out.println("Error occurred, please see the message bellow.");
            System.out.println(e.getMessage());
        }
    }

    private static void forwardPackets(Map<SelectionKey, SocketChannel> ongoingDownloads, ByteBuffer applicationBuffer, SelectionKey uploaderKey) throws IOException {
        if(!ongoingDownloads.containsKey(uploaderKey)){ // Rare case when shutting down the server, and selector set containing key for request
            return;
        }

        SocketChannel clientSocketChannel = (SocketChannel) uploaderKey.channel();
        SocketChannel downloadChannel = ongoingDownloads.get(uploaderKey);
        int bytesRead;
        try {
            bytesRead = clientSocketChannel.read(applicationBuffer);
        } catch (IOException e){
            ongoingDownloads.remove(uploaderKey);
            clientSocketChannel.close();
            downloadChannel.close();
            applicationBuffer.clear();
            return;
        }

        if (bytesRead == -1) {// Uploader is done, need to shutdown connection with downloader
            ongoingDownloads.remove(uploaderKey);
            downloadChannel.close();
            uploaderKey.cancel();
        } else {
            applicationBuffer.flip();
            try {
                while (applicationBuffer.hasRemaining()) {
                    downloadChannel.write(applicationBuffer);
                }
            }catch (IOException e){
                ongoingDownloads.remove(uploaderKey);
                clientSocketChannel.close();
                downloadChannel.close();
            }
        }
        applicationBuffer.clear();
    }

    private static void processClientRequest(SelectionKey clientSocketKey,
                                             ServerSocketChannel serverSocketChannel,
                                             Selector selector,
                                             ByteBuffer communicationBuffer,
                                             ByteBuffer applicationBuffer,
                                             Map<SelectionKey, SocketChannel> ongoingDownloads,
                                             Map<String, List<SelectionKey>> pendingDownloads,
                                             Map<String, List<SelectionKey>> pendingUploads,
                                             Map<SelectionKey, Boolean> clientRequests) throws IOException {
        SocketChannel clientSocketChannel = (SocketChannel) clientSocketKey.channel();
        int bytesRead = clientSocketChannel.read(communicationBuffer);
        communicationBuffer.flip();

        if (bytesRead == CommunicationMessage.MESSAGE_SIZE) {
            CommunicationMessage communicationMessage = CommunicationMessage.decodeMessage(communicationBuffer.array());
            if (communicationMessage.getOperationType() == CommunicationMessage.PUT) {
                List<SelectionKey> listOfPendingDownloads = pendingDownloads.get(communicationMessage.getOperationKey());
                if (listOfPendingDownloads != null && !listOfPendingDownloads.isEmpty()) {
                    SelectionKey downloadChannelKey = listOfPendingDownloads.remove(0);
                    if(listOfPendingDownloads.isEmpty()){
                        pendingDownloads.remove(communicationMessage.getOperationKey());
                    }
                    SocketChannel downloadChannel = (SocketChannel) downloadChannelKey.channel();
                    ongoingDownloads.put(clientSocketKey, downloadChannel);
                    forwardPackets(ongoingDownloads, applicationBuffer, clientSocketKey); // Need to forward those packets that were buffered
                } else if (pendingUploads.containsKey(communicationMessage.getOperationKey())) {
                    pendingUploads.get(communicationMessage.getOperationKey()).add(clientSocketKey);
                    clientSocketKey.cancel();
                } else {
                    List<SelectionKey> listOfPendingUploads = new ArrayList<>();
                    listOfPendingUploads.add(clientSocketKey);
                    pendingUploads.put(communicationMessage.getOperationKey(), listOfPendingUploads);
                    clientSocketKey.cancel();
                }
            } else if (communicationMessage.getOperationType() == CommunicationMessage.GET) {
                List<SelectionKey> listOfPendingUploads = pendingUploads.get(communicationMessage.getOperationKey());
                if (listOfPendingUploads != null && !listOfPendingUploads.isEmpty()) {
                    SelectionKey uploadChannelKey = listOfPendingUploads.remove(0);
                    if(listOfPendingUploads.isEmpty()){
                        pendingUploads.remove(communicationMessage.getOperationKey());
                    }
                    SocketChannel uploadChannel = (SocketChannel) uploadChannelKey.channel();
                    try {
                        uploadChannelKey = uploadChannel.register(selector, SelectionKey.OP_READ);
                    } catch (IOException e){
                        uploadChannel.close();
                        clientSocketChannel.close();
                        communicationBuffer.clear();
                        return;
                    }
                    ongoingDownloads.put(uploadChannelKey, clientSocketChannel);
                    forwardPackets(ongoingDownloads, applicationBuffer, uploadChannelKey); // Need to forward those packets that were buffered
                } else if (pendingDownloads.containsKey(communicationMessage.getOperationKey())) {
                    pendingDownloads.get(communicationMessage.getOperationKey()).add(clientSocketKey);
                } else {
                    List<SelectionKey> listOfPendingDownloads = new ArrayList<>();
                    listOfPendingDownloads.add(clientSocketKey);
                    pendingDownloads.put(communicationMessage.getOperationKey(), listOfPendingDownloads);
                }
                clientSocketKey.cancel();
            } else {
                clientSocketKey.cancel();
                clientSocketChannel.close();
                serverSocketChannel.close();

                // Close all pending requests
                Iterator<Map.Entry<SelectionKey, Boolean>> clientRequestIter = clientRequests.entrySet().iterator();
                while (clientRequestIter.hasNext()) {
                    Map.Entry<SelectionKey, Boolean> entry = clientRequestIter.next();
                    SelectionKey selectionKey = entry.getKey();
                    selectionKey.channel().close();
                }
                // Close all pending uploads and downloads
                closeClientConnections(pendingUploads);
                closeClientConnections(pendingDownloads);
            }
        }
        communicationBuffer.clear();
    }

    private static void closeClientConnections(Map<String, List<SelectionKey>> pendingClients) throws IOException {
        Iterator<Map.Entry<String, List<SelectionKey>>> pendingDownloadIter = pendingClients.entrySet().iterator();
        while (pendingDownloadIter.hasNext()) {
            Map.Entry<String, List<SelectionKey>> entry = pendingDownloadIter.next();
            List<SelectionKey> selectionKeys = entry.getValue();
            for(SelectionKey selectionKey :  selectionKeys){
                selectionKey.channel().close();
            }
        }
    }

    private static void saveServerPort(ServerSocket socket) {
        try {
            PrintWriter writer = new PrintWriter("port", "UTF-8");
            writer.print(socket.getLocalPort());
            writer.close();
        } catch (IOException e) {
            System.out.println("Error occurred while creating file with address info.");
            e.printStackTrace();
        }
    }
}
