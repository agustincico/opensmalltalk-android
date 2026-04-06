package au.com.darkside.xserver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;


/**
 * An X visual. This is always 32-bit TrueColor.
 *
 * @author Matthew Kwan
 */
public class Visual {
    public final static byte BackingStoreNever = 0;
    public final static byte BackingStoreWhenMapped = 1;
    public final static byte BackingStoreAlways = 2;

    public final static byte StaticGray = 0;
    public final static byte GrayScale = 1;
    public final static byte StaticColor = 2;
    public final static byte PseudoColor = 3;
    public final static byte TrueColor = 4;
    public final static byte DirectColor = 5;

    private final int _id;
    private final int _depth;

    /**
     * Constructor.
     *
     * @param id The visual ID.
     */
    public Visual(int id, int depth) {
        _id = id;
        _depth = depth;
    }

    /**
     * Return the visual's ID.
     *
     * @return The visual's ID.
     */
    public int getId() {
        return _id;
    }

    /**
     * Return whether the visual supports a backing store.
     *
     * @return Whether a backing store is supported.
     */
    public byte getBackingStoreInfo() {
        return BackingStoreAlways;
    }

    /**
     * Return whether the visual supports save-under.
     *
     * @return Whether save-under is supported.
     */
    public boolean getSaveUnder() {
        return false;
    }

    /**
     * Return the depth of the visual.
     * Under Android this is always 32.
     *
     * @return The depth of the visual, in integer.
     */
    public int getDepth() {
        return _depth;
    }

    /**
     * Return the supported depths of the visual.
     *
     * @return A list of the supported depths.
     */
    public List<Integer> getSupportedDepths() {
        return Arrays.asList(24, 32);
    }

    /**
     * Write details of the visual.
     *
     * @param io The input/output stream.
     * @throws IOException
     */
    public void write(InputOutput io) throws IOException {
        io.writeInt(_id);        // Visual ID.
        io.writeByte(TrueColor);    // Class.
        io.writeByte((byte) 8);    // Bits per RGB value.
        io.writeShort((short) (1 << 8));    // Colormap entries.
        io.writeInt(0x00ff0000);    // Red mask.
        io.writeInt(0x0000ff00);    // Green mask.
        io.writeInt(0x000000ff);    // Blue mask.
        io.writePadBytes(4);    // Unused.
    }
}