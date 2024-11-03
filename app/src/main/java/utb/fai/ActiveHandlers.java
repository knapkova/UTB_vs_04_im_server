package utb.fai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveHandlers {
    private static final long serialVersionUID = 1L;
    private ConcurrentHashMap<String, SocketHandler> activeHandlersMap = new ConcurrentHashMap<>();

    synchronized void sendMessageToAll(SocketHandler sender, String message) {
        for (SocketHandler handler : activeHandlersMap.values()) {
            if (handler != sender) {
                if (!handler.messages.offer(message)) {
                    System.err.printf("Client %s message queue is full, dropping the message!\n", handler.clientID);
                }
            }
        }
    }

    synchronized void sendMessageToRooms(SocketHandler sender, String message, Set<String> rooms) {
        for (SocketHandler handler : activeHandlersMap.values()) {
            if (handler != sender && !Collections.disjoint(handler.joinedRooms, rooms)) {
                if (!handler.messages.offer(message)) {
                    System.err.printf("Client %s message queue is full, dropping the message!\n", handler.clientID);
                }
            }
        }
    }

    synchronized void sendPrivateMessage(SocketHandler sender, String recipientName, String message) {
        for (SocketHandler handler : activeHandlersMap.values()) {
            if (handler.userName != null && handler.userName.equals(recipientName)) {
                String privateMessage = "[" + sender.userName + "] >> " + message;
                if (!handler.messages.offer(privateMessage)) {
                    System.err.printf("Client %s message queue is full, dropping the message!\n", handler.clientID);
                }
                sender.messages.offer("Private message sent to " + recipientName);
                return;
            }
        }
        sender.messages.offer("User " + recipientName + " not found or not online.");
    }

    synchronized boolean add(SocketHandler handler) {
        return activeHandlersMap.put(handler.clientID, handler) == null;
    }

    synchronized boolean remove(SocketHandler handler) {
        return activeHandlersMap.remove(handler.clientID) != null;
    }

    synchronized boolean isNameUnique(String name) {
        for (SocketHandler handler : activeHandlersMap.values()) {
            if (handler.userName != null && handler.userName.equals(name)) {
                return false;
            }
        }
        return true;
    }
}
