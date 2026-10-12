package org.iaproject.memchat;

public interface UserInteraction {

    String read();

    String ask(String agentMessage);

    void display(String answer);
}
