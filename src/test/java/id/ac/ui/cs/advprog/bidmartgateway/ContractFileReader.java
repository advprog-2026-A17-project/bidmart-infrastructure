package id.ac.ui.cs.advprog.bidmartgateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ContractFileReader {
    private ContractFileReader() {
    }

    static String read(String relativePath) {
        try {
            return Files.readString(Path.of(relativePath));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
