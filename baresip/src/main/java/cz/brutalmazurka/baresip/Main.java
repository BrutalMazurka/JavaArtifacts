package cz.brutalmazurka.baresip;

public class Main {
    public static void main(String[] args) {
        BaresipStdioController controller = new BaresipStdioController();

        try {
            controller.startBaresip();
            Thread.sleep(3000); // Wait for startup

            // Example usage
            System.out.println("Status: " + controller.getCurrentCallStatus());
            controller.makeCall("sip:1001@10.0.1.68");
            Thread.sleep(10000);
            controller.hangupCall();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            controller.shutdown();
        }
    }
}