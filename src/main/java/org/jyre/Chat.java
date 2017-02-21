package org.jyre;

import org.zeromq.api.Backgroundable;
import org.zeromq.api.Context;
import org.zeromq.api.LoopAdapter;
import org.zeromq.api.Message;
import org.zeromq.api.Reactor;
import org.zeromq.api.Socket;

import javax.print.attribute.standard.DateTimeAtCompleted;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Chat extends Thread {
    private String name;

    private ZreInterface zre;
    private Reactor reactor;

    public Chat(String name) {
        this.name = name;
    }

    @Override
    public void run() {
        zre = new ZreInterface();
        if (name != null) {
            zre.setName(name);
        }
        if (System.getProperty("verbose", "false").equals("true")) {
            zre.setVerbose();
        }
        zre.start();
        zre.join("home");

        Socket pipe = zre.getContext().fork(new ChatHandler());
        reactor = zre.getContext().buildReactor()
            .withInPollable(zre.getSocket(), new ZyreHandler())
            .withInPollable(pipe, new UserHandler())
            .build();
        reactor.start();
    }

    private void exit() {
        zre.close();
        reactor.stop();
    }

    public static void main(String[] args) {
        String name = null;
        if (args.length > 0) {
            name = args[0];
        }

        new Chat(name).start();
    }

    private class ChatHandler implements Backgroundable {
        private String peer;
        private String group = "home";

        @Override
        public void run(Context context, Socket socket) {
            while (true) {
                String line = readLine();
                if (line != null) {
                    if (line.startsWith("/")) {
                        String command = line.substring(1, line.contains(" ") ? line.indexOf(" ") : line.length());
                        String message = line.substring(line.indexOf(" ") + 1);
                        switch (command) {
                            case "join":
                                socket.send(new Message("JOIN").addString(message));
                                break;
                            case "leave":
                                socket.send(new Message("LEAVE").addString(message));
                                break;
                            case "status":
                                if (peer != null) {
                                    System.out.printf("You are talking to %s\n", peer);
                                } else {
                                    System.out.printf("You are in %s\n", group);
                                }
                                break;
                            case "exit":
                            case "quit":
                                exit();
                                return;
                        }
                    } else if (line.startsWith("#")) {
                        group = line.substring(1);
                        peer = null;
                    } else if (line.startsWith("@")) {
                        peer = line.substring(1);
                        group = null;
                    } else if (peer != null) {
                        socket.send(new Message("WHISPER").addString(peer).addString(line));
                    } else if (group != null) {
                        socket.send(new Message("SHOUT").addString(group).addString(line));
                    }
                } else {
                    exit();
                    return;
                }
            }
        }

        @Override
        public void onClose() {

        }

        private String readLine() {
            try {
                return new BufferedReader(new InputStreamReader(System.in)).readLine();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    private class ZyreHandler extends LoopAdapter {
        @Override
        protected void execute(Reactor reactor, Socket socket) {
            Message message = zre.receive();
            String command = message.popString();
            switch (command) {
                case "ENTER":
                    onEnter(message);
                    break;
                case "EXIT":
                    onExit(message);
                    break;
                case "JOIN":
                    onJoin(message);
                    break;
                case "LEAVE":
                    onLeave(message);
                    break;
                case "WHISPER":
                    onWhisper(message);
                    break;
                case "SHOUT":
                    onShout(message);
                    break;
                case "EVASIVE":
                    break;
            }
        }

        private void onEnter(Message message) {
            String peer = message.popString();
            String name = zre.getPeerName(peer);
            if (name != null) {
                System.out.printf("%s: %s entered\n", time(), name);
            }
        }

        private void onExit(Message message) {
            message.popString();
            String name = message.popString();
            if (name != null) {
                System.out.printf("%s: %s left\n", time(), name);
            }
        }

        private void onJoin(Message message) {
            String peer = message.popString();
            String group = message.popString();
            String name = zre.getPeerName(peer);
            if (name != null) {
                System.out.printf("%s: %s joined %s\n", time(), name, group);
            }
        }

        private void onLeave(Message message) {
            String peer = message.popString();
            String group = message.popString();
            String name = zre.getPeerName(peer);
            if (name != null) {
                System.out.printf("%s: %s left %s\n", time(), name, group);
            }
        }

        private void onWhisper(Message message) {
            String peer = message.popString();
            String content = message.popString();
            System.out.printf("%s: #%-12s @%-20s %s\n", time(), "private", zre.getPeerName(peer), content);
        }

        private void onShout(Message message) {
            String peer = message.popString();
            String group = message.popString();
            String content = message.popString();
            System.out.printf("%s: #%-12s @%-20s %s\n", time(), group, zre.getPeerName(peer), content);
        }
    }

    private class UserHandler extends LoopAdapter {
        @Override
        protected void execute(Reactor reactor, Socket socket) {
            Message message = socket.receiveMessage();
            String command = message.popString();
            switch (command) {
                case "JOIN":
                    onJoin(message);
                    break;
                case "LEAVE":
                    onLeave(message);
                    break;
                case "WHISPER":
                    onWhisper(message);
                    break;
                case "SHOUT":
                    onShout(message);
                    break;
            }
        }

        private void onJoin(Message message) {
            String group = message.popString();
            zre.join(group);
        }

        private void onLeave(Message message) {
            String group = message.popString();
            zre.leave(group);
        }

        private void onWhisper(Message message) {
            String peer = message.popString();
            String content = message.popString();
            for (String identity : zre.getPeers()) {
                if (zre.getPeerName(identity).equals(peer)) {
                    zre.whisper(new Message(identity).addString(content));
                    break;
                }
            }
            System.out.printf("%s: #%-12s @%-20s %s\n", time(), "private", name, content);
        }

        private void onShout(Message message) {
            String group = message.popString();
            String content = message.popString();
            zre.shout(new Message(group).addString(content));
            System.out.printf("%s: #%-12s @%-20s %s\n", time(), group, name, content);
        }
    }

    private String time() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a")).toLowerCase();
    }
}
