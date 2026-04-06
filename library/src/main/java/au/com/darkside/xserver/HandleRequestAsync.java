package au.com.darkside.xserver;

import java.io.IOException;
import android.os.AsyncTask;

import au.com.darkside.xserver.Client;

public class HandleRequestAsync extends AsyncTask<Void, Void, Void> {
    private Client client;
    private byte opcode;
    private byte arg;
    private int bytesRemaining;

    HandleRequestAsync(Client client, byte opcode, byte arg, int bytesRemaining) {
        this.client = client;
        this.opcode = opcode;
        this.arg = arg;
        this.bytesRemaining = bytesRemaining;
    }

    @Override
    protected Void doInBackground(Void... voids) {
        try {
            client.processRequest(opcode, arg, bytesRemaining);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}