package org.iaproject.memchat;

import java.util.Scanner;

public class ConsoleUserInteraction implements UserInteraction {
    private final Scanner scanner;

    public ConsoleUserInteraction() {
        this.scanner = new Scanner(System.in);
    }

    public ConsoleUserInteraction(Scanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String read() {
        System.out.print("User >");
        return scanner.nextLine();
    }

    @Override
    public String ask(String agentMessage) {
        System.out.print("Agent >" + agentMessage);
        System.out.print("User >");
        return scanner.nextLine();
    }

    @Override
    public void display(String answer) {
        System.out.print("Agent >" + answer);
    }

}
