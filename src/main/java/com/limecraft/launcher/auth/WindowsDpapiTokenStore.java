package com.limecraft.launcher.auth;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class WindowsDpapiTokenStore implements SecureTokenStore {
    private final Path storeFile;

    public WindowsDpapiTokenStore(Path gameDir) {
        this.storeFile = gameDir.resolve("secure-tokens.properties");
    }

    @Override
    public synchronized void put(String key, String token) throws IOException {
        if (key == null || key.isBlank()) {
            throw new IOException("Token key is required.");
        }
        if (token == null || token.isBlank()) {
            remove(key);
            return;
        }
        Properties props = loadProps();
        props.setProperty(key.trim(), encrypt(token));
        storeProps(props);
    }

    @Override
    public synchronized String get(String key) throws IOException {
        if (key == null || key.isBlank()) {
            return "";
        }
        Properties props = loadProps();
        String encrypted = props.getProperty(key.trim(), "").trim();
        if (encrypted.isBlank()) {
            return "";
        }
        return decrypt(encrypted);
    }

    @Override
    public synchronized void remove(String key) throws IOException {
        if (key == null || key.isBlank()) {
            return;
        }
        Properties props = loadProps();
        props.remove(key.trim());
        storeProps(props);
    }

    private Properties loadProps() throws IOException {
        Properties props = new Properties();
        if (!Files.exists(storeFile)) {
            return props;
        }
        try (var in = Files.newInputStream(storeFile)) {
            props.load(in);
        }
        return props;
    }

    private void storeProps(Properties props) throws IOException {
        Files.createDirectories(storeFile.getParent());
        try (var out = Files.newOutputStream(storeFile)) {
            props.store(out, "Limecraft secure tokens");
        }
    }

    private String encrypt(String plainToken) throws IOException {
        ensureWindows();
        String script = """
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                $plain = [Console]::In.ReadToEnd()
                $secure = ConvertTo-SecureString -String $plain -AsPlainText -Force
                ConvertFrom-SecureString -SecureString $secure
                """;
        return runPowerShell(script, plainToken).trim();
    }

    private String decrypt(String encryptedToken) throws IOException {
        ensureWindows();
        String script = """
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                $encrypted = [Console]::In.ReadToEnd()
                $secure = ConvertTo-SecureString $encrypted
                $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
                try {
                    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
                } finally {
                    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
                }
                """;
        return runPowerShell(script, encryptedToken).trim();
    }

    private void ensureWindows() throws IOException {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase().contains("win")) {
            throw new IOException("Windows DPAPI token storage is only available on Windows.");
        }
    }

    private String runPowerShell(String script, String stdin) throws IOException {
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script)
                .redirectErrorStream(false)
                .start();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            writer.write(stdin == null ? "" : stdin);
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (var out = process.getInputStream(); var err = process.getErrorStream()) {
            out.transferTo(stdout);
            err.transferTo(stderr);
        }

        try {
            process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for PowerShell.", ex);
        }

        if (process.exitValue() != 0) {
            String error = stderr.toString(StandardCharsets.UTF_8).trim();
            throw new IOException(error.isBlank() ? "PowerShell token operation failed." : error);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
