package cz.brutalmazurka.baresip;

public class MainController {
    public static void main(String[] args) {
        BaresipStdioController controller = new BaresipStdioController();


        try {
            controller.startBaresip();
            Thread.sleep(2000); // Wait for startup
            System.out.println("Registration status: " + controller.getRegistrationStatus());
            System.out.println("Process alive? " + controller.isProcessAlive());

            System.out.println("Status: " + controller.getCurrentCallStatus());
            controller.makeCall("sip:5b87727@10.0.1.68");
            Thread.sleep(10000);
            controller.hangupCall();
            Thread.sleep(10000);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            controller.shutdown();
        }
    }
}
