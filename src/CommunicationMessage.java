import java.nio.ByteBuffer;
import java.util.Arrays;

public class CommunicationMessage {
    private char operationType;
    private String operationKey;

    public static int MESSAGE_SIZE = 9;
    public static char GET = 'G';
    public static char PUT = 'P';
    public static char FINISH = 'F';

    public CommunicationMessage(char operationType, String operationKey) {
        this.operationType = operationType;
        this.operationKey = operationKey;
    }

    public static byte[] encodeMessage(CommunicationMessage message){
        byte operationType = (byte) (message.getOperationType() & 0x00FF);
        byte[] operationKey = convertStringToByteArray(message.getOperationKey());
        byte[] encodedMessage = new byte[MESSAGE_SIZE];

        encodedMessage[0] = operationType;
        System.arraycopy(operationKey, 0, encodedMessage, 1, 8);
        return encodedMessage;
    }

    public static CommunicationMessage decodeMessage(byte[] message){
        char operationType = (char) (message[0] & 0x00FF);
        String operationKey = convertByteArrayToString(Arrays.copyOfRange(message, 1, MESSAGE_SIZE));

        return new CommunicationMessage(operationType, operationKey);
    }

    public char getOperationType() {
        return operationType;
    }

    public void setOperationType(char operationType) {
        this.operationType = operationType;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public void setOperationKey(String operationKey) {
        this.operationKey = operationKey;
    }

    private static byte[] convertStringToByteArray(String string){
        byte[] byteArray = new byte[8];
        char[] charArray = string.toCharArray();

        for(int i = 0; i < 8; i++){
            if(i < charArray.length){
                byteArray[i] = (byte) (charArray[i] & 0x00FF);
            } else {
                byteArray[i] = (byte) ('\0' & 0x00FF);
            }
        }
        return byteArray;
    }

    private static String convertByteArrayToString(byte[] byteArray){
        StringBuilder stringBuilder = new StringBuilder();

        for(int i = 0; i < 8; i++){
            char c = (char) (byteArray[i] & 0x00FF);
            if(c != '\0'){
                stringBuilder.append(c);
            }
        }
        return stringBuilder.toString();
    }
}
