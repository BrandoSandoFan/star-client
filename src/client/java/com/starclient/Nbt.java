package com.starclient;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal reader for gzipped NBT files (Litematica schematics). Compounds become {@code Map<String, Object>},
 * lists {@code List<Object>}, numbers their boxed types, arrays {@code byte[]}/{@code int[]}/{@code long[]}.
 * Independent of Minecraft's NBT classes, whose accessors change between versions.
 */
public final class Nbt {
	private static final int MAX_DEPTH = 512;

	private Nbt() { }

	/** Reads the root compound of a gzipped NBT file. */
	public static Map<String, Object> readCompressed(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
			byte type = in.readByte();
			if (type != 10) throw new IOException("Root tag is not a compound");
			in.readUTF();
			return compound(in, 0);
		}
	}

	private static Map<String, Object> compound(DataInput in, int depth) throws IOException {
		Map<String, Object> out = new HashMap<>();
		while (true) {
			byte type = in.readByte();
			if (type == 0) return out;
			String name = in.readUTF();
			out.put(name, payload(in, type, depth + 1));
		}
	}

	private static Object payload(DataInput in, byte type, int depth) throws IOException {
		if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
		switch (type) {
			case 1: return in.readByte();
			case 2: return in.readShort();
			case 3: return in.readInt();
			case 4: return in.readLong();
			case 5: return in.readFloat();
			case 6: return in.readDouble();
			case 7: {
				byte[] a = new byte[length(in)];
				in.readFully(a);
				return a;
			}
			case 8: return in.readUTF();
			case 9: {
				byte element = in.readByte();
				int n = length(in);
				List<Object> list = new ArrayList<>(Math.min(n, 4096));
				for (int i = 0; i < n; i++) list.add(payload(in, element, depth + 1));
				return list;
			}
			case 10: return compound(in, depth);
			case 11: {
				int[] a = new int[length(in)];
				for (int i = 0; i < a.length; i++) a[i] = in.readInt();
				return a;
			}
			case 12: {
				long[] a = new long[length(in)];
				for (int i = 0; i < a.length; i++) a[i] = in.readLong();
				return a;
			}
			default: throw new IOException("Unknown NBT tag type " + type);
		}
	}

	private static int length(DataInput in) throws IOException {
		int n = in.readInt();
		if (n < 0 || n > 64 * 1024 * 1024) throw new IOException("Bad NBT array length " + n);
		return n;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Map<String, Object> tag, String key) {
		Object v = tag.get(key);
		return v instanceof Map ? (Map<String, Object>) v : Map.of();
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Map<String, Object> tag, String key) {
		Object v = tag.get(key);
		return v instanceof List ? (List<Object>) v : List.of();
	}

	public static int integer(Map<String, Object> tag, String key) {
		return tag.get(key) instanceof Number n ? n.intValue() : 0;
	}

	public static String string(Map<String, Object> tag, String key) {
		return tag.get(key) instanceof String s ? s : "";
	}
}
