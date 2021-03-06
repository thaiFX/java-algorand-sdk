package com.algorand.algosdk.logic;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Logic class provides static checkProgram function
 * that can be used for client-side program validation for size and execution cost.
 */
public class Logic {

    private static final int MAX_COST = 20000;
    private static final int MAX_LENGTH = 1000;

    private static final int INTCBLOCK_OPCODE = 32;
    private static final int BYTECBLOCK_OPCODE = 38;

    private class LangSpec {
        public int EvalMaxVersion;
        public int LogicSigVersion;
        public Operation[] Ops;
    }

    private class Operation {
        int Opcode;
        String Name;
        int Cost;
        int Size;
        String Returns;
        String[] ArgEnum;
        String ArgEnumTypes;
        String Doc;
        String ImmediateNote;
        String[] Group;
    }

    private static LangSpec langSpec;
    private static Operation[] opcodes;

    /**
     * Performs basic program validation: instruction count and program cost
     * @param program Program to validate
     * @param args Program arguments to validate
     * @return boolean
     * @throws IOException
     */
    public static boolean checkProgram(byte[] program, ArrayList<byte[]> args) throws IOException {
        if (langSpec == null) {
            Reader reader;
            try {
                reader = new InputStreamReader(
                    Logic.class.getResourceAsStream("/langspec.json"),
                    "UTF-8"
                );
            } catch (UnsupportedEncodingException ex) {
                throw new IllegalStateException("langspec opening error");
            }

            Gson g = new GsonBuilder().create();

            langSpec = g.fromJson(reader, LangSpec.class);
            reader.close();
        }

        VarintResult result = Uvarint.parse(program);
        int vlen = result.length;
        if (vlen <= 0) {
            throw new IllegalArgumentException("version parsing error");
        }

        int version = result.value;
        if (version > langSpec.EvalMaxVersion) {
            throw new IllegalArgumentException("unsupported version");
        }

        if (args == null) {
            args = new ArrayList<byte[]>();
        }

        int cost = 0;
        int length = program.length;
        for (int i = 0; i < args.size(); i++) {
            length += args.get(i).length;
        }

        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException("program too long");
        }

        if (opcodes == null) {
            opcodes = new Operation[256];
            for (int i = 0; i < langSpec.Ops.length; i++) {
                Operation op = langSpec.Ops[i];
                opcodes[op.Opcode] = op;
            }
        }

        int pc = vlen;
        while (pc < program.length) {
            int opcode = program[pc] & 0xFF;
            Operation op = opcodes[opcode];
            if (op == null) {
                throw new IllegalArgumentException("invalid instruction");
            }

            cost += op.Cost;
            int size = op.Size;
            if (size == 0) {
                switch (op.Opcode) {
                    case INTCBLOCK_OPCODE:
                        size = checkIntConstBlock(program, pc);
                        break;
                    case BYTECBLOCK_OPCODE:
                        size = checkByteConstBlock(program, pc);
                        break;
                    default:
                        throw new IllegalArgumentException("invalid instruction");
                }
            }
            pc += size;
        }

        if (cost > MAX_COST) {
            throw new IllegalArgumentException("program too costly to run");
        }

        return true;
    }

    static int checkIntConstBlock(byte[] program, int pc) {
        int size = 1;
        VarintResult result = Uvarint.parse(Arrays.copyOfRange(program, pc + size, program.length));
        if (result.length <= 0) {
            throw new IllegalArgumentException(
                String.format("could not decode int const block at pc=%d", pc)
            );
        }
        size += result.length;
        int numInts = result.value;
        for (int i = 0; i < numInts; i++) {
            if (pc + size >= program.length) {
                throw new IllegalArgumentException("int const block exceeds program length");
            }
            result = Uvarint.parse(Arrays.copyOfRange(program, pc + size, program.length));
            if (result.length <= 0) {
                throw new IllegalArgumentException(
                    String.format("could not decode int const[%d] block at pc=%d", i, pc + size)
                );
            }
            size += result.length;
        }
        return size;
    }

    static int checkByteConstBlock(byte[] program, int pc) {
        int size = 1;
        VarintResult result = Uvarint.parse(Arrays.copyOfRange(program, pc + size, program.length));
        if (result.length <= 0) {
            throw new IllegalArgumentException(
                String.format("could not decode byte[] const block at pc=%d", pc)
            );
        }
        size += result.length;
        int numInts = result.value;
        for (int i = 0; i < numInts; i++) {
            if (pc + size >= program.length) {
                throw new IllegalArgumentException("byte[] const block exceeds program length");
            }
            result = Uvarint.parse(Arrays.copyOfRange(program, pc + size, program.length));
            if (result.length <= 0) {
                throw new IllegalArgumentException(
                    String.format("could not decode byte[] const[%d] block at pc=%d", i, pc + size)
                );
            }
            size += result.length;
            if (pc + size >= program.length) {
                throw new IllegalArgumentException("byte[] const block exceeds program length");
            }
            size += result.value;
        }
        return size;
    }
}

class Uvarint {
    public static VarintResult parse(byte[] data) {
        int x = 0;
        int s = 0;
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xff;
            if (b < 0x80) {
                if (i > 9 || i == 9 && b > 1) {
                    return new VarintResult(0, -(i + 1));
                }
                return new VarintResult(x | (b & 0xff) << s, i + 1);
            }
            x |= ((b & 0x7f) & 0xff) << s;
            s += 7;
        }
        return new VarintResult();
    }
}

class VarintResult {
    public int value;
    public int length;

    public VarintResult(int value, int length) {
        this.value = value;
        this.length = length;
    }

    public VarintResult() {
        this.value = 0;
        this.length = 0;
    }
}
