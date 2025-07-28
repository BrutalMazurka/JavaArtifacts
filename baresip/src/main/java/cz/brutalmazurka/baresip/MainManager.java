package cz.brutalmazurka.baresip;

public class MainManager {
    public static void main(String[] args) {
        BaresipProcessManager manager = new BaresipProcessManager();
        try {
            manager.startBaresip();

            Thread.sleep(5000);
            manager.sendCommand("dial sip:5b87727@10.0.1.68");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
