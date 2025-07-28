package cz.brutalmazurka.baresip;

import java.io.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BaresipStdioController {
    private Process baresipProcess;
    private BufferedWriter commandWriter;
    private BufferedReader outputReader;
    private BufferedReader errorReader;
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    
    private volatile String currentCallStatus = "IDLE";
    private volatile String registrationStatus = "UNKNOWN";
    private volatile String currentCallId = null;
    private volatile String incomingCallerId = null;
    
    // ANSI escape sequence pattern for stripping colors
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[;\\d]*m");
    
    public boolean startBaresip() {
        try {
            // Start Baresip process with your config
            ProcessBuilder pb = new ProcessBuilder(
                "baresip", 
                "-f", "/home/martins/.baresip"  // Path to Baresip config directory
            );
            pb.redirectErrorStream(false);
            
            baresipProcess = pb.start();
            
            // Setup I/O streams
            commandWriter = new BufferedWriter(new OutputStreamWriter(baresipProcess.getOutputStream()));
            outputReader = new BufferedReader(new InputStreamReader(baresipProcess.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(baresipProcess.getErrorStream()));
            
            // Start monitoring threads
            scheduler.execute(this::monitorOutput);
            scheduler.execute(this::monitorErrors);
            
            // Periodic status checks
            scheduler.scheduleAtFixedRate(this::requestStatus, 5, 10, TimeUnit.SECONDS);
            
            System.out.println("Baresip started successfully");
            return true;
            
        } catch (IOException e) {
            System.err.println("Failed to start Baresip: " + e.getMessage());
            return false;
        }
    }
    
    private void monitorOutput() {
        try {
            String line;
            while ((line = outputReader.readLine()) != null) {
                // Keep original line for color detection, clean line for parsing
                String cleanLine = stripAnsiCodes(line);
                
                // Show severity indicator based on color
                String severity = "";
                if (isRedText(line)) {
                    severity = "❌ ";
                } else if (isGreenText(line)) {
                    severity = "✅ ";
                }
                
                System.out.println("BARESIP OUT: " + severity + cleanLine);
                processOutputLine(cleanLine, line);
                responseQueue.offer(cleanLine);     // For synchronous command responses
            }
        } catch (IOException e) {
            if (baresipProcess.isAlive()) {
                System.err.println("Error reading Baresip output: " + e.getMessage());
            }
        }
    }
    
    private void monitorErrors() {
        try {
            String line;
            while ((line = errorReader.readLine()) != null) {
                // Strip ANSI escape sequences from error output too
                String cleanLine = stripAnsiCodes(line);
                
                String severity = isRedText(line) ? "❌ " : "⚠️ ";
                System.err.println("BARESIP ERR: " + severity + cleanLine);
                processErrorLine(cleanLine, line);
            }
        } catch (IOException e) {
            if (baresipProcess.isAlive()) {
                System.err.println("Error reading Baresip errors: " + e.getMessage());
            }
        }
    }
    
    private void processOutputLine(String cleanLine, String originalLine) {
        // Registration status
        if (cleanLine.contains("registering with")) {
            registrationStatus = "REGISTERING";
        } else if (cleanLine.contains("registration ok")) {
            registrationStatus = "REGISTERED";
            System.out.println("✓ SIP Registration successful");
        } else if (cleanLine.contains("registration failed")) {
            registrationStatus = "FAILED";
            System.out.println("✗ SIP Registration failed" + (isRedText(originalLine) ? " (RED)" : ""));
        }
        
        // Incoming call detection
        Pattern incomingPattern = Pattern.compile("incoming call from (.+?) \\((.+?)\\)");
        Matcher incomingMatcher = incomingPattern.matcher(cleanLine);
        if (incomingMatcher.find()) {
            incomingCallerId = incomingMatcher.group(1);
            currentCallStatus = "INCOMING";
            System.out.println("📞 Incoming call from: " + incomingCallerId);
            handleIncomingCall(incomingCallerId);
        }
        
        // Call established
        if (cleanLine.contains("call established")) {
            currentCallStatus = "ESTABLISHED";
            System.out.println("✓ Call established" + (isGreenText(originalLine) ? " (GREEN)" : ""));
        }
        
        // Call closed/terminated
        if (cleanLine.contains("call closed") || cleanLine.contains("call terminated")) {
            currentCallStatus = "IDLE";
            currentCallId = null;
            incomingCallerId = null;
            System.out.println("✓ Call ended" + (isRedText(originalLine) ? " (RED - ERROR)" : ""));
        }
        
        // Outgoing call progress
        if (cleanLine.contains("trying") && cleanLine.contains("sip:")) {
            currentCallStatus = "OUTGOING";
            System.out.println("📞 Outgoing call in progress");
        }
        
        // Call answered
        if (cleanLine.contains("answered")) {
            currentCallStatus = "ESTABLISHED";
            System.out.println("✓ Call answered" + (isGreenText(originalLine) ? " (GREEN)" : ""));
        }
        
        // Busy signal (collision detection)
        if (cleanLine.contains("486 Busy Here") || cleanLine.contains("busy")) {
            System.out.println("⚠ Collision detected - target busy" + (isRedText(originalLine) ? " (RED)" : ""));
            handleCollision("BUSY");
        }
        
        // Request terminated (another type of collision)
        if (cleanLine.contains("487 Request Terminated")) {
            System.out.println("⚠ Collision detected - request terminated" + (isRedText(originalLine) ? " (RED)" : ""));
            handleCollision("TERMINATED");
        }
    }
    
    private void processErrorLine(String cleanLine, String originalLine) {
        if (cleanLine.contains("401 Unauthorized")) {
            registrationStatus = "AUTH_FAILED";
            System.err.println("✗ Authentication failed" + (isRedText(originalLine) ? " (RED)" : ""));
        } else if (cleanLine.contains("timeout")) {
            System.err.println("⚠ Network timeout detected" + (isRedText(originalLine) ? " (RED)" : ""));
        }
    }
    
    public boolean makeCall(String sipUri) {
        try {
            if (!currentCallStatus.equals("IDLE")) {
                System.out.println("⚠ Cannot make call - already in call state: " + currentCallStatus);
                return false;
            }
            
            System.out.println("📞 Making call to: " + sipUri);
            return sendCommand("d " + sipUri);
            
        } catch (Exception e) {
            System.err.println("Error making call: " + e.getMessage());
            return false;
        }
    }
    
    public boolean answerCall() {
        try {
            if (!currentCallStatus.equals("INCOMING")) {
                System.out.println("⚠ No incoming call to answer");
                return false;
            }
            
            System.out.println("✓ Answering call");
            return sendCommand("a");
            
        } catch (Exception e) {
            System.err.println("Error answering call: " + e.getMessage());
            return false;
        }
    }
    
    public boolean hangupCall() {
        try {
            if (currentCallStatus.equals("IDLE")) {
                System.out.println("⚠ No active call to hang up");
                return false;
            }
            
            System.out.println("✓ Hanging up call");
            boolean result = sendCommand("b");
            if (result) {
                currentCallStatus = "IDLE";
                currentCallId = null;
                incomingCallerId = null;
            }
            return result;
            
        } catch (Exception e) {
            System.err.println("Error hanging up call: " + e.getMessage());
            return false;
        }
    }
    
    public boolean rejectCall() {
        try {
            if (!currentCallStatus.equals("INCOMING")) {
                System.out.println("⚠ No incoming call to reject");
                return false;
            }
            
            System.out.println("✗ Rejecting call");
            return sendCommand("b"); // Same as hangup for incoming calls
            
        } catch (Exception e) {
            System.err.println("Error rejecting call: " + e.getMessage());
            return false;
        }
    }
    
    private void requestStatus() {
        try {
            sendCommand("s"); // Status command
        } catch (Exception e) {
            System.err.println("Error requesting status: " + e.getMessage());
        }
    }
    
    private boolean sendCommand(String command) throws IOException {
        if (commandWriter == null) {
            throw new IOException("Baresip process not started");
        }
        
        synchronized (commandWriter) {
            commandWriter.write(command);
            commandWriter.newLine();
            commandWriter.flush();
            System.out.println("CMD: " + command);
        }
        
        return true;
    }
    
    private void handleIncomingCall(String caller) {
        System.out.println("Handling incoming call from: " + caller);
        
        // Check for collision - already in a call
        if (currentCallStatus.equals("ESTABLISHED") || currentCallStatus.equals("OUTGOING")) {
            System.out.println("⚠ COLLISION: Already in call, rejecting incoming call");
            scheduler.schedule(() -> rejectCall(), 500, TimeUnit.MILLISECONDS);
            return;
        }
        
        // Auto-answer after 2 seconds (customize as needed)
        scheduler.schedule(() -> {
            if (currentCallStatus.equals("INCOMING") && caller.equals(incomingCallerId)) {
                answerCall();
            }
        }, 2, TimeUnit.SECONDS);
    }
    
    private void handleCollision(String collisionType) {
        System.out.println("⚠ Handling collision type: " + collisionType);
        
        currentCallStatus = "IDLE"; // Reset state
        
        // Implement retry logic based on collision type
        switch (collisionType) {
            case "BUSY":
                System.out.println("Target is busy - implementing backoff retry");
                // Could implement exponential backoff retry here
                break;
            case "TERMINATED":
                System.out.println("Call was terminated - ready for new attempts");
                break;
        }
    }
    
    /**
     * Strip ANSI escape sequences from a string
     * Common sequences: \u001B[31m (red), \u001B[32m (green), \u001B[0m (reset)
     */
    private String stripAnsiCodes(String input) {
        if (input == null) {
            return null;
        }
        return ANSI_PATTERN.matcher(input).replaceAll("");
    }
    
    /**
     * Check if original line contains color codes to determine message severity
     */
    private boolean isRedText(String originalLine) {
        return originalLine != null && originalLine.contains("\u001B[31m");
    }
    
    private boolean isGreenText(String originalLine) {
        return originalLine != null && originalLine.contains("\u001B[32m");
    }
    
    // Wait for specific response with timeout
    public String waitForResponse(String expectedPattern, int timeoutSeconds) {
        try {
            long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000);
            Pattern pattern = Pattern.compile(expectedPattern);
            
            while (System.currentTimeMillis() < endTime) {
                String response = responseQueue.poll(1, TimeUnit.SECONDS);
                if (response != null && pattern.matcher(response).find()) {
                    return response;
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
    
    // Getters for current status
    public String getCurrentCallStatus() {
        return currentCallStatus;
    }
    
    public String getRegistrationStatus() {
        return registrationStatus;
    }
    
    public String getCurrentCallerId() {
        return incomingCallerId;
    }
    
    public boolean isProcessAlive() {
        return baresipProcess != null && baresipProcess.isAlive();
    }
    
    public void shutdown() {
        System.out.println("Shutting down Baresip controller...");
        
        scheduler.shutdown();
        
        if (baresipProcess != null && baresipProcess.isAlive()) {
            try {
                // Send quit command
                sendCommand("q");
                
                // Wait for graceful shutdown
                if (!baresipProcess.waitFor(5, TimeUnit.SECONDS)) {
                    System.out.println("Force killing Baresip process");
                    baresipProcess.destroyForcibly();
                }
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
                baresipProcess.destroyForcibly();
            }
        }
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        System.out.println("Shutdown complete");
    }
}
