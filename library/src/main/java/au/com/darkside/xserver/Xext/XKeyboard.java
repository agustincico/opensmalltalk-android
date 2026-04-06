package au.com.darkside.xserver.Xext;

import java.io.IOException;

import android.util.Log;

import au.com.darkside.xserver.Client;
import au.com.darkside.xserver.ErrorCode;
import au.com.darkside.xserver.InputOutput;
import au.com.darkside.xserver.Keyboard;
import au.com.darkside.xserver.Util;
import au.com.darkside.xserver.XServer;

public class XKeyboard {
    private static final byte XkbUseExtension = -123;
    private static final byte XkbGetState = 1;
    private static final byte XkbLatchLockState = 2;
    private static final byte XkbGetControls = 3;
    private static final byte XkbSetControls = 4;
    private static final byte XkbGetMap = 5;
    private static final byte XkbSetMap = 6;

    private static final int KEYCODE_MIN_VALUE = 8;
    private static final int KEYCODE_MAX_VALUE = 255;

    private static final int SERVER_XKB_MAJOR_VERSION = 1;

    public static void processRequest(XServer xServer, Client client, byte opcode, byte arg, int bytesRemaining) throws IOException {
        InputOutput io = client.getInputOutput();
        Keyboard keyboard = xServer.getKeyboard();

        switch (opcode) {
            case XkbUseExtension:
                handleXkbUseExtension(client, io, bytesRemaining);
                break;

            case XkbGetState:
                handleXkbGetState(client, io, keyboard);
                break;

            case XkbLatchLockState:
                handleXkbLatchLockState(io, keyboard, bytesRemaining);
                break;

            case XkbGetControls:
                handleXkbGetControls(client, io);
                break;

            case XkbSetControls:
                handleXkbSetControls(io, keyboard, bytesRemaining);
                break;

            case XkbGetMap:
                handleXkbGetMap(client, io, keyboard, bytesRemaining);
                break;

            case XkbSetMap:
                handleXkbSetMap(io, keyboard, bytesRemaining);
                break;

            default:
                Log.w("XKeyboard", "Unknown XKB opcode: " + opcode);
                io.readSkip(bytesRemaining);
                ErrorCode.write(client, ErrorCode.Implementation, opcode, 0);
                break;
        }
    }

    private static void handleXkbUseExtension(Client client, InputOutput io, int bytesRemaining) throws IOException {
        byte clientMajor = (byte) io.readByte();
        byte clientMinor = (byte) io.readByte();
        io.readSkip(bytesRemaining - 2);

        // Check if the server supports the requested XKB extension version
        boolean supported = isXkbExtensionVersionSupported(clientMajor, clientMinor);

        synchronized (io) {
            Util.writeReplyHeader(client, (byte) 1);
            io.writeInt(0);
            io.writeByte(supported ? (byte) 1 : (byte) 0); // Supported flag
            io.writeByte((byte) 0);
            io.writePadBytes(22);
        }
        io.flush();
    }

    private static boolean isXkbExtensionVersionSupported(byte clientMajor, byte clientMinor) {
        boolean supported;
        if (clientMajor != SERVER_XKB_MAJOR_VERSION) {
            // Pre-release version 0.65 is compatible with 1.00
            supported = (SERVER_XKB_MAJOR_VERSION == 1) && (clientMajor == 0) && (clientMinor == 65);
        } else {
            supported = true;
        }
        return supported;
    }

    private static void handleXkbGetState(Client client, InputOutput io, Keyboard keyboard) throws IOException {
        synchronized (io) {
            Util.writeReplyHeader(client, (byte) 1);
            io.writeInt(0);
            // io.writeByte((byte) keyboard.getState());
            io.writeByte((byte) 0);
            io.writePadBytes(23);
        }
        io.flush();
    }

    private static void handleXkbLatchLockState(InputOutput io, Keyboard keyboard, int bytesRemaining) throws IOException {
        int affectModLocks = io.readByte();
        int modLocks = io.readByte();
        io.readSkip(bytesRemaining - 2);
        // keyboard.latchModifiers(affectModLocks, modLocks);
    }

    private static void handleXkbGetControls(Client client, InputOutput io) throws IOException {
        synchronized (io) {
            Util.writeReplyHeader(client, (byte) 1);
            io.writeInt(5);
            io.writeShort((short) 0);
            io.writeByte((byte) 0);
            io.writeByte((byte) 0);
            io.writePadBytes(20);

            io.writeInt(0); // Hardcoded controls for now
            io.writePadBytes(20);
        }
        io.flush();
    }

    private static void handleXkbSetControls(InputOutput io, Keyboard keyboard, int bytesRemaining) throws IOException {
        io.readSkip(bytesRemaining); // Skip controls for now
    }

    private static void handleXkbGetMap(Client client, InputOutput io, Keyboard keyboard, int bytesRemaining) throws IOException {
        io.readSkip(2); // Skip partial & first-type
        int nTypes = io.readByte();
        int nKeySyms = io.readByte();
        int nKeyActions = io.readByte();
        int nKeyBehaviors = io.readByte();
        io.readSkip(bytesRemaining - 6);

        synchronized (io) {
            Util.writeReplyHeader(client, (byte) 1);
            io.writeInt(nTypes*4 + nKeySyms*4 + nKeyActions + nKeyBehaviors*4);
            io.writePadBytes(24);

            // io.writeInt(keyboard.getXkbKeymapSize()); // Min keycode
            // io.writeInt(keyboard.getXkbKeymapSize()); // Max keycode
            io.writeInt(KEYCODE_MIN_VALUE); // Min keycode
            io.writeInt(KEYCODE_MAX_VALUE); // Max keycode
            io.writePadBytes(16);

            // TODO: Write types, syms, actions, behaviors
            io.writePadBytes(nTypes*4 + nKeySyms*4 + nKeyActions + nKeyBehaviors*4);
        }
        io.flush();
    }

    private static void handleXkbSetMap(InputOutput io, Keyboard keyboard, int bytesRemaining) throws IOException {
        int flags = io.readShort();
        int minKeyCode = io.readByte();
        int maxKeyCode = io.readByte();

        while (bytesRemaining > 4) {
            // int keycode = io.readByte() - keyboard.getXkbMinKeycode();
            int keycode = io.readByte() - KEYCODE_MIN_VALUE;
            int numSyms = io.readByte();
            io.readSkip(2); // Skip pad
            bytesRemaining -= 4;

            for (int i = 0; i < numSyms && bytesRemaining >= 4; i++, bytesRemaining -= 4) {
                int keysym = io.readInt();
                keyboard.updateXkbKeymap(keycode, keysym);
            }
        }
    }
}