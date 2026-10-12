package org.iaproject.memchat.cli;

import com.williamcallahan.tui4j.compat.bubbletea.*;
import com.williamcallahan.tui4j.compat.lipgloss.Style;
import com.williamcallahan.tui4j.compat.lipgloss.color.Color;

public class CliMain {


    public static class Demo implements Model {
        private final static Style SELECTION = Style.newStyle().foreground(Color.color("205"));
        private final static String[] CHOICES = {"Espresso", "Americano", "tui4j"};

        private int cursor;
        private String choice;

        public Demo(){

        }

        public String getChoice() {
            return choice;
        }

        @Override
        public Command init() {
            return null;
        }

        @Override
        public UpdateResult<? extends Model> update(Message msg) {
            if (msg instanceof KeyPressMessage keyPressMessage) {
                return switch (keyPressMessage.key()) {
                    // "up" and "k" keys move cursor up
                    case "k", "K", "up" -> UpdateResult.from(this.moveUp());

                    // "down" and "j" keys move cursor down
                    case "j", "J", "down" -> UpdateResult.from(this.moveDown());

                    // "enter" selects the current item
                    case "enter" -> UpdateResult.from(this.makeChoice(), QuitMessage::new);

                    // "q" exits the program
                    case "q", "Q" -> UpdateResult.from(this, QuitMessage::new);
                    default -> UpdateResult.from(this);
                };
            }

            return UpdateResult.from(this);
        }
        @Override
        public String view() {
            StringBuilder buffer = new StringBuilder();
            buffer.append("What kind of Coffee would you like to order?\n\n");

            for (int index = 0; index < CHOICES.length; index++) {
                if (cursor == index) {
                    buffer.append(SELECTION.render("[•]", CHOICES[index]));
                } else {
                    buffer.append("[ ] ").append(CHOICES[index]);
                }
                buffer.append("\n");
            }
            buffer.append("\n(press q to quit)");
            return buffer.toString();
        }

        private Model moveUp() {
            if (cursor - 1 < 0) {
                cursor = CHOICES.length - 1;
                return this;
            }
            cursor--;
            return this;
        }

        private Model moveDown() {
            if (cursor + 1 >= CHOICES.length) {
                cursor = 0;
                return this;
            }
            cursor++;
            return this;
        }

        private Model makeChoice() {
            for (int index = 0; index < CHOICES.length ; index++) {
                String choice = CHOICES[index];
                if (index == cursor) {
                    this.choice = choice;
                    return this;
                }
            }
            return this;
        }
    }
    public static void main(String[] args) {
        Demo demoModel = new Demo();
        Program program = new Program(demoModel);
        program.run();

        if (demoModel.getChoice() == null) {
            return;
        }
        System.out.printf("\n---\nYou chose: %s!\n", demoModel.getChoice());
    }
}
