package io.github.truscdo.foldbackup.cli;

import java.io.IOException;
import java.util.List;

/** Shared CLI argument value and positive-integer validation with unified error messages. */
final class CliArgs {
    private CliArgs() {}

    static String requireValue(List<String> args, int index, String option) throws IOException {
        if (index >= args.size() || args.get(index).startsWith("--")) {
            throw new IOException(option + " requires a value");
        }
        return args.get(index);
    }

    static String requireValue(String[] args, int index, String option) throws IOException {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IOException(option + " requires a value");
        }
        return args[index];
    }

    static int requirePositiveInt(List<String> args, int index, String option) throws IOException {
        String value = requireValue(args, index, option);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IOException(option + " requires a positive integer");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException(option + " requires a positive integer", e);
        }
    }
}
