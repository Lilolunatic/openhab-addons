package org.openhab.binding.egloconnect.internal.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class EgloConnectCommand {

    final Lock stateLock;
    final Condition stateCondition;
    CommandState commandState;

    public EgloConnectCommand() {
        this.stateLock = new ReentrantLock();
        this.stateCondition = stateLock.newCondition();
        this.commandState = CommandState.NEW;
    }

    public enum CommandState {
        NEW,
        QUEUED,
        SENT,
        SUCCESS,
        FAIL
    }

    public void updateCommandState(CommandState commandState) {
        this.stateLock.lock();
        try {
            this.commandState = commandState;
            this.stateCondition.signalAll();
        } finally {
            this.stateLock.unlock();
        }
    }

    // public void awaitCommandStates(ArrayList<CommandState> commandStates) {
    public void awaitCommandStates(CommandState... args) {

        ArrayList<CommandState> list = new ArrayList<>(Arrays.asList(args));

        this.stateLock.lock();

        while (!list.contains(commandState)) {
            try {
                stateCondition.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                stateLock.unlock();
            }
        }
    }

    public CommandState getCommandState() {
        return commandState;
    }
}
