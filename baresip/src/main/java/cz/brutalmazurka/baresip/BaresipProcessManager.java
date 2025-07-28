package cz.brutalmazurka.baresip;

import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BaresipProcessManager {

    private Process baresipProcess;
    private BufferedWriter commandWriter;
    private ExecutorService outputMonitorExecutor = Executors.newSingleThreadExecutor();

    private static final String ANSI_REGEX = "\\u001B\\[[;\\d]*m";

    /**
     * Starts Baresip as a subprocess
     */
    public void startBaresip() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("baresip");
        pb.redirectErrorStream(true); // merge stderr with stdout
        baresipProcess = pb.start();

        commandWriter = new BufferedWriter(new OutputStreamWriter(baresipProcess.getOutputStream()));

        // Start monitoring output
        monitorOutput(baresipProcess.getInputStream());
        System.out.println("Baresip started.");
    }

    /**
     * Send command to Baresip stdin
     */
    public void sendCommand(String command) throws IOException {
        if (commandWriter != null) {
            commandWriter.write(command + "\n");
            commandWriter.flush();
            System.out.println("Command sent: " + command);
        }
    }

    /**
     * Monitor Baresip stdout
     */
    private void monitorOutput(InputStream inputStream) {
        outputMonitorExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String cleanLine = stripAnsiCodes(line);
                    processLine(cleanLine);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Parse important Baresip output lines
     */
    private void processLine(String line) {
        System.out.println("Baresip: " + line);

        if (line.contains("call: connecting")) {
            System.out.println("Call is connecting...");
        } else if (line.contains("call: established")) {
            System.out.println("Call established.");
        } else if (line.contains("call: terminated")) {
            System.out.println("Call terminated.");
        } else if (line.matches(".*486 Busy Here.*")) {
            System.out.println("Call collision (486 Busy Here).");
        } else if (line.matches(".*(408 Request Timeout|500 Server Error|503 Service Unavailable).*")) {
            System.out.println("SIP Error: " + line);
        }
    }

    /**
     * Strip ANSI escape sequences from output lines
     */
    private String stripAnsiCodes(String input) {
        return input.replaceAll(ANSI_REGEX, "");
    }

    /**
     * Graceful shutdown
     */
    public void stop() {
        if (baresipProcess != null) {
            baresipProcess.destroy();
            outputMonitorExecutor.shutdownNow();
            System.out.println("Baresip stopped.");
        }
    }

}
