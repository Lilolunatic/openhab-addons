package org.openhab.binding.bluetooth.egloconnect.internal.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class EgloConnectCommand {

    final Lock stateLock;
    final Condition stateCondition;
    CommandState commandState;

    public enum CommandState {
        NEW,
        QUEUED,
        SENT,
        SUCCESS,
        FAIL
    }

    public EgloConnectCommand() {
        this.stateLock = new ReentrantLock();
        this.stateCondition = stateLock.newCondition();
        this.commandState = CommandState.NEW;
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

    public void awaitCommandStates(CommandState... args) {

        ArrayList<CommandState> list = new ArrayList<>(Arrays.asList(args));

        this.stateLock.lock();

        try {
            while (!list.contains(commandState)) {
                if (!stateCondition.await(3, TimeUnit.SECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            stateLock.unlock();
        }
    }

    public CommandState getCommandState() {
        return commandState;
    }
}