package utb.fai;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.HashSet;

public class SocketHandler {
	/** mySocket je socket, o který se bude tento SocketHandler starat */
	Socket mySocket;

	/** client ID je øetìzec ve formátu <IP_adresa>:<port> */
	String clientID;

	/**
	 * activeHandlers je reference na mnoinu vech právì bìících SocketHandlerù.
	 * Potøebujeme si ji udrovat, abychom mohli zprávu od tohoto klienta
	 * poslat vem ostatním!
	 */
	ActiveHandlers activeHandlers;

	/**
	 * messages je fronta pøíchozích zpráv, kterou musí mít kaý klient svoji
	 * vlastní - pokud bude je pøetíená nebo nefunkèní klientova sí,
	 * èekají zprávy na doruèení právì ve frontì messages
	 */
	ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<String>(20);

	/**
	 * startSignal je synchronizaèní závora, která zaøizuje, aby oba tasky
	 * OutputHandler.run() a InputHandler.run() zaèaly ve stejný okamik.
	 */
	CountDownLatch startSignal = new CountDownLatch(2);

	/** outputHandler.run() se bude starat o OutputStream mého socketu */
	OutputHandler outputHandler = new OutputHandler();
	/** inputHandler.run() se bude starat o InputStream mého socketu */
	InputHandler inputHandler = new InputHandler();
	/**
	 * protoe v outputHandleru nedovedu detekovat uzavøení socketu, pomùe mi
	 * inputFinished
	 */
	volatile boolean inputFinished = false;

	String userName = null;
    HashSet<String> joinedRooms = new HashSet<>();

    public SocketHandler(Socket mySocket, ActiveHandlers activeHandlers) {
        this.mySocket = mySocket;
        clientID = mySocket.getInetAddress().toString() + ":" + mySocket.getPort();
        this.activeHandlers = activeHandlers;
        joinedRooms.add("public");
    }

	class OutputHandler implements Runnable {
        public void run() {
            OutputStreamWriter writer;
            try {
                System.err.println("DBG>Output handler starting for " + clientID);
                startSignal.countDown();
                startSignal.await();
                System.err.println("DBG>Output handler running for " + clientID);
                writer = new OutputStreamWriter(mySocket.getOutputStream(), "UTF-8");
                writer.write("\nWelcome! Please set your name.\n");
                writer.flush();
                while (!inputFinished) {
                    String m = messages.take();// blokující ètení - pokud není ve frontì zpráv nic, uspi se!
                    writer.write(m + "\r\n"); // pokud nìjaké zprávy od ostatních máme,
                    writer.flush(); // poleme je naemu klientovi
                    System.err.println("DBG>Message sent to " + clientID + ":" + m + "\n");
                }
			} catch (IOException e) {
                System.err.println("Error in OutputHandler for " + clientID + ": " + e.getMessage());
            } catch (InterruptedException e) {
                System.err.println("OutputHandler for " + clientID + " was interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
            System.err.println("DBG>Output handler for " + clientID + " has finished.");
        }
    }

	class InputHandler implements Runnable {
        public void run() {
            try {
                System.err.println("DBG>Input handler starting for " + clientID);
                startSignal.countDown();
                startSignal.await();
                System.err.println("DBG>Input handler running for " + clientID);
                String request = "";
                /**
                 * v okamiku, kdy nás Thread pool spustí, pøidáme se do mnoiny
                 * vech aktivních handlerù, aby chodily zprávy od ostatních i nám
                 */
                activeHandlers.add(SocketHandler.this);
                BufferedReader reader = new BufferedReader(new InputStreamReader(mySocket.getInputStream(), "UTF-8"));

				setName(reader.readLine());

				while ((request = reader.readLine()) != null) {
						if (request.startsWith("#")) {
							handleCommand(request);
						} else if (userName != null) {
							String message = "[" + userName + "] >> " + request;
							activeHandlers.sendMessageToRooms(SocketHandler.this, message, joinedRooms);
						} else {
							messages.offer("set your name with #setMyName <name>");
						}
					}
					inputFinished = true;
					messages.offer("OutputHandler, wakeup and die!");
            } catch (UnknownHostException e) {
                System.err.println("Unknown host exception in InputHandler for " + clientID + ": " + e.getMessage());
            } catch (IOException e) {
                System.err.println("IO exception in InputHandler for " + clientID + ": " + e.getMessage());
            } catch (InterruptedException e) {
                System.err.println("InputHandler for " + clientID + " was interrupted: " + e.getMessage());
                Thread.currentThread().interrupt();
            } finally {
                // remove yourself from the set of activeHandlers
                activeHandlers.remove(SocketHandler.this);
            }
            System.err.println("DBG>Input handler for " + clientID + " has finished.");
        }

        private void handleCommand(String command) {
            String[] parts = command.split("\\s+", 3);
            if (parts.length == 0) {
                return;
            }

            String commandName = parts[0];

            switch (commandName) {
                case "#setMyName":
                    handleSetMyName(parts);
                    break;
                case "#sendPrivate":
                    handleSendPrivate(parts);
                    break;
                case "#join":
                    handleJoinRoom(parts);
                    break;
                case "#leave":
                    handleLeaveRoom(parts);
                    break;
                case "#groups":
                    handleShowJoinedRooms();
                    break;
                default:
                    messages.offer("Unknown command: " + command);
            }
        }

        private void handleSetMyName(String[] parts) {
            if (parts.length > 1) {
                setName(parts[1]);
            } else {
                messages.offer("Usage: #setMyName <name>");
            }
        }

        private void handleSendPrivate(String[] parts) {
            if (parts.length > 2) {
                activeHandlers.sendPrivateMessage(SocketHandler.this, parts[1], parts[2]);
            } else {
                messages.offer("Usage: #sendPrivate <recipient> <message>");
            }
        }

        private void handleJoinRoom(String[] parts) {
            if (parts.length > 1) {
                joinedRooms.add(parts[1]);
                messages.offer("Joined room: " + parts[1]);
            } else {
                messages.offer("Usage: #join <room_name>");
            }
        }

        private void handleLeaveRoom(String[] parts) {
            if (parts.length > 1) {
                joinedRooms.remove(parts[1]);
                messages.offer("Left room: " + parts[1]);
            } else {
                messages.offer("Usage: #leave <room_name>");
            }
        }

        private void handleShowJoinedRooms() {
            messages.offer("Your joined rooms: " + String.join(", ", joinedRooms));
        }


        private void setName(String newName) {
            if (isNameValid(newName)) {
                if (activeHandlers.isNameUnique(newName)) {
                    userName = newName;
                    messages.offer("Your name has been set to: " + userName);
                } else {
                    messages.offer("This name is already taken. Please choose another one.");
                }
            }
        }
        private boolean isNameValid(String name) {
            if (name == null || name.trim().isEmpty()) {
                messages.offer("Name cannot be empty. Please try again.");
                return false;
            }
            if (name.contains(" ")) {
                messages.offer("Name cannot contain spaces. Please try again.");
                return false;
            }
            return true;
        }
    }
}
