import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class Client {
    public static void main(String[] args) {
        if (args.length != 3 && args.length != 5 && args.length != 6) {
            System.out.println("Usage: ");
            System.out.println("client <host> <port> F");
            System.out.println("client <host> <port> G<key> <file name> <recv size>");
            System.out.println("client <host> <port> P<key> <file name> <send size> <wait time>");
            return;
        }
        try {
            Selector selector;
            InetAddress destinationHost;
            int destinationPort;
            char operationType;
            String operationKey;
            String fileName;
            ByteBuffer buffer;
            int waitTime;
            int bufferSize;

            destinationHost = InetAddress.getByName(args[0]);
            destinationPort = Integer.valueOf(args[1]);

            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.connect(new InetSocketAddress(destinationHost, destinationPort));

            if (args.length == 3) {
                operationType = args[2].charAt(0);
                CommunicationMessage communicationMessage = new CommunicationMessage(operationType, "");
                buffer = ByteBuffer.allocate(CommunicationMessage.MESSAGE_SIZE);
                buffer.put(CommunicationMessage.encodeMessage(communicationMessage));
                buffer.flip();
                while (buffer.hasRemaining()) {
                    socketChannel.write(buffer);
                }
                socketChannel.close();
                return;
            }

            selector = Selector.open();
            socketChannel.configureBlocking(false);
            operationType = args[2].charAt(0);
            operationKey = args[2].substring(1);
            fileName = args[3];
            bufferSize = Integer.valueOf(args[4]);
            buffer = ByteBuffer.allocate(bufferSize);

            // Send the request to the server
            CommunicationMessage communicationMessage = new CommunicationMessage(operationType, operationKey);
            buffer.put(CommunicationMessage.encodeMessage(communicationMessage));
            buffer.flip();
            while (buffer.hasRemaining()) {
                socketChannel.write(buffer);
            }
            buffer.clear();

            if (operationType == CommunicationMessage.GET) {
                socketChannel.register(selector, SelectionKey.OP_READ);
                FileOutputStream fileOutputStream = new FileOutputStream(fileName);
                FileChannel fileChannel = fileOutputStream.getChannel();

                while (true) {
                    int readyChannels = selector.select();
                    if (readyChannels == 0) {
                        continue;
                    }
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
                    while (keyIterator.hasNext()) {
                        keyIterator.next();
                        int bytesRead = socketChannel.read(buffer);
                        if (bytesRead != -1) {
                            buffer.flip();
                            while (buffer.hasRemaining()) {
                                fileChannel.write(buffer);
                            }
                            buffer.clear();
                            keyIterator.remove();
                        } else {
                            keyIterator.remove();
                            fileChannel.close();
                            socketChannel.close();
                            selector.close();
                            return;
                        }
                    }
                }
            } else if (operationType == CommunicationMessage.PUT) {
                int bytesRead;
                long virtualFileSize = 0;
                boolean virtualFile = false;
                FileInputStream fileInputStream;
                FileChannel fileChannel = null;

                socketChannel.register(selector, SelectionKey.OP_WRITE);
                waitTime = Integer.valueOf(args[5]);

                if(isInteger(fileName)){
                    virtualFile = true;
                    virtualFileSize = Long.valueOf(fileName);
                    if(virtualFileSize > bufferSize){
                        bytesRead = bufferSize;
                        buffer.put(new byte[bufferSize]);
                        virtualFileSize = virtualFileSize - bufferSize;
                    } else {
                        bytesRead = (int)virtualFileSize;
                        buffer.put(new byte[(int)virtualFileSize]);
                        virtualFileSize = 0;
                    }
                    buffer.flip();
                } else {
                    fileInputStream = new FileInputStream(fileName);
                    fileChannel = fileInputStream.getChannel();

                    bytesRead = fileChannel.read(buffer);
                }

                while (bytesRead != -1) {
                    int readyChannels = selector.select();
                    if (readyChannels == 0) {
                        continue;
                    }
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
                    while (keyIterator.hasNext()) {
                        keyIterator.next();
                        if(!virtualFile) {
                            buffer.flip();
                        }
                        while (buffer.hasRemaining()) {
                            socketChannel.write(buffer);
                        }
                        buffer.clear();
                        keyIterator.remove();
                    }
                    Thread.sleep(waitTime);
                    if(virtualFile) {
                        if(virtualFileSize == 0){
                            bytesRead = -1;
                        } else if(virtualFileSize > bufferSize){
                            buffer.rewind();
                            virtualFileSize = virtualFileSize - bufferSize;
                        } else {
                            bytesRead = (int)virtualFileSize;
                            buffer.put(new byte[(int)virtualFileSize]);
                            buffer.flip();
                            virtualFileSize = 0;
                        }
                    } else {
                        bytesRead = fileChannel.read(buffer);
                    }
                }

                if(!virtualFile) {
                    fileChannel.close();
                }
                socketChannel.close();
                selector.close();
            }

        } catch (IOException | InterruptedException e) {
            System.out.println("Error occurred, please see the message bellow.");
            System.out.println(e.getMessage());
        }
    }

    private static boolean isInteger( String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch(Exception e) {
            return false;
        }
    }
}
